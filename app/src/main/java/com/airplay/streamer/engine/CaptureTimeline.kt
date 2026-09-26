package com.airplay.streamer.engine

/**
 * Maps captured frame indices to the moment they were captured, on the [NtpClock]
 * timeline (Unix-epoch nanoseconds).
 *
 * The capture clock (the audio HAL) and the phone's system clock drift apart by tens of
 * ppm. Sync packets must follow the *capture* clock, otherwise the receiver's buffer
 * slowly drains or overflows. Each read gives an observation `now - frames/rate`, which
 * is the true offset plus positive scheduling jitter; we follow its lower envelope
 * (drop immediately to a lower value, creep up slowly) so jitter doesn't leak into the
 * timeline but real drift in either direction is tracked.
 */
class CaptureTimeline(val sampleRate: Int = 44100) {
    @Volatile private var offsetNanos = Double.NaN

    val isStarted: Boolean get() = !offsetNanos.isNaN()

    /** Record that [totalFrames] frames had been captured when a read returned at [nowNanos]. */
    fun onFramesCaptured(totalFrames: Long, nowNanos: Long) {
        val observed = nowNanos - totalFrames * 1e9 / sampleRate
        val current = offsetNanos
        offsetNanos = when {
            current.isNaN() -> observed
            observed < current -> observed
            else -> current + (observed - current) * RISE_ALPHA
        }
    }

    /** Gap (in frames) between where the timeline expects [totalFrames] to be and [nowNanos]. */
    fun lagFrames(totalFrames: Long, nowNanos: Long): Long {
        val current = offsetNanos
        if (current.isNaN()) return 0
        val expected = current + totalFrames * 1e9 / sampleRate
        return ((nowNanos - expected) * sampleRate / 1e9).toLong()
    }

    fun captureNanosOf(frame: Long): Long = (offsetNanos + frame * 1e9 / sampleRate).toLong()

    /** Fractional frame index captured at [nanos]. */
    fun frameAt(nanos: Long): Long = ((nanos - offsetNanos) * sampleRate / 1e9).toLong()

    companion object {
        private const val RISE_ALPHA = 0.002
    }
}
