package com.airplay.streamer.service

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import com.airplay.streamer.util.LogServer
import kotlin.math.roundToInt

/**
 * Our own media session while streaming. It plays "remotely" through a [VolumeProvider],
 * so when it is the system's default volume session, the hardware volume keys and the
 * volume panel slider drive the AirPlay speakers instead of the (silenced) phone.
 *
 * Android picks the most recently *started* active session for volume keys, so whenever
 * another app starts playing we briefly re-enter PLAYING ([reclaimVolumeKeys]) to stay on
 * top. Transport controls (headset buttons, lock screen) are forwarded to the music app.
 */
class StreamMediaSession(
    context: Context,
    private val tracker: MediaInfoTracker,
    private val onVolume: (Float) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    val session = MediaSession(context, "centuryplay-stream")
    private var volumeSteps = 100 // 0..100

    private val volumeProvider = object : VolumeProvider(VOLUME_CONTROL_ABSOLUTE, MAX, 60) {
        override fun onSetVolumeTo(volume: Int) {
            val v = volume.coerceIn(0, MAX)
            currentVolume = v
            onVolume(v / MAX.toFloat())
        }

        override fun onAdjustVolume(direction: Int) {
            val v = (currentVolume + direction * STEP).coerceIn(0, MAX)
            currentVolume = v
            onVolume(v / MAX.toFloat())
        }
    }

    init {
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() { tracker.play() }
            override fun onPause() { tracker.pause() }
            override fun onSkipToNext() { tracker.next() }
            override fun onSkipToPrevious() { tracker.previous() }
            override fun onStop() { tracker.pause() }
        }, handler)
        session.setPlaybackToRemote(volumeProvider)
    }

    fun activate(title: String) {
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "centuryplay")
                .build()
        )
        setPlaying(true)
        session.isActive = true
    }

    fun updateMetadata(title: String?, artist: String?, album: String?, art: Bitmap?, durationMs: Long, subtitle: String) {
        val b = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title ?: subtitle)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, artist ?: "centuryplay")
            .putString(MediaMetadata.METADATA_KEY_ALBUM, album ?: subtitle)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, subtitle)
        if (durationMs > 0) b.putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs)
        if (art != null) b.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art)
        session.setMetadata(b.build())
    }

    /** Reflect the speakers' (group) volume in the system slider. */
    fun setVolume(volume: Float) {
        volumeProvider.currentVolume = (volume * MAX).roundToInt().coerceIn(0, MAX)
    }

    /** Move back to the top of the volume-key priority list after another app started playing. */
    fun reclaimVolumeKeys() {
        if (!session.isActive) return
        setPlaying(false)
        handler.postDelayed({ if (session.isActive) setPlaying(true) }, 150)
        LogServer.log("MediaSession: reclaimed volume keys")
    }

    private fun setPlaying(playing: Boolean) {
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
                )
                .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
        )
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        session.isActive = false
        session.release()
    }

    companion object {
        private const val MAX = 100
        private const val STEP = 5
    }
}
