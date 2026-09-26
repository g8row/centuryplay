package com.airplay.streamer.airplay2

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class HapSessionTest {

    @Test
    fun roundTrip_singleFrame() {
        val k1 = ByteArray(32) { it.toByte() }
        val k2 = ByteArray(32) { (255 - it).toByte() }
        val sender = HapSession(k1, k2)
        val receiver = HapSession(k2, k1)

        val data = "RTSP/1.0 200 OK\r\n\r\n".toByteArray()
        val encrypted = sender.encrypt(data)
        assertArrayEquals(data, receiver.decrypt(encrypted))
    }

    @Test
    fun roundTrip_multiFrame() {
        val k1 = ByteArray(32) { 3 }
        val k2 = ByteArray(32) { 5 }
        val sender = HapSession(k1, k2)
        val receiver = HapSession(k2, k1)

        val data = ByteArray(2600) { (it % 251).toByte() } // > 2 frames of 1024
        val encrypted = sender.encrypt(data)
        assertArrayEquals(data, receiver.decrypt(encrypted))
    }

    @Test
    fun decrypt_handlesPartialChunks() {
        val k1 = ByteArray(32) { 11 }
        val k2 = ByteArray(32) { 22 }
        val sender = HapSession(k1, k2)
        val receiver = HapSession(k2, k1)

        val data = ByteArray(1500) { (it % 97).toByte() }
        val encrypted = sender.encrypt(data)

        // Feed the encrypted stream in arbitrary small slices
        val out = ArrayList<Byte>()
        var i = 0
        while (i < encrypted.size) {
            val end = minOf(i + 13, encrypted.size)
            val piece = encrypted.copyOfRange(i, end)
            out.addAll(receiver.decrypt(piece).toList())
            i = end
        }
        assertArrayEquals(data, out.toByteArray())
    }
}
