package com.airplay.streamer.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.airplay.streamer.MainActivity
import com.airplay.streamer.R
import com.airplay.streamer.discovery.AirPlayDevice
import com.airplay.streamer.engine.AudioRecordSource
import com.airplay.streamer.engine.PcmSource
import com.airplay.streamer.engine.SessionSettings
import com.airplay.streamer.engine.SessionState
import com.airplay.streamer.engine.SpeakerStatus
import com.airplay.streamer.engine.StreamPcmSource
import com.airplay.streamer.engine.StreamSession
import com.airplay.streamer.engine.TrackMetadata
import com.airplay.streamer.shizuku.ShizukuManager
import com.airplay.streamer.util.LogServer
import com.airplay.streamer.util.Prefs
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Foreground service that owns a [StreamSession]: it captures the phone's media audio and
 * streams it to one or more AirPlay speakers.
 *
 * Capture source, in order of preference:
 *  1. Shizuku (shell-user AudioPolicy loopback): no consent dialog, phone speaker silent.
 *  2. MediaProjection playback capture: needs a one-time consent (skipped when the
 *     PROJECT_MEDIA app-op is pre-approved, e.g. by Shizuku setup); the projection token is
 *     kept between sessions so reconnects never ask again.
 *
 * Everything the UI, tile and route provider need is exposed through [state]; commands go
 * through [StreamController].
 */
