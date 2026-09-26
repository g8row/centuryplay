package com.airplay.streamer.airplay2

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Chacha20Poly1305Test {

    @Test
    fun counterNonce_layout() {
        // 12-byte nonce: 4 zero bytes + 8-byte little-endian counter
        assertArrayEquals(ByteArray(12), Chacha20Poly1305.counterNonce(0))

        val one = Chacha20Poly1305.counterNonce(1)
        val expected = ByteArray(12).also { it[4] = 1 }
        assertArrayEquals(expected, one)

        val n = Chacha20Poly1305.counterNonce(0x0102L)
        assertEquals(0x02.toByte(), n[4])
        assertEquals(0x01.toByte(), n[5])
    }

    @Test
    fun encryptDecrypt_roundTrip_withAad() {
        val k1 = ByteArray(32) { it.toByte() }
        val k2 = ByteArray(32) { (it + 100).toByte() }
        val enc = Chacha20Poly1305(k1, k2)
        val dec = Chacha20Poly1305(k2, k1) // decryptor in-key == encryptor out-key

        val data = "hello airplay 2".toByteArray()
        val aad = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)

        val ciphertext = enc.encrypt(data, aad = aad)
        val plaintext = dec.decrypt(ciphertext, aad = aad)
        assertArrayEquals(data, plaintext)
    }

    @Test
    fun multiplePackets_counterAdvances() {
        val k1 = ByteArray(32) { 7 }
        val k2 = ByteArray(32) { 9 }
        val enc = Chacha20Poly1305(k1, k2)
        val dec = Chacha20Poly1305(k2, k1)

        repeat(5) { i ->
            val msg = "packet-$i".toByteArray()
            val ct = enc.encrypt(msg)
            val pt = dec.decrypt(ct)
            assertArrayEquals(msg, pt)
        }
    }
}
