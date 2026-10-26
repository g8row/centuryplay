package com.airplay.streamer.airplay2

import org.junit.Assert.*
import org.junit.Test

class AirPlay2SyncTimingTest {
    @Test fun supportedLatenciesHaveDistinctFramesAndCorrectPlayTime() {
        val now = 10_000_000L
        for (ms in listOf(150L, 200L, 250L, 500L, 2000L, 4000L)) {
            val delay = ms * 44100 / 1000
            val (first, second) = AirPlay2SyncTiming.referenceFrames(now, delay)
            assertNotEquals(first, second)
            assertEquals(11025L, second - first)
            assertEquals(now - delay, first - AirPlay2SyncTiming.EXTRA_FRAMES)
        }
    }
    @Test fun speakerOffsetMovesBothReferencesTogether() {
        val (a,b) = AirPlay2SyncTiming.referenceFrames(100000L, 8820L)
        val (c,d) = AirPlay2SyncTiming.referenceFrames(95590L, 8820L)
        assertEquals(4410L, a-c)
        assertEquals(4410L, b-d)
    }
}
