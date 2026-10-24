package com.airplay.streamer.service

import org.junit.Assert.*
import org.junit.Test

class VolumeKeyPolicyTest {
    @Test fun tvDownCanRegisterAndPhonesRemainSilent() {
        assertEquals(1, VolumeKeyPolicy.parkedIndex(100))
        assertEquals(1, VolumeKeyPolicy.parkedIndex(50))
        assertEquals(0, VolumeKeyPolicy.parkedIndex(15))
    }
    @Test fun tvVolumeChangesAtLeastFivePercent() {
        assertEquals(0.05f, VolumeKeyPolicy.stepFraction(100), 0.0001f)
        assertEquals(0.05f, VolumeKeyPolicy.stepFraction(50), 0.0001f)
        assertEquals(1f / 15, VolumeKeyPolicy.stepFraction(15), 0.0001f)
    }
    @Test fun onlyRapidSameDirectionBroadcastsAreIgnored() {
        assertTrue(VolumeKeyPolicy.isDuplicate(1, 1, 1025, 1000))
        assertFalse(VolumeKeyPolicy.isDuplicate(-1, 1, 1025, 1000))
        assertFalse(VolumeKeyPolicy.isDuplicate(1, 1, 1150, 1000))
    }
}
