package com.airplay.streamer.airplay2

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BinaryPlistTest {

    @Test
    fun encodeDecode_roundTrip() {
        val shk = ByteArray(32) { it.toByte() }
        val body = mapOf(
            "eventPort" to 1234,
            "streams" to listOf(
                mapOf(
                    "audioFormat" to 0x800,
                    "ct" to 1,
                    "shk" to shk,
                    "dataPort" to 55555,
                    "controlPort" to 6789,
                )
            )
        )

        val encoded = BinaryPlist.encode(body)
        // bplist00 magic
        assertEquals("bplist00", String(encoded.copyOfRange(0, 8), Charsets.US_ASCII))

        val dict = BinaryPlist.decode(encoded)
        assertEquals(1234, BinaryPlist.int(dict, "eventPort"))

        val stream = BinaryPlist.firstStream(dict)!!
        assertEquals(0x800, BinaryPlist.int(stream, "audioFormat"))
        assertEquals(55555, BinaryPlist.int(stream, "dataPort"))
        assertEquals(6789, BinaryPlist.int(stream, "controlPort"))
        assertArrayEquals(shk, BinaryPlist.data(stream, "shk"))
    }
}
