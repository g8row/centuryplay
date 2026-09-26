package com.airplay.streamer.airplay2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlay2CapabilitiesTest {

    @Test
    fun parseFeatures_combinesHighAndLow() {
        // high holds bits 32-63
        assertEquals(0x10040L shl 32, AirPlay2Capabilities.parseFeatures("0x0,0x10040"))
        assertEquals(0x80000L, AirPlay2Capabilities.parseFeatures("0x80000"))
    }

    @Test
    fun sonosLikeFlags_routeToV2Transient() {
        // bit 48 (CoreUtilsPairingAndEncryption) -> high bit 16 = 0x10000
        // bit 38 (UnifiedMediaControl)          -> high bit 6  = 0x40
        val txt = mapOf("ft" to "0x0,0x10040")
        assertTrue(AirPlay2Capabilities.isAirPlay2(txt))
        assertTrue(AirPlay2Capabilities.needsTransient(txt))
    }

    @Test
    fun v1OnlyFlags_doNotRouteToV2() {
        val txt = mapOf("ft" to "0x80000") // bit 19, no v2 bits
        assertFalse(AirPlay2Capabilities.isAirPlay2(txt))
        assertFalse(AirPlay2Capabilities.needsTransient(txt))
    }

    @Test
    fun missingFeatures_isNotV2() {
        assertFalse(AirPlay2Capabilities.isAirPlay2(emptyMap()))
        assertFalse(AirPlay2Capabilities.needsTransient(mapOf("et" to "0,1")))
    }
}
