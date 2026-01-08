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
import android.service.notification.NotificationListenerService
import com.airplay.streamer.util.LogServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks currently playing media across all apps.
 * Uses MediaSessionManager to get active media sessions and extract metadata.
 * 
 * Requires NOTIFICATION_LISTENER permission to access MediaSessionManager.
 */
class MediaInfoTracker(private val context: Context) {

    companion object {
        private const val TAG = "MediaInfoTracker"
    }

    data class MediaInfo(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val albumArt: Bitmap? = null,
        val duration: Long = 0,
        val position: Long = 0,
        val isPlaying: Boolean = false,
        val packageName: String? = null
    ) {
        val hasContent: Boolean
            get() = title != null || artist != null
            
        fun displayText(): String {
            return when {
                title != null && artist != null -> "$title • $artist"
                title != null -> title
                artist != null -> artist
                else -> "Unknown"
            }
        }
    }

    private val _mediaInfo = MutableStateFlow(MediaInfo())
    val mediaInfo: StateFlow<MediaInfo> = _mediaInfo.asStateFlow()

    private var mediaSessionManager: MediaSessionManager? = null
    private var activeController: MediaController? = null
    private val handler = Handler(Looper.getMainLooper())

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        LogServer.log("$TAG: Active sessions changed, count=${controllers?.size ?: 0}")
        updateActiveController(controllers)
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            LogServer.log("$TAG: Metadata changed")
            updateMediaInfo(metadata, activeController?.playbackState)
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            LogServer.log("$TAG: Playback state changed: ${state?.state}")
            updateMediaInfo(activeController?.metadata, state)
        }

        override fun onSessionDestroyed() {
            LogServer.log("$TAG: Session destroyed")
            _mediaInfo.value = MediaInfo()
        }
    }

    /**
     * Start tracking media sessions.
     * Note: Requires NotificationListenerService permission.
     */
    fun start() {
        try {
            mediaSessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            
            // Get component name for our notification listener (if we have one)
            // For now, try without it - some devices allow this
            val componentName = ComponentName(context, NotificationListener::class.java)
            
            try {
                mediaSessionManager?.addOnActiveSessionsChangedListener(sessionListener, componentName)
                
                // Get initial active sessions
                val controllers = mediaSessionManager?.getActiveSessions(componentName)
                updateActiveController(controllers)
                
                LogServer.log("$TAG: Started tracking media sessions")
            } catch (e: SecurityException) {
                // Try without component name (may work on some devices)
                LogServer.log("$TAG: NotificationListener not enabled, trying fallback")
                tryFallbackTracking()
            }
        } catch (e: Exception) {
            LogServer.log("$TAG: Failed to start: ${e.message}")
        }
    }

    /**
     * Fallback tracking using AudioManager (limited info)
     */
    private fun tryFallbackTracking() {
        // Poll periodically for any active sessions
        // This is less reliable but works without special permissions
        handler.postDelayed(object : Runnable {
            override fun run() {
                try {
                    val controllers = mediaSessionManager?.getActiveSessions(null)
                    if (!controllers.isNullOrEmpty()) {
                        updateActiveController(controllers)
                    }
                } catch (e: Exception) {
                    // Ignore - we don't have permission
                }
                handler.postDelayed(this, 2000)
            }
        }, 1000)
    }

    fun togglePlayback() {
        activeController?.let { controller ->
            val state = controller.playbackState?.state
            if (state == PlaybackState.STATE_PLAYING) {
                controller.transportControls.pause()
            } else {
                controller.transportControls.play()
            }
        }
    }

    fun stop() {
        try {
            mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionListener)
            activeController?.unregisterCallback(controllerCallback)
            activeController = null
            handler.removeCallbacksAndMessages(null)
        } catch (e: Exception) {
            LogServer.log("$TAG: Error stopping: ${e.message}")
        }
    }

    private fun updateActiveController(controllers: List<MediaController>?) {
        if (controllers.isNullOrEmpty()) {
            activeController?.unregisterCallback(controllerCallback)
            activeController = null
            _mediaInfo.value = MediaInfo()
            return
        }

        // Find the most relevant controller (playing > paused > others)
        val playing = controllers.find { 
            it.playbackState?.state == PlaybackState.STATE_PLAYING 
        }
        val paused = controllers.find { 
            it.playbackState?.state == PlaybackState.STATE_PAUSED 
        }
        val newController = playing ?: paused ?: controllers.first()

        if (activeController != newController) {
            activeController?.unregisterCallback(controllerCallback)
            activeController = newController
            activeController?.registerCallback(controllerCallback)
            
            LogServer.log("$TAG: New active controller: ${newController.packageName}")
            updateMediaInfo(newController.metadata, newController.playbackState)
        }
    }

    private fun updateMediaInfo(metadata: MediaMetadata?, playbackState: PlaybackState?) {
        val info = MediaInfo(
            title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
            artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) 
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
            album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM),
            albumArt = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART),
            duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0,
            position = playbackState?.position ?: 0,
            isPlaying = playbackState?.state == PlaybackState.STATE_PLAYING,
            packageName = activeController?.packageName
        )
        
        _mediaInfo.value = info
        
        if (info.hasContent) {
            LogServer.log("$TAG: Now playing: ${info.displayText()}")
        }
    }
}

/**
 * NotificationListenerService stub for MediaSession access.
 * Must be declared in AndroidManifest.xml and enabled by user.
 */
class NotificationListener : NotificationListenerService() {
    // Empty implementation - we just need this for MediaSession access
}
