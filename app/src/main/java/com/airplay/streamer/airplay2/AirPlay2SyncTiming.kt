package com.airplay.streamer.airplay2

/** Stable AirPlay 2 0xD4 sync fields, avoiding identical timestamps at 250 ms latency. */
internal object AirPlay2SyncTiming {
    const val EXTRA_FRAMES = 11025L // 250 ms at 44.1 kHz
    fun referenceFrames(currentFrame: Long, desiredLatencyFrames: Long): Pair<Long, Long> {
        val first = currentFrame - desiredLatencyFrames + EXTRA_FRAMES
        return first to (first + EXTRA_FRAMES)
    }
}
