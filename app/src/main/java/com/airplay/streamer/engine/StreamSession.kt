package com.airplay.streamer.engine

import com.airplay.streamer.discovery.AirPlayDevice
import com.airplay.streamer.util.LogServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

enum class SpeakerStatus { CONNECTING, PLAYING, RECONNECTING, NEEDS_PASSWORD, NEEDS_PIN, NEEDS_ACCEPT, FAILED }

data class SpeakerState(
    val identity: String,
    val name: String,
    val status: SpeakerStatus,
    val volume: Float,
    val transport: Transport = Transport.RAOP,
    val message: String? = null,
    val stats: String = "",
)

data class SessionState(
    val capturing: Boolean = false,
    val speakers: List<SpeakerState> = emptyList(),
    val source: String = "",
) {
    val isStreaming: Boolean get() = capturing && speakers.any { it.status == SpeakerStatus.PLAYING }
    val activeSpeakers: List<SpeakerState> get() = speakers.filter { it.status != SpeakerStatus.FAILED }
    fun speaker(identity: String) = speakers.firstOrNull { it.identity == identity }
}

data class SessionSettings(
    val latencyMs: Int = 2000,
    val preferPcm: Boolean = false,
    val preferAirPlay2: Boolean = false,
    val senderName: String = "centuryplay",
)

/**
 * One streaming session: a capture [StreamEngine] feeding any number of speakers.
 * Speakers can be added/removed while audio is flowing; each connects on the session's
 * coroutine scope (blocking IO), and unexpected disconnects are retried with backoff.
 */
