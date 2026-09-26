package com.airplay.streamer.airplay2

import com.airplay.streamer.engine.AudioPacket
import com.airplay.streamer.engine.AudioSink
import com.airplay.streamer.engine.SinkError
import com.airplay.streamer.engine.SinkException
import com.airplay.streamer.engine.StreamContext
import com.airplay.streamer.engine.TrackMetadata
import com.airplay.streamer.raop.Dmap
import com.airplay.streamer.raop.RaopSink
import com.airplay.streamer.util.LogServer
import com.airplay.streamer.util.NtpClock
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * AirPlay 2 receiver (HomePod, Apple TV, Mac, Sonos, ...) using realtime audio (type 0x60)
 * with NTP timing and transient HAP pairing (PIN 3939) — no FairPlay/MFi needed.
 *
 * Mirrors pyatv `protocols/raop/protocols/airplayv2.py` + `stream_client.py`:
 * pair → SETUP (timing/event) → event channel → SETUP (stream) → RECORD, then raw PCM
 * (audioFormat 0x800, big-endian) encrypted with ChaCha20-Poly1305 (AAD = RTP bytes 4..12,
 * 8-byte nonce appended), `POST /feedback` every 2 s, sync packets once a second.
 */
class AirPlay2Sink(
    private val host: String,
    private val port: Int,
    override val displayName: String,
    @Suppress("unused") private val txt: Map<String, String>,
    private val senderName: String,
    preferPcm: Boolean = false,
    private val identity: String = host,
    private val credentialStore: HapCredentialStore? = null,
    /** Blocks until the user enters the code shown on / set for the receiver; null = cancelled. */
    private val pinProvider: ((String) -> String?)? = null,
    private val forcePin: Boolean = false,
) : AudioSink {

    /**
     * ALAC (ct=2, audioFormat 0x40000 = ALAC/44100/16/2) like OwnTone's airplay.c, which is
     * known to work with Sonos/IKEA Symfonisk and HomePods; raw PCM (ct=1, 0x800) like pyatv.
     */
    private val useAlac = !preferPcm

    override val key: String = "ap2:$host:$port"
    override var listener: AudioSink.Listener? = null
    @Volatile override var offsetMs: Int = 0

    private val connection = AirPlay2Connection(host, port, senderName)
    private val connectionLock = Any()
    private val address: InetAddress = InetAddress.getByName(host)

    private lateinit var context: StreamContext
    private var eventChannel: AirPlay2EventChannel? = null
    private val timingServer = NtpTimingServer()
    private var controlSocket: DatagramSocket? = null
    private var audioSocket: DatagramSocket? = null
    private var dataPort = 0
    private var serverControlPort = 0
    private var audioCipher: Chacha20Poly1305? = null

    private val streamId = Random.nextInt()
    private val sessionUri get() = "rtsp://${connection.localIp}/${streamId.toLong() and 0xFFFFFFFFL}"

    @Volatile private var running = false
    @Volatile private var intentionalStop = false
    private var paired = false
    private var promptTimedOut = false

    private val rtpBase = Random.nextLong(0, 0xFFFFFFFFL)
    private var seq = Random.nextInt(0, 0xFFFF)
    private var firstPacket = true
    private val backlog = arrayOfNulls<ByteArray>(BACKLOG)

    private val packetsSent = AtomicLong()
    private val packetsResent = AtomicLong()
    private var lastTrack: TrackMetadata? = null

    override fun connect(context: StreamContext) {
        this.context = context
        try {
            timingServer.start()
            controlSocket = DatagramSocket()
            startControlListener()

            connection.connect()
            val sharedKey = authenticate()
            paired = true

            // GET /info before SETUP, as pyatv, OwnTone and Apple senders do; Macs don't answer
            // SETUP without it.
            runCatching { exchange("GET", "/info") }
                .onSuccess { LogServer.d(TAG, "GET /info -> ${it.statusCode}") }
                .onFailure { LogServer.d(TAG, "GET /info failed: ${it.message}") }

            val eventPort = setupSession()
            eventChannel = AirPlay2EventChannel(host, eventPort, sharedKey).also {
                if (!it.connectWithRetries()) LogServer.d(TAG, "Event channel failed to open (continuing)")
            }
            setupStream(sharedKey)
            audioSocket = DatagramSocket()

            val nowFrame = if (context.timeline.isStarted) context.timeline.frameAt(NtpClock.nowNanos()) else 0L
            val firstFrame = (nowFrame / AudioPacket.FRAMES + 1) * AudioPacket.FRAMES
            exchange("RECORD", sessionUri, mapOf("Range" to "npt=0-"))
            exchange("FLUSH", sessionUri, mapOf("Range" to "npt=0-", "RTP-Info" to "seq=$seq;rtptime=${rtp(firstFrame)}"))

            running = true
            startFeedbackLoop()
            startSyncSender()
            LogServer.d(TAG, "$displayName: AirPlay 2 streaming (${if (useAlac) "ALAC" else "PCM"}, NTP)")
        } catch (e: SinkException) {
            closeQuietly(); throw e
        } catch (e: ConnectException) {
            closeQuietly(); throw SinkException(SinkError.UNREACHABLE, "Can't reach $displayName", e)
        } catch (e: NoRouteToHostException) {
            closeQuietly(); throw SinkException(SinkError.UNREACHABLE, "Can't reach $displayName", e)
        } catch (e: SocketTimeoutException) {
            closeQuietly()
            // After a successful pairing a timeout is a protocol problem; retrying would only
            // make receivers like Macs prompt the user again.
            throw when {
                // A Mac lets pairing through when its Accept prompt times out (~15 s), then never
                // answers SETUP: the request simply wasn't accepted.
                promptTimedOut -> SinkException(SinkError.NEEDS_ACCEPT, "Accept the request on $displayName within 15 s, then tap again", e)
                paired -> SinkException(SinkError.PROTOCOL, "$displayName stopped answering after pairing", e)
                else -> SinkException(SinkError.UNREACHABLE, "$displayName is not responding", e)
            }
        } catch (e: Exception) {
            closeQuietly()
            val msg = e.message ?: e.javaClass.simpleName
            val error = if (msg.contains("403") || msg.contains("470")) SinkError.AUTH_REQUIRED else SinkError.PROTOCOL
            throw SinkException(error, "AirPlay 2 connection to $displayName failed: $msg", e)
        }
    }

    /**
     * Stored credentials → Pair-Verify; else transient pairing (PIN 3939); if the receiver
     * rejects that (or code pairing was requested) → Pair-Setup with the code shown on /
     * configured for the receiver, stored for next time.
     */
    private fun authenticate(): ByteArray {
        credentialStore?.load(identity)?.let { creds ->
            try {
                LogServer.d(TAG, "$displayName: pair-verify with stored credentials")
                return HapPairing(connection).verify(creds)
            } catch (e: PairingException) {
                LogServer.d(TAG, "$displayName: stored pairing rejected (${e.message}); pairing again")
                credentialStore.clear(identity)
                connection.reopen()
            }
        }
        if (!forcePin) {
            LogServer.d(TAG, "Connected to $host:$port; transient pairing (accept the request on the receiver if asked)")
            val pairing = HapTransientPairing(connection)
            try {
                pairing.pair()
                promptTimedOut = pairing.pinStartMillis >= PROMPT_TIMEOUT_MS
                return pairing.sharedKey
            } catch (e: SocketTimeoutException) {
                throw SinkException(SinkError.NEEDS_ACCEPT, "$displayName didn't answer — accept the AirPlay request on it, then retry", e)
            } catch (e: Exception) {
                if (pinProvider == null) throw e
                LogServer.d(TAG, "$displayName: transient pairing rejected (${e.message}); pairing with a code")
                connection.reopen()
            }
        }
        return pairWithCode()
    }

    private fun pairWithCode(): ByteArray {
        val setup = HapPairing(connection)
        try {
            setup.startSetup() // an Apple TV shows the code now
        } catch (e: SocketTimeoutException) {
            throw SinkException(SinkError.NEEDS_ACCEPT, "Accept the request on $displayName, then try pairing again", e)
        }
        val pin = pinProvider?.invoke(displayName)?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw SinkException(SinkError.NEEDS_PIN, "Pairing with $displayName was cancelled")
        val creds = try {
            setup.finishSetup(pin)
        } catch (e: PairingException) {
            throw SinkException(if (e.wrongPin) SinkError.AUTH_FAILED else SinkError.PROTOCOL, "Pairing with $displayName failed: ${e.message}", e)
        }
        credentialStore?.save(identity, creds)
        connection.reopen()
        return HapPairing(connection).verify(creds)
    }

    private fun setupSession(): Int {
        val body = BinaryPlist.encode(
            mapOf(
                "deviceID" to DEVICE_ID,
                "sessionUUID" to UUID.randomUUID().toString().uppercase(),
                "timingPort" to timingServer.port,
                "timingProtocol" to "NTP",
                "isMultiSelectAirPlay" to true,
                "groupContainsGroupLeader" to false,
                "macAddress" to DEVICE_ID,
                "model" to "iPhone14,3",
                "name" to senderName,
                "osBuildVersion" to "20F66",
                "osName" to "iPhone OS",
                "osVersion" to "16.5",
                "senderSupportsRelay" to false,
                "sourceVersion" to "690.7.1",
                "statsCollectionEnabled" to false,
            )
        )
        // A Mac in "anyone on the same network" mode asks to Accept every new session — for
        // paired senders it holds this SETUP until the prompt is answered.
        connection.setReadTimeout(ACCEPT_WAIT_MS)
        val resp = try {
            exchange("SETUP", sessionUri, body = body, contentType = BinaryPlist.CONTENT_TYPE)
        } catch (e: SocketTimeoutException) {
            throw SinkException(SinkError.NEEDS_ACCEPT, "Accept the request on $displayName, then tap again", e)
        } finally {
            connection.setReadTimeout(10_000)
        }
        if (resp.statusCode != 200) throw SinkException(SinkError.PROTOCOL, "SETUP rejected: ${resp.statusCode}")
        val dict = BinaryPlist.decode(resp.body)
        return BinaryPlist.int(dict, "eventPort") ?: throw SinkException(SinkError.PROTOCOL, "SETUP missing eventPort")
    }

    private fun setupStream(sharedKey: ByteArray) {
        // pyatv derives the audio key from the event-channel keys; any 32-byte key works.
        val shk = Hkdf.expand(AirPlay2EventChannel.EVENTS_SALT, AirPlay2EventChannel.EVENTS_WRITE_INFO, sharedKey)
        val body = BinaryPlist.encode(
            mapOf(
                "streams" to listOf(
                    mapOf(
                        "audioFormat" to if (useAlac) 0x40000 else 0x800,
                        "audioMode" to "default",
                        "controlPort" to controlSocket!!.localPort,
                        "ct" to if (useAlac) 2 else 1,
                        "isMedia" to true,
                        // Must cover our configured latency (settings allow up to 4 s).
                        "latencyMax" to maxOf(88200L, context.latencyFrames + 11025L).toInt(),
                        "latencyMin" to 11025,
                        "shk" to shk,
                        "spf" to AudioPacket.FRAMES,
                        "sr" to 44100,
                        "type" to 0x60,
                        "supportsDynamicStreamID" to false,
                        "streamConnectionID" to (streamId.toLong() and 0xFFFFFFFFL),
                    )
                )
            )
        )
        val resp = exchange("SETUP", sessionUri, body = body, contentType = BinaryPlist.CONTENT_TYPE)
        if (resp.statusCode != 200) throw SinkException(SinkError.PROTOCOL, "stream SETUP rejected: ${resp.statusCode}")
        val stream = BinaryPlist.firstStream(BinaryPlist.decode(resp.body))
            ?: throw SinkException(SinkError.PROTOCOL, "stream SETUP missing streams")
        dataPort = BinaryPlist.int(stream, "dataPort") ?: throw SinkException(SinkError.PROTOCOL, "missing dataPort")
        serverControlPort = BinaryPlist.int(stream, "controlPort") ?: 0
        audioCipher = Chacha20Poly1305(shk, shk)
    }

    private fun rtp(frame: Long): Long = (rtpBase + frame) and 0xFFFFFFFFL

    // ------------------------------------------------------------------ audio (capture thread)

    override fun send(packet: AudioPacket) {
        if (!running) return
        val cipher = audioCipher ?: return
        val header = ByteArray(12)
        header[0] = 0x80.toByte()
        header[1] = if (firstPacket) 0xE0.toByte() else 0x60
        RaopSink.putShort(header, 2, seq)
        RaopSink.putInt(header, 4, rtp(packet.frameIndex))
        RaopSink.putInt(header, 8, streamId.toLong())
        val nonce = cipher.outNonce()
        val payload = if (useAlac) context.alacOf(packet) else packet.bigEndianPcm()
        val encrypted = cipher.encrypt(payload, aad = header.copyOfRange(4, 12))
        val out = ByteArray(12 + encrypted.size + 8)
        System.arraycopy(header, 0, out, 0, 12)
        System.arraycopy(encrypted, 0, out, 12, encrypted.size)
        System.arraycopy(nonce, 4, out, 12 + encrypted.size, 8)
        firstPacket = false
        backlog[seq and (BACKLOG - 1)] = out
        try {
            audioSocket?.send(DatagramPacket(out, out.size, address, dataPort))
            packetsSent.incrementAndGet()
        } catch (e: Exception) {
            if (running) LogServer.d(TAG, "audio send failed: ${e.message}")
        }
        seq = (seq + 1) and 0xFFFF
    }

    // ------------------------------------------------------------------ background loops

    private fun startFeedbackLoop() {
        thread(name = "AirPlay2Feedback", isDaemon = true) {
            var failures = 0
            while (running) {
                try {
                    Thread.sleep(FEEDBACK_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
                if (!running) break
                try {
                    exchangePost("/feedback")
                    failures = 0
                } catch (e: Exception) {
                    failures++
                    if (failures >= 2 && running && !intentionalStop) {
                        LogServer.d(TAG, "$displayName stopped answering feedback: ${e.message}")
                        running = false
                        closeQuietly()
                        listener?.onSinkDisconnected(this, SinkError.DISCONNECTED, "$displayName disconnected")
                        break
                    }
                }
            }
        }
    }

    private fun startSyncSender() {
        val sock = controlSocket ?: return
        if (serverControlPort == 0) return
        thread(name = "AirPlay2Sync", isDaemon = true) {
            var first = true
            while (running && !sock.isClosed) {
                try {
                    if (context.timeline.isStarted) {
                        sock.send(DatagramPacket(syncPacket(first), 20, address, serverControlPort))
                        first = false
                    }
                    Thread.sleep(if (first) 20 else 1000L)
                } catch (_: InterruptedException) {
                    break
                } catch (_: Exception) {
                    if (sock.isClosed) break
                }
            }
        }
    }

    /** Same 0xD4 semantics as RAOP (see [RaopSink]): the frame captured now plays `latency` later. */
    private fun syncPacket(first: Boolean): ByteArray {
        val now = NtpClock.nowNanos()
        // Shifting the mapping by the offset makes this speaker play that much later/earlier.
        val current = context.timeline.frameAt(now) - offsetMs.toLong() * context.sampleRate / 1000
        val p = ByteArray(20)
        p[0] = if (first) 0x90.toByte() else 0x80.toByte()
        p[1] = 0xD4.toByte()
        p[2] = 0x00; p[3] = 0x07
        RaopSink.putInt(p, 4, rtp(current - (context.latencyFrames - 11025)))
        NtpClock.writeNtpNanos(p, 8, now)
        RaopSink.putInt(p, 16, rtp(current))
        return p
    }

    private fun startControlListener() {
        val sock = controlSocket ?: return
        thread(name = "AirPlay2Control", isDaemon = true) {
            val buf = ByteArray(256)
            val pkt = DatagramPacket(buf, buf.size)
            while (!sock.isClosed) {
                try {
                    sock.receive(pkt)
                    if (pkt.length >= 8 && (buf[1].toInt() and 0x7F) == 0x55) {
                        val first = ((buf[4].toInt() and 0xFF) shl 8) or (buf[5].toInt() and 0xFF)
                        val count = ((buf[6].toInt() and 0xFF) shl 8) or (buf[7].toInt() and 0xFF)
                        for (i in 0 until count.coerceAtMost(BACKLOG)) {
                            val s = (first + i) and 0xFFFF
                            val original = backlog[s and (BACKLOG - 1)] ?: continue
                            val origSeq = ((original[2].toInt() and 0xFF) shl 8) or (original[3].toInt() and 0xFF)
                            if (origSeq != s) continue
                            val resp = ByteArray(4 + original.size)
                            resp[0] = 0x80.toByte(); resp[1] = 0xD6.toByte()
                            RaopSink.putShort(resp, 2, s)
                            System.arraycopy(original, 0, resp, 4, original.size)
                            runCatching { sock.send(DatagramPacket(resp, resp.size, pkt.address, pkt.port)) }
                            packetsResent.incrementAndGet()
                        }
                    }
                } catch (_: Exception) {
                    if (sock.isClosed) break
                }
            }
        }
    }

    // ------------------------------------------------------------------ control

    override fun setVolume(volume: Float) {
        if (!running) return
        runCatching {
            exchange(
                "SET_PARAMETER", sessionUri,
                body = "volume: %.6f\r\n".format(java.util.Locale.US, RaopSink.volumeToDb(volume)).toByteArray(Charsets.US_ASCII),
                contentType = "text/parameters",
            )
        }.onFailure { LogServer.d(TAG, "setVolume failed: ${it.message}") }
    }

    override fun setMetadata(metadata: TrackMetadata) {
        if (!running || metadata.sameTrack(lastTrack)) return
        lastTrack = metadata
        // Realtime AirPlay 2 receivers accept the same DMAP track info as RAOP (pyatv
        // stream_client set_metadata); failures are harmless.
        runCatching {
            exchange(
                "SET_PARAMETER", sessionUri,
                body = Dmap.trackInfo(metadata.title, metadata.artist, metadata.album),
                contentType = "application/x-dmap-tagged",
            )
        }
    }

    override fun disconnect() {
        intentionalStop = true
        val wasRunning = running
        running = false
        if (wasRunning) runCatching { exchange("TEARDOWN", sessionUri) }
        closeQuietly()
    }

    private fun closeQuietly() {
        running = false
        eventChannel?.close()
        timingServer.stop()
        runCatching { controlSocket?.close() }
        runCatching { audioSocket?.close() }
        runCatching { connection.close() }
    }

    override fun stats(): String = "AirPlay 2 ${if (useAlac) "ALAC" else "PCM"} · ${packetsSent.get()} pkts · ${packetsResent.get()} resent"

    private fun exchange(
        method: String, uri: String, headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null, contentType: String? = null,
    ): AirPlay2Response = synchronized(connectionLock) { connection.exchange(method, uri, headers, body, contentType) }

    private fun exchangePost(uri: String): AirPlay2Response = synchronized(connectionLock) { connection.post(uri) }

    companion object {
        private const val TAG = "AirPlay2Sink"
        private const val BACKLOG = 1024
        private const val FEEDBACK_INTERVAL_MS = 2000L
        private const val PROMPT_TIMEOUT_MS = 14_000L
        private const val ACCEPT_WAIT_MS = 60_000
        private const val DEVICE_ID = "AA:BB:CC:DD:EE:FF"
    }
}
