package com.airplay.streamer.service

/** Pure volume-key policy for MediaProjection capture on high-resolution Android TVs. */
internal object VolumeKeyPolicy {
    const val DUPLICATE_WINDOW_MS = 150L
    fun parkedIndex(maxIndex: Int): Int = if (maxIndex >= 50) 1 else 0
    fun stepFraction(maxIndex: Int): Float = maxOf(1f / maxIndex.coerceAtLeast(1), 0.05f)
    fun isDuplicate(direction: Int, previous: Int, nowMs: Long, previousMs: Long) =
        direction == previous && nowMs >= previousMs && nowMs - previousMs < DUPLICATE_WINDOW_MS
}