class StreamSession(
    private val scope: CoroutineScope,
    val settings: SessionSettings,
    private val passwordFor: (String) -> String?,
    private val initialVolumeFor: (String) -> Float,
    private val offsetFor: (String) -> Int = { 0 },
    private val hapStore: com.airplay.streamer.airplay2.HapCredentialStore? = null,
) : AudioSink.Listener {

    private val pinRequests = ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<String?>>()
    private val forcePin: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Answer a pending "enter the code" request (null = cancel). */
    fun submitPin(identity: String, pin: String?) {
        pinRequests.remove(identity)?.complete(pin)
    }

    /** (Re)connect [device] using code/password pairing instead of transient pairing. */
    fun pairWithCode(device: AirPlayDevice) {
        forcePin += device.identity
        removeDevice(device.identity)
        addDevice(device)
    }

    /** Called on the connecting thread; blocks until the user answers (max 3 min). */
    private fun requestPin(device: AirPlayDevice, volume: Float): String? {
        val future = java.util.concurrent.CompletableFuture<String?>()
        pinRequests[device.identity] = future
        setSpeaker(device, SpeakerStatus.NEEDS_PIN, volume, Transport.AIRPLAY2, "Enter the code shown on ${device.displayName} (or its AirPlay password)")
        return try {
            future.get(3, java.util.concurrent.TimeUnit.MINUTES)
        } catch (_: Exception) {
            null
        } finally {
            pinRequests.remove(device.identity)
            setSpeaker(device, SpeakerStatus.CONNECTING, volume, Transport.AIRPLAY2)
        }
    }

    val engine = StreamEngine(settings.latencyMs)

    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private class Entry(val device: AirPlayDevice) {
        @Volatile var sink: AudioSink? = null
        @Volatile var job: Job? = null
        @Volatile var volume: Float = 0.5f
        @Volatile var removed = false
        @Volatile var forcedTransport: Transport? = null
    }

    private val entries = ConcurrentHashMap<String, Entry>()
    @Volatile private var playedOnce = false
    private var lastMetadata: TrackMetadata? = null

    /** Called when the capture source dies (e.g. projection revoked). */
    var onSourceEnded: (() -> Unit)? = null

    /** Called when no speaker has been playing for [ALL_LOST_GRACE_MS] (e.g. left the Wi-Fi). */
    var onAllSpeakersLost: (() -> Unit)? = null

    fun start(source: PcmSource) {
        engine.onSourceEnded = { onSourceEnded?.invoke() }
        engine.start(source)
        _state.update { it.copy(capturing = true, source = source.description) }
        scope.launch {
            var lostSince = 0L
            while (isActive && engine.isRunning) {
                delay(2000)
                refreshStats()
                val st = _state.value
                val anyPlaying = st.speakers.any { it.status == SpeakerStatus.PLAYING || it.status == SpeakerStatus.CONNECTING }
                val everPlayed = playedOnce
                if (!anyPlaying && everPlayed) {
                    if (lostSince == 0L) lostSince = System.currentTimeMillis()
                    else if (System.currentTimeMillis() - lostSince > ALL_LOST_GRACE_MS) {
                        LogServer.log("No speaker reachable for ${ALL_LOST_GRACE_MS / 1000} s — ending session")
                        onAllSpeakersLost?.invoke()
                        break
                    }
                } else {
                    lostSince = 0L
                }
            }
        }
    }

    val deviceIdentities: Set<String> get() = entries.keys.toSet()

    fun contains(identity: String) = entries.containsKey(identity)

    fun addDevice(device: AirPlayDevice) {
        val existing = entries[device.identity]
        if (existing != null && existing.job?.isActive == true) return
        val entry = existing ?: Entry(device).also {
            it.volume = initialVolumeFor(device.identity)
            entries[device.identity] = it
        }
        entry.removed = false
        entry.job = scope.launch { connectWithRetry(entry, initial = true) }
    }

    fun removeDevice(identity: String) {
        val entry = entries.remove(identity) ?: return
        entry.removed = true
        entry.job?.cancel()
        val sink = entry.sink
        entry.sink = null
        _state.update { s -> s.copy(speakers = s.speakers.filterNot { it.identity == identity }) }
        if (sink != null) {
            engine.removeSink(sink)
            sink.listener = null
            scope.launch { runCatching { sink.disconnect() } }
        }
    }

    private suspend fun connectWithRetry(entry: Entry, initial: Boolean) {
        val device = entry.device
        var attempt = 0
        while (scope.isActive && !entry.removed) {
            val transport = entry.forcedTransport ?: SinkFactory.transportFor(device, settings.preferAirPlay2)
            setSpeaker(device, if (attempt == 0 && initial) SpeakerStatus.CONNECTING else SpeakerStatus.RECONNECTING, entry.volume, transport)
            val usePin = forcePin.remove(device.identity)
            val sink = try {
                SinkFactory.create(
                    device, transport, settings.senderName, settings.preferPcm, passwordFor(device.identity),
                    hapStore = hapStore,
                    pinProvider = { requestPin(device, entry.volume) },
                    forcePin = usePin,
                )
            } catch (e: SinkException) {
                setSpeaker(device, SpeakerStatus.FAILED, entry.volume, transport, e.message)
                return
            }
            try {
                sink.connect(engine.context)
                if (entry.removed) {
                    runCatching { sink.disconnect() }
                    return
                }
                sink.listener = this
                sink.offsetMs = offsetFor(device.identity)
                sink.setVolume(entry.volume)
                lastMetadata?.let { sink.setMetadata(it) }
                entry.sink = sink
                engine.addSink(sink)
                setSpeaker(device, SpeakerStatus.PLAYING, entry.volume, transport, null, sink.stats())
                playedOnce = true
                LogServer.log("Speaker ${device.displayName} playing via $transport")
                return
            } catch (e: SinkException) {
                LogServer.log("Speaker ${device.displayName} failed: ${e.error} ${e.message}")
                when (e.error) {
                    SinkError.AUTH_REQUIRED, SinkError.AUTH_FAILED -> {
                        // RAOP passwords are entered up front; AirPlay 2 codes are asked for inline.
                        val status = if (transport == Transport.AIRPLAY2) SpeakerStatus.FAILED else SpeakerStatus.NEEDS_PASSWORD
                        setSpeaker(device, status, entry.volume, transport, e.message); return
                    }
                    SinkError.NEEDS_PIN -> {
                        setSpeaker(device, SpeakerStatus.FAILED, entry.volume, transport, e.message); return
                    }
                    SinkError.NEEDS_ACCEPT -> {
                        setSpeaker(device, SpeakerStatus.NEEDS_ACCEPT, entry.volume, transport, e.message); return
                    }
                    SinkError.BUSY, SinkError.UNSUPPORTED -> {
                        setSpeaker(device, SpeakerStatus.FAILED, entry.volume, transport, e.message); return
                    }
                    SinkError.PROTOCOL -> {
                        // A handshake we can't complete: try the device's other protocol once.
                        val alt = if (entry.forcedTransport == null) SinkFactory.alternative(device, transport) else null
                        if (alt != null) {
                            LogServer.log("${device.displayName}: $transport failed, trying $alt")
                            entry.forcedTransport = alt
                            continue
                        }
                        // Retrying a handshake the receiver rejected won't help (and re-prompts Macs).
                        setSpeaker(device, SpeakerStatus.FAILED, entry.volume, transport, e.message)
                        return
                    }
                    else -> {}
                }
                attempt++
                if (attempt >= MAX_ATTEMPTS) {
                    setSpeaker(device, SpeakerStatus.FAILED, entry.volume, transport, e.message)
                    return
                }
                setSpeaker(device, SpeakerStatus.RECONNECTING, entry.volume, transport, e.message)
                delay(BACKOFF_MS[(attempt - 1).coerceAtMost(BACKOFF_MS.size - 1)])
            }
        }
    }

    override fun onSinkDisconnected(sink: AudioSink, error: SinkError?, message: String?) {
        val entry = entries.values.firstOrNull { it.sink === sink } ?: return
        engine.removeSink(sink)
        entry.sink = null
        if (entry.removed) return
        LogServer.log("Speaker ${entry.device.displayName} dropped ($message); reconnecting")
        entry.job = scope.launch {
            delay(1000)
            connectWithRetry(entry, initial = false)
        }
    }

    /** Immediately retry every speaker that isn't playing (e.g. after Wi-Fi came back). */
    fun retryFailed() {
        for (entry in entries.values) {
            if (entry.removed || entry.sink != null) continue
            val status = _state.value.speaker(entry.device.identity)?.status
            if (status == SpeakerStatus.NEEDS_PASSWORD) continue
            entry.job?.cancel()
            entry.job = scope.launch { connectWithRetry(entry, initial = false) }
        }
    }

    fun setVolume(identity: String, volume: Float) {
        val entry = entries[identity] ?: return
        entry.volume = volume.coerceIn(0f, 1f)
        _state.update { s -> s.copy(speakers = s.speakers.map { if (it.identity == identity) it.copy(volume = entry.volume) else it }) }
        val sink = entry.sink ?: return
        scope.launch { sink.setVolume(entry.volume) }
    }

    /** Group volume = loudest speaker; changing it scales every speaker proportionally. */
    val groupVolume: Float get() = entries.values.maxOfOrNull { it.volume } ?: 0f

    fun setGroupVolume(volume: Float) {
        val target = volume.coerceIn(0f, 1f)
        val current = groupVolume
        for ((id, e) in entries) {
            val v = if (current <= 0.001f || entries.size == 1) target else (e.volume * target / current)
            setVolume(id, v)
        }
    }

    fun volumeOf(identity: String): Float? = entries[identity]?.volume

    fun setOffset(identity: String, offsetMs: Int) {
        entries[identity]?.sink?.offsetMs = offsetMs
    }

    fun setMetadata(metadata: TrackMetadata) {
        LogServer.log("Now playing: ${metadata.title} — ${metadata.artist}")
        lastMetadata = metadata
        val sinks = entries.values.mapNotNull { it.sink }
        if (sinks.isEmpty()) return
        scope.launch { sinks.forEach { runCatching { it.setMetadata(metadata) } } }
    }

    private fun refreshStats() {
        _state.update { s ->
            s.copy(speakers = s.speakers.map { sp ->
                val sink = entries[sp.identity]?.sink
                if (sink != null) sp.copy(stats = sink.stats()) else sp
            })
        }
    }

    private fun setSpeaker(
        device: AirPlayDevice, status: SpeakerStatus, volume: Float, transport: Transport,
        message: String? = null, stats: String = "",
    ) {
        val entry = entries[device.identity]
        if (entry == null || entry.removed) return
        val sp = SpeakerState(device.identity, device.displayName, status, volume, transport, message, stats)
        _state.update { s ->
            val others = s.speakers.filterNot { it.identity == device.identity }
            s.copy(speakers = others + sp)
        }
    }

    /** Stops capture and disconnects every speaker (blocking; call off the main thread). */
    fun stop() {
        pinRequests.values.forEach { it.complete(null) }
        engine.stop()
        val sinks = entries.values.mapNotNull { e -> e.removed = true; e.job?.cancel(); e.sink }
        entries.clear()
        sinks.forEach { it.listener = null; runCatching { it.disconnect() } }
        engine.context.release()
        _state.value = SessionState()
    }

    companion object {
        private const val MAX_ATTEMPTS = 8
        private const val ALL_LOST_GRACE_MS = 20_000L
        private val BACKOFF_MS = longArrayOf(1000, 2000, 4000, 8000, 15000, 30000)
    }
}
