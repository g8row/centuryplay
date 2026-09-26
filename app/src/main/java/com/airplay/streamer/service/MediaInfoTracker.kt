package com.airplay.streamer.service

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import com.airplay.streamer.util.LogServer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks what other apps are playing (title/artist/art/position) via MediaSessionManager.
 * Needs notification-listener access (the [NotificationListener] component); without it
 * nothing is reported. Our own media session is always ignored.
 */
class MediaInfoTracker(private val context: Context) {

    data class MediaInfo(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val albumArt: Bitmap? = null,
        val duration: Long = 0,
        val position: Long = 0,
        val isPlaying: Boolean = false,
        val packageName: String? = null,
    ) {
        val hasContent: Boolean get() = title != null || artist != null

        fun displayText(): String = when {
            title != null && artist != null -> "$title • $artist"
            title != null -> title
            artist != null -> artist
            else -> "Unknown"
        }
    }

    private val _mediaInfo = MutableStateFlow(MediaInfo())
    val mediaInfo: StateFlow<MediaInfo> = _mediaInfo.asStateFlow()

    private val _playbackStarted = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Emits the package name whenever another app's session starts playing. */
    val playbackStarted: SharedFlow<String> = _playbackStarted.asSharedFlow()

    private var manager: MediaSessionManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private val controllers = mutableListOf<MediaController>()
    private val callbacks = mutableMapOf<MediaController, MediaController.Callback>()
    private val lastStates = mutableMapOf<String, Int>()
    var activeController: MediaController? = null
        private set
    private var started = false

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        setControllers(list.orEmpty())
    }

    val hasAccess: Boolean get() = hasNotificationAccess(context)

    fun start() {
        if (started) return
        started = true
        val m = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        manager = m
        tryRegister()
    }

    /** Retry registration (e.g. after the user granted notification access). */
    fun tryRegister() {
        val m = manager ?: return
        try {
            val component = ComponentName(context, NotificationListener::class.java)
            m.removeOnActiveSessionsChangedListener(sessionListener)
            m.addOnActiveSessionsChangedListener(sessionListener, component, handler)
            setControllers(m.getActiveSessions(component))
        } catch (e: SecurityException) {
            LogServer.log("MediaInfoTracker: no notification access; track info unavailable")
        } catch (e: Exception) {
            LogServer.log("MediaInfoTracker: ${e.message}")
        }
    }

    fun stop() {
        started = false
        runCatching { manager?.removeOnActiveSessionsChangedListener(sessionListener) }
        setControllers(emptyList())
        handler.removeCallbacksAndMessages(null)
    }

    private fun setControllers(list: List<MediaController>) {
        val filtered = list.filter { it.packageName != context.packageName }
        for (c in controllers) callbacks.remove(c)?.let { runCatching { c.unregisterCallback(it) } }
        controllers.clear()
        controllers.addAll(filtered)
        for (c in filtered) {
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    val pkg = c.packageName
                    val newState = state?.state ?: PlaybackState.STATE_NONE
                    val old = lastStates.put(pkg, newState)
                    if (newState == PlaybackState.STATE_PLAYING && old != PlaybackState.STATE_PLAYING) {
                        _playbackStarted.tryEmit(pkg)
                    }
                    pickActive()
                }

                override fun onMetadataChanged(metadata: MediaMetadata?) = pickActive()
                override fun onSessionDestroyed() = setControllers(controllers.filter { it !== c })
            }
            callbacks[c] = cb
            c.registerCallback(cb, handler)
            lastStates[c.packageName] = c.playbackState?.state ?: PlaybackState.STATE_NONE
        }
        pickActive()
    }

    private fun pickActive() {
        val playing = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        val chosen = playing
            ?: activeController?.takeIf { it in controllers }
            ?: controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PAUSED }
            ?: controllers.firstOrNull()
        activeController = chosen
        _mediaInfo.value = if (chosen == null) MediaInfo() else toInfo(chosen)
    }

    private fun toInfo(c: MediaController): MediaInfo {
        val metadata = c.metadata
        val state = c.playbackState
        return MediaInfo(
            title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
            artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
            album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM),
            albumArt = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
            duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0,
            position = livePosition(state),
            isPlaying = state?.state == PlaybackState.STATE_PLAYING,
            packageName = c.packageName,
        )
    }

    /** Current position, extrapolated from the last update when playing. */
    fun currentPosition(): Long = livePosition(activeController?.playbackState)

    private fun livePosition(state: PlaybackState?): Long {
        state ?: return 0
        if (state.state != PlaybackState.STATE_PLAYING) return state.position
        val elapsed = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
        return state.position + (elapsed * state.playbackSpeed).toLong()
    }

    fun togglePlayback() {
        val c = activeController ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
    }

    fun play() = activeController?.transportControls?.play()
    fun pause() = activeController?.transportControls?.pause()
    fun next() = activeController?.transportControls?.skipToNext()
    fun previous() = activeController?.transportControls?.skipToPrevious()

    companion object {
        fun hasNotificationAccess(context: Context): Boolean {
            val flat = android.provider.Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            return flat?.contains(ComponentName(context, NotificationListener::class.java).flattenToString()) == true
        }
    }
}

/**
 * Notification-listener access lets MediaSessionManager show us other apps' sessions. The
 * system keeps this service bound, so it also hosts [AutoPlayWatcher]: start streaming to the
 * usual speakers automatically when music starts at home.
 */
class NotificationListener : NotificationListenerService() {
    private var watcher: AutoPlayWatcher? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        watcher = AutoPlayWatcher(applicationContext).also { it.start() }
    }

    override fun onListenerDisconnected() {
        watcher?.stop()
        watcher = null
        super.onListenerDisconnected()
    }
}
