package com.airplay.streamer.engine

import com.airplay.streamer.audio.AlacEncoder

/** Track info forwarded to receivers that display it. */
data class TrackMetadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    /** JPEG bytes. */
    val artwork: ByteArray? = null,
    val durationMs: Long = 0,
    val positionMs: Long = 0,
    val isPlaying: Boolean = true,
) {
    fun sameTrack(other: TrackMetadata?): Boolean =
        other != null && title == other.title && artist == other.artist && album == other.album
}

/** Shared, read-only view of the session the sinks stream into. */
class StreamContext(
    val timeline: CaptureTimeline,
    /** Frames between capture and playback on every receiver. */
    val latencyFrames: Long,
    val sampleRate: Int = 44100,
) {
    /** Frame that every receiver should be playing at [nanos] (NtpClock timeline). */
    fun playingFrameAt(nanos: Long): Long = timeline.frameAt(nanos) - latencyFrames

    /** Shared encoder; only touched from the capture thread (inside [AudioSink.send]). */
    val alacEncoder = AlacEncoder()
    private val alacScratch = ByteArray(AlacEncoder.maxEncodedSize())

    /** ALAC payload for [packet], encoded once and shared by every sink. */
    fun alacOf(packet: AudioPacket): ByteArray = packet.alac(alacEncoder, alacScratch)

    fun release() = alacEncoder.close()
}

/** Why a receiver session ended or failed, surfaced to the UI. */
enum class SinkError {
    UNREACHABLE, BUSY, AUTH_REQUIRED, AUTH_FAILED, UNSUPPORTED, NEEDS_ACCEPT, NEEDS_PIN, PROTOCOL, DISCONNECTED
}

/**
 * One receiver in a streaming session. `connect`/`disconnect`/`setVolume`/`setMetadata`
 * are blocking network calls (call off the main thread); [send] is called on the capture
 * thread for every packet and must not block for long.
 */
interface AudioSink {
    val key: String
    val displayName: String

    interface Listener {
        fun onSinkDisconnected(sink: AudioSink, error: SinkError?, message: String?)
    }

    var listener: Listener?

    /** Performs the full handshake. Throws [SinkException] on failure. */
    fun connect(context: StreamContext)

    fun send(packet: AudioPacket)

    /** 0..1 */
    fun setVolume(volume: Float)

    /**
     * Extra playback delay for this speaker in milliseconds (positive = later), to line up
     * speakers with different hardware latency or match video.
     */
    var offsetMs: Int

    fun setMetadata(metadata: TrackMetadata) {}

    fun disconnect()

    /** Human-readable one-line stats (codec, resends, ...). */
    fun stats(): String = ""
}

class SinkException(val error: SinkError, message: String, cause: Throwable? = null) : Exception(message, cause)