class AudioCaptureService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "airplay_streaming"
        private const val ALERT_CHANNEL_ID = "airplay_alerts"
        private const val KEY_RESTORE_VOLUME = "restore_music_volume"
        private const val STANDBY_MS = 10 * 60_000L

        const val ACTION_START_PROJECTION = "com.airplay.streamer.START"
        const val ACTION_CONNECT = "com.airplay.streamer.CONNECT"
        const val ACTION_DISCONNECT = "com.airplay.streamer.DISCONNECT"
        const val ACTION_STOP = "com.airplay.streamer.STOP"
        const val ACTION_RELEASE = "com.airplay.streamer.RELEASE"
        const val ACTION_VOLUME_UP = "com.airplay.streamer.VOLUME_UP"
        const val ACTION_VOLUME_DOWN = "com.airplay.streamer.VOLUME_DOWN"
        const val ACTION_SLEEP_TIMER = "com.airplay.streamer.SLEEP_TIMER"
        const val EXTRA_MINUTES = "minutes"

        private val _sleepAt = MutableStateFlow(0L)
        /** Wall-clock millis when the sleep timer stops streaming; 0 = no timer. */
        val sleepAt: StateFlow<Long> = _sleepAt.asStateFlow()
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_DEVICE = "device"
        const val EXTRA_IDENTITY = "identity"

        @Volatile var instance: AudioCaptureService? = null
            private set

        /** True when a MediaProjection token is held — reconnect without a consent dialog. */
        val hasActiveProjection: Boolean get() = instance?.mediaProjection != null

        private val _state = MutableStateFlow(SessionState())
        val state: StateFlow<SessionState> = _state.asStateFlow()

        // Compatibility for the route provider.
        data class StreamingState(val isStreaming: Boolean, val deviceId: String? = null)
        private val _streamingState = MutableStateFlow(StreamingState(false))
        val streamingState: StateFlow<StreamingState> = _streamingState.asStateFlow()
        private val _volumeState = MutableStateFlow(60)
        val volumeState: StateFlow<Int> = _volumeState.asStateFlow()

        /** Live capture level 0..1 for the UI meter. */
        val level: Float get() = instance?.session?.engine?.level ?: 0f
    }

    private val errorHandler = CoroutineExceptionHandler { _, e -> LogServer.log("E/Service: ${e.stackTraceToString()}") }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + errorHandler)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs

    private var mediaProjection: MediaProjection? = null
    private var session: StreamSession? = null
    private var sessionJobs = mutableListOf<Job>()
    private var shizukuPipe: ParcelFileDescriptor? = null
    private var reroutedCapture = false

    private lateinit var tracker: MediaInfoTracker
    private var mediaSession: StreamMediaSession? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var savedMusicVolume = -1
    private var volumeReceiver: BroadcastReceiver? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastArtwork: Bitmap? = null
    private var lastArtworkJpeg: ByteArray? = null
    private var foregroundType = 0
    private var publishedShortcut = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        tracker = MediaInfoTracker(this)
        createChannels()
        ShizukuManager.init(this)
        restoreAfterCrash()
    }

    override fun onDestroy() {
        endSession("service destroyed")
        releaseProjection()
        scope.cancel()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must go foreground promptly after startForegroundService().
        goForeground()
        when (intent?.action) {
            ACTION_START_PROJECTION -> {
                val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                if (data != null) acquireProjection(code, data)
                deviceExtra(intent)?.let { connect(it) }
            }
            ACTION_CONNECT -> deviceExtra(intent)?.let { connect(it) }
            ACTION_DISCONNECT -> intent.getStringExtra(EXTRA_IDENTITY)?.let { disconnect(it) }
            ACTION_STOP -> {
                prefs.autoPlaySuppressedUntil = System.currentTimeMillis() + 60 * 60_000L
                endSession("stopped by user")
            }
            ACTION_RELEASE -> {
                endSession("released")
                releaseProjection()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_SLEEP_TIMER -> setSleepTimer(intent.getIntExtra(EXTRA_MINUTES, 0))
            ACTION_VOLUME_UP -> adjustGroupVolume(+0.05f)
            ACTION_VOLUME_DOWN -> adjustGroupVolume(-0.05f)
            else -> if (session == null && mediaProjection == null) stopSelfIfIdle()
        }
        return START_NOT_STICKY
    }

    private fun deviceExtra(intent: Intent): AirPlayDevice? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_DEVICE, AirPlayDevice::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DEVICE)

    // ------------------------------------------------------------------ projection

    private fun acquireProjection(resultCode: Int, data: Intent) {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        // Foreground with the mediaProjection type *before* getMediaProjection (Android 14).
        goForeground(forceProjectionType = true)
        val projection = try {
            manager.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            LogServer.log("getMediaProjection failed: ${e.message}")
            null
        } ?: return
        mediaProjection?.let { old -> if (old !== projection) runCatching { old.stop() } }
        mediaProjection = projection
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                if (mediaProjection === projection) {
                    LogServer.log("MediaProjection revoked")
                    mediaProjection = null
                    if (session != null && !reroutedCapture) {
                        endSession("capture permission was revoked")
                        notifyAlert(getString(R.string.alert_capture_stopped))
                    }
                }
            }
        }, mainHandler)
    }

    private fun releaseProjection() {
        runCatching { mediaProjection?.stop() }
        mediaProjection = null
    }

    // ------------------------------------------------------------------ session

    private var pendingConsentDevice: AirPlayDevice? = null

    private fun connect(device: AirPlayDevice) {
        pendingConnects.incrementAndGet()
        scope.launch {
            try {
                pendingConsentDevice = device
                val s = startSession() ?: return@launch
                pendingConsentDevice = null
                withContext(Dispatchers.Main) {
                    val viaPrefs = prefs.raw.getString("pair_with_code_next", null) == device.identity
                    if (viaPrefs) prefs.raw.edit().remove("pair_with_code_next").apply()
                    if (pendingPairWithCode == device.identity || viaPrefs) {
                        pendingPairWithCode = null
                        s.pairWithCode(device)
                    } else s.addDevice(device)
                }
            } finally {
                pendingConnects.decrementAndGet()
            }
        }
    }

    private fun disconnect(identity: String) {
        val s = session ?: return
        s.removeDevice(identity)
        // A connect may be in flight (e.g. an output-switcher transfer): don't tear down then.
        if (s.deviceIdentities.isEmpty() && pendingConnects.get() == 0) endSession("last speaker removed")
    }

    /** Creates the session and starts capture. Returns null if no capture source is available. */
    private var stopJob: Job? = null
    private val pendingConnects = java.util.concurrent.atomic.AtomicInteger()

    private val startMutex = kotlinx.coroutines.sync.Mutex()

    /** Returns the running session, creating it (once, even under concurrent connects) if needed. */
    private suspend fun startSession(): StreamSession? = startMutex.withLock { startSessionLocked() }

    private suspend fun startSessionLocked(): StreamSession? {
        session?.let { return it }
        standbyJob?.cancel()
        stopJob?.join()
        val source = openSource() ?: run {
            LogServer.log("No capture source available (need consent or Shizuku)")
            pendingConsentDevice?.let { device ->
                // Shizuku capture failed and there's no projection yet: ask for consent
                // (allowed while our UI is in front; otherwise the alert explains).
                val started = runCatching {
                    startActivity(
                        Intent(this, com.airplay.streamer.router.MediaProjectionConsentActivity::class.java)
                            .putExtra(com.airplay.streamer.router.MediaProjectionConsentActivity.EXTRA_DEVICE, device)
                            .putExtra(com.airplay.streamer.router.MediaProjectionConsentActivity.EXTRA_FORCE_PROJECTION, true)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }.isSuccess
                if (!started) notifyAlert(getString(R.string.alert_need_consent))
            } ?: notifyAlert(getString(R.string.alert_need_consent))
            withContext(Dispatchers.Main) { stopSelfIfIdle() }
            return null
        }
        val settings = SessionSettings(
            latencyMs = prefs.latencyMs.coerceIn(500, 4000),
            preferPcm = prefs.preferPcm,
            preferAirPlay2 = prefs.preferAirPlay2,
            senderName = prefs.senderName,
        )
        val s = StreamSession(
            scope, settings,
            passwordFor = { prefs.password(it) },
            initialVolumeFor = { prefs.speakerVolume(it) },
            offsetFor = { prefs.speakerOffsetMs(it) },
            hapStore = prefs.hapStore,
        )
        s.onSourceEnded = { mainHandler.post { if (session === s) endSession("capture ended") } }
        s.onAllSpeakersLost = {
            mainHandler.post {
                if (session === s) {
                    endSession("speakers unreachable")
                    notifyAlert(getString(R.string.alert_speakers_lost))
                }
            }
        }
        session = s
        s.start(source)
        withContext(Dispatchers.Main) { onSessionStarted(s) }
        return s
    }

    private suspend fun openSource(): PcmSource? {
        if (prefs.useShizukuCapture && Build.VERSION.SDK_INT >= 33) {
            val svc = ShizukuManager.awaitPrivileged(1500)
            if (svc != null) {
                try {
                    val keepLocal = !prefs.silencePhone
                    val pfd = try {
                        svc.startCaptureForUsages(keepLocal, prefs.captureUsages)
                    } catch (e: Exception) {
                        svc.startCapture(keepLocal) // older helper process
                    }
                    shizukuPipe = pfd
                    reroutedCapture = !keepLocal
                    return StreamPcmSource(ParcelFileDescriptor.AutoCloseInputStream(pfd), "Shizuku ${if (keepLocal) "capture" else "reroute"}") {
                        runCatching { svc.stopCapture() }
                    }
                } catch (e: Exception) {
                    LogServer.log("Shizuku capture failed, falling back to MediaProjection: ${e.message}")
                }
            }
        }
        reroutedCapture = false
        val projection = mediaProjection ?: return null
        return try {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .apply { prefs.captureUsages.forEach { addMatchingUsage(it) } }
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(44100)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val minBuffer = AudioRecord.getMinBufferSize(44100, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            @SuppressLint("MissingPermission")
            val record = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBuffer, 44100 * 4 / 5)) // ~200 ms
                .build()
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                null
            } else AudioRecordSource(record, "MediaProjection capture")
        } catch (e: Exception) {
            LogServer.log("Playback capture unavailable: ${e.message}")
            null
        }
    }

    private fun onSessionStarted(s: StreamSession) {
        acquireLocks()
        if (prefs.silencePhone && !reroutedCapture) silencePhone()
        tracker.start()
        val ms = StreamMediaSession(this, tracker) { v -> s.setGroupVolume(v); persistVolumes(s) }
        mediaSession = ms
        ms.activate(getString(R.string.streaming_notification_text))
        registerVolumeReceiver(s)
        registerNetworkCallback(s)
        startDacp(s)

        sessionJobs += scope.launch {
            s.state.collectLatest { st ->
                _state.value = st
                val playing = st.speakers.filter { it.status == SpeakerStatus.PLAYING }
                _streamingState.value = StreamingState(st.isStreaming, playing.firstOrNull()?.identity)
                _volumeState.value = (s.groupVolume * 100).toInt()
                ms.setVolume(s.groupVolume)
                if (playing.isNotEmpty()) {
                    val ids = playing.map { it.identity }.toSet()
                    if (ids != prefs.lastSpeakers || !publishedShortcut) {
                        prefs.lastSpeakers = ids
                        prefs.rememberGroup(ids, playing.joinToString(" + ") { it.name.lowercase() })
                        publishedShortcut = true
                        com.airplay.streamer.ShortcutActivity.publish(
                            this@AudioCaptureService, ids.toList(), playing.joinToString(" + ") { it.name.lowercase() }
                        )
                    }
                }
                withContext(Dispatchers.Main) {
                    updateNotification(st)
                    com.airplay.streamer.AirPlayWidget.update(this@AudioCaptureService, st)
                }
            }
        }
        sessionJobs += scope.launch {
            tracker.mediaInfo.collectLatest { info ->
                delay(300) // coalesce metadata/state bursts
                val subtitle = speakerSummary(_state.value)
                withContext(Dispatchers.Main) {
                    ms.updateMetadata(info.title, info.artist, info.album, info.albumArt, info.duration, subtitle)
                }
                if (prefs.sendMetadata && info.hasContent) {
                    s.setMetadata(
                        TrackMetadata(
                            title = info.title, artist = info.artist, album = info.album,
                            artwork = artworkJpeg(info.albumArt), durationMs = info.duration,
                            positionMs = tracker.currentPosition(), isPlaying = info.isPlaying,
                        )
                    )
                }
            }
        }
        sessionJobs += scope.launch {
            tracker.playbackStarted.collect {
                if (prefs.volumeKeysControlSpeakers) withContext(Dispatchers.Main) { ms.reclaimVolumeKeys() }
            }
        }
        sessionJobs += scope.launch {
            // A player that is "playing" while we capture pure digital silence has opted out
            // of playback capture (Apple Music, some DRM apps). Tell the user why and how to fix.
            val warned = mutableSetOf<String>()
            while (isActive) {
                delay(3000)
                val info = tracker.mediaInfo.value
                val pkg = info.packageName ?: continue
                if (info.isPlaying && s.engine.silenceMs > 6000 && pkg !in warned) {
                    warned += pkg
                    val app = runCatching {
                        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
                    }.getOrDefault(pkg)
                    LogServer.log("$app is playing but capture is silent — app blocks capture")
                    withContext(Dispatchers.Main) {
                        notifyAlert(getString(if (reroutedCapture) R.string.alert_capture_blocked_hard else R.string.alert_capture_blocked, app))
                    }
                }
            }
        }
        sessionJobs += scope.launch {
            // Auto-stop after a long silence, so a forgotten stream doesn't drain the battery.
            while (isActive) {
                delay(10_000)
                val minutes = prefs.autoStopMinutes
                if (minutes > 0 && s.engine.silenceMs > minutes * 60_000L) {
                    withContext(Dispatchers.Main) {
                        endSession("silent for $minutes min")
                        notifyAlert(getString(R.string.alert_auto_stopped, minutes))
                    }
                    break
                }
            }
        }
    }

    /** Ends streaming; keeps the projection (standby) so the next connect is instant. */
    private fun endSession(reason: String) {
        val s = session ?: return
        session = null
        LogServer.log("Ending session: $reason")
        sleepJob?.cancel()
        _sleepAt.value = 0
        // Like unplugging headphones: pause the player so audio never falls back to the
        // phone speaker when streaming stops.
        tracker.pause()
        sessionJobs.forEach { it.cancel() }
        sessionJobs.clear()
        persistVolumes(s)
        unregisterVolumeReceiver()
        unregisterNetworkCallback()
        DacpServer.handler = null
        DacpServer.stop()
        mediaSession?.release()
        mediaSession = null
        tracker.stop()
        restorePhoneVolume()
        releaseLocks()
        val pipe = shizukuPipe
        shizukuPipe = null
        // A new session must not start until this one's capture is fully released (the
        // Shizuku service has a single capture, and stopping late would kill the new one).
        stopJob = scope.launch(Dispatchers.IO) {
            s.stop()
            runCatching { pipe?.close() }
        }
        _state.value = SessionState()
        _streamingState.value = StreamingState(false)
        com.airplay.streamer.AirPlayWidget.update(this, SessionState())
        if (mediaProjection != null && !ShizukuManager.projectionPreApproved(this)) {
            // Keep the projection briefly so a quick reconnect needs no dialog, but not forever:
            // Android shows its screen-sharing indicator while a projection exists.
            updateNotification(SessionState())
            standbyJob?.cancel()
            standbyJob = scope.launch {
                delay(STANDBY_MS)
                withContext(Dispatchers.Main) {
                    if (session == null) {
                        LogServer.log("Standby expired — releasing capture permission")
                        releaseProjection()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        } else {
            // Pre-approved (or Shizuku): re-acquiring is silent, so don't hold anything.
            releaseProjection()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private var standbyJob: Job? = null

    private fun stopSelfIfIdle() {
        if (session == null && mediaProjection == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun persistVolumes(s: StreamSession) {
        for (id in s.deviceIdentities) s.volumeOf(id)?.let { prefs.setSpeakerVolume(id, it) }
    }

    private fun adjustGroupVolume(delta: Float) {
        val s = session ?: return
        s.setGroupVolume((s.groupVolume + delta).coerceIn(0f, 1f))
        persistVolumes(s)
    }

    fun setSpeakerVolume(identity: String, volume: Float) {
        val s = session ?: return
        s.setVolume(identity, volume)
        prefs.setSpeakerVolume(identity, volume)
    }

    fun submitPin(identity: String, pin: String?) {
        session?.submitPin(identity, pin)
    }

    fun pairWithCode(device: AirPlayDevice) {
        val s = session
        if (s != null) s.pairWithCode(device) else {
            pendingPairWithCode = device.identity
            connect(device)
        }
    }

    private var pendingPairWithCode: String? = null

    fun setSpeakerOffset(identity: String, ms: Int) {
        prefs.setSpeakerOffsetMs(identity, ms)
        session?.setOffset(identity, ms)
    }

    fun setGroupVolume(volume: Float) {
        val s = session ?: return
        s.setGroupVolume(volume)
        persistVolumes(s)
    }

    val groupVolume: Float get() = session?.groupVolume ?: 0f

    private var sleepJob: Job? = null

    /** Stop streaming (and pause the player) after [minutes]; 0 cancels. */
    private fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        if (minutes <= 0 || session == null) {
            _sleepAt.value = 0
            updateNotification(_state.value)
            return
        }
        val at = System.currentTimeMillis() + minutes * 60_000L
        _sleepAt.value = at
        LogServer.log("Sleep timer: $minutes min")
        sleepJob = scope.launch {
            delay(minutes * 60_000L)
            withContext(Dispatchers.Main) {
                _sleepAt.value = 0
                prefs.autoPlaySuppressedUntil = System.currentTimeMillis() + 8 * 60 * 60_000L
                endSession("sleep timer")
            }
        }
        updateNotification(_state.value)
    }

    fun isCurrentlyStreaming(): Boolean = session?.state?.value?.isStreaming == true

    // ------------------------------------------------------------------ OS integration

    private fun acquireLocks() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        // Released when the session ends; the timeout is only a safety net.
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "centuryplay:stream").apply { setReferenceCounted(false); acquire(12 * 60 * 60_000L) }
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = wm.createWifiLock(mode, "centuryplay:stream").apply { setReferenceCounted(false); acquire() }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.release() }
        runCatching { wifiLock?.release() }
        wakeLock = null
        wifiLock = null
    }

    private fun silencePhone() {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        if (savedMusicVolume < 0) {
            savedMusicVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            // Persist so a crash/kill mid-stream can't leave the phone silenced.
            prefs.raw.edit().putInt(KEY_RESTORE_VOLUME, savedMusicVolume).apply()
        }
        runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0) }
    }

    private fun restorePhoneVolume() {
        if (savedMusicVolume < 0) return
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusicVolume, 0) }
        savedMusicVolume = -1
        prefs.raw.edit().remove(KEY_RESTORE_VOLUME).apply()
    }

    /** A previous run was killed while the phone was silenced: put the volume back. */
    private fun restoreAfterCrash() {
        val saved = prefs.raw.getInt(KEY_RESTORE_VOLUME, -1)
        if (saved < 0) return
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, saved, 0) }
        prefs.raw.edit().remove(KEY_RESTORE_VOLUME).apply()
    }

    /**
     * Fallback for volume keys that reach the phone's music stream instead of our remote
     * session (e.g. while an app forces the music stream): each step is applied to the
     * speakers and the phone index is put back to its baseline — 0 when the phone is
     * silenced, mid-scale when audio is rerouted (so both directions register). The
     * original phone volume is restored when the session ends.
     */
    private fun registerVolumeReceiver(s: StreamSession) {
        if (!prefs.volumeKeysControlSpeakers) return
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        // Only needed when the phone is silenced by volume (projection capture); when audio
        // is rerouted the keys reach our remote session and the phone index is left alone.
        if (reroutedCapture || savedMusicVolume < 0) return
        val baseline = 0
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) != AudioManager.STREAM_MUSIC) return
                val value = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1)
                if (value < 0 || value == baseline) return
                adjustGroupVolume((value - baseline) / max.toFloat())
                runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, baseline, 0) }
            }
        }
        volumeReceiver = receiver
        val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED) else registerReceiver(receiver, filter)
    }

    /** Remote control from the speakers' side (DACP). */
    private fun startDacp(s: StreamSession) {
        DacpServer.handler = object : DacpServer.Handler {
            override fun onCommand(command: String, query: String?) {
                mainHandler.post {
                    when (command) {
                        "playpause" -> tracker.togglePlayback()
                        "play" -> tracker.play()
                        "pause", "stop", "discrete-pause" -> tracker.pause()
                        "nextitem", "beginff" -> tracker.next()
                        "previtem", "beginrew" -> tracker.previous()
                        "volumeup" -> adjustGroupVolume(+0.05f)
                        "volumedown" -> adjustGroupVolume(-0.05f)
                        "mutetoggle" -> s.setGroupVolume(if (s.groupVolume > 0.01f) 0f else prefs.speakerVolume(s.deviceIdentities.firstOrNull() ?: ""))
                        "setproperty" -> query?.let { q ->
                            // dmcp.device-volume=<dB -30..0> or dmcp.volume=<0..100>
                            Regex("dmcp\\.volume=([0-9.]+)").find(q)?.groupValues?.get(1)?.toFloatOrNull()
                                ?.let { s.setGroupVolume((it / 100f).coerceIn(0f, 1f)) }
                            Regex("dmcp\\.device-volume=(-?[0-9.]+)").find(q)?.groupValues?.get(1)?.toFloatOrNull()
                                ?.let { db -> s.setGroupVolume(((db + 30f) / 30f).coerceIn(0f, 1f)) }
                        }
                    }
                }
            }
        }
        runCatching { DacpServer.start(this) }.onFailure { LogServer.log("DACP: ${it.message}") }
    }

    private fun unregisterVolumeReceiver() {
        volumeReceiver?.let { runCatching { unregisterReceiver(it) } }
        volumeReceiver = null
    }

    private fun registerNetworkCallback(s: StreamSession) {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            private var lost = false
            override fun onLost(network: Network) { lost = true }
            override fun onAvailable(network: Network) {
                if (!lost) return
                lost = false
                LogServer.log("Wi-Fi back — reconnecting speakers")
                s.retryFailed()
            }
        }
        networkCallback = cb
        runCatching {
            cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), cb)
        }
    }

    private fun unregisterNetworkCallback() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        networkCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        networkCallback = null
    }

    private fun artworkJpeg(bitmap: Bitmap?): ByteArray? {
        bitmap ?: return null
        if (bitmap === lastArtwork) return lastArtworkJpeg
        val scale = minOf(1f, 600f / maxOf(bitmap.width, bitmap.height))
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        lastArtwork = bitmap
        lastArtworkJpeg = out.toByteArray()
        return lastArtworkJpeg
    }

    // ------------------------------------------------------------------ notifications

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_streaming), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.channel_streaming_desc)
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL_ID, getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun goForeground(forceProjectionType: Boolean = false) {
        val type = when {
            Build.VERSION.SDK_INT < 29 -> 0
            forceProjectionType || mediaProjection != null -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        }
        val notification = buildNotification(_state.value)
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, type) else startForeground(NOTIFICATION_ID, notification)
            foregroundType = type
        } catch (e: Exception) {
            LogServer.log("startForeground failed: ${e.message}")
        }
    }

    private fun updateNotification(st: SessionState) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(st))
    }

    private fun speakerSummary(st: SessionState): String {
        val names = st.activeSpeakers.map { it.name }
        return when {
            names.isEmpty() -> getString(R.string.no_speakers)
            names.size <= 2 -> names.joinToString(" + ")
            else -> getString(R.string.n_speakers, names.size)
        }
    }

    private fun buildNotification(st: SessionState): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        fun action(act: String, req: Int) = PendingIntent.getService(
            this, req, Intent(this, AudioCaptureService::class.java).setAction(act),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_speaker)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (session != null) {
            val connecting = st.speakers.any { it.status == SpeakerStatus.CONNECTING || it.status == SpeakerStatus.RECONNECTING }
            builder.setContentTitle(getString(R.string.streaming_notification_title, speakerSummary(st)))
                .setContentText(
                    when {
                        connecting -> getString(R.string.connecting)
                        _sleepAt.value > 0 -> getString(R.string.sleep_timer_notification,
                            java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(_sleepAt.value)))
                        else -> st.source.ifEmpty { getString(R.string.streaming_notification_text) }
                    }
                )
                .addAction(R.drawable.ic_volume_down, getString(R.string.volume_down), action(ACTION_VOLUME_DOWN, 2))
                .addAction(R.drawable.ic_stop, getString(R.string.stop_streaming), action(ACTION_STOP, 1))
                .addAction(R.drawable.ic_volume_up, getString(R.string.volume_up), action(ACTION_VOLUME_UP, 3))
                .addAction(
                    R.drawable.ic_speaker, getString(R.string.speakers_action),
                    PendingIntent.getActivity(
                        this, 6, Intent(this, com.airplay.streamer.TileDeviceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            mediaSession?.let {
                builder.setStyle(
                    androidx.media.app.NotificationCompat.MediaStyle()
                        .setMediaSession(android.support.v4.media.session.MediaSessionCompat.Token.fromToken(it.session.sessionToken))
                        .setShowActionsInCompactView(0, 1, 2)
                )
            }
        } else {
            builder.setContentTitle(getString(R.string.standby_title))
                .setContentText(getString(R.string.standby_text))
                .addAction(R.drawable.ic_stop, getString(R.string.release), action(ACTION_RELEASE, 4))
        }
        return builder.build()
    }

    private fun notifyAlert(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        val open = PendingIntent.getActivity(this, 5, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(
            2, NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_speaker)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        )
    }
}
