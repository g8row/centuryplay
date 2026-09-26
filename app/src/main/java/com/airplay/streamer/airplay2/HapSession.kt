package com.airplay.streamer.airplay2

import java.io.ByteArrayOutputStream

/**
 * HAP session encryption framing, matching pyatv's `auth/hap_session.py`.
 *
 * Outgoing data is split into frames of at most [FRAME_LENGTH] bytes. Each frame
 * is prefixed with a 2-byte little-endian length and encrypted with
 * ChaCha20-Poly1305 using that length as additional authenticated data. The
 * 16-byte Poly1305 tag is appended by the cipher.
 *
 * Incoming data is buffered until a full frame (length prefix + ciphertext + tag)
 * is available, then decrypted.
 */
class HapSession(outKey: ByteArray, inKey: ByteArray) {

    private val cipher = Chacha20Poly1305(outKey, inKey)
    private var incoming = ByteArray(0)

    fun encrypt(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var offset = 0
        while (offset < data.size) {
            val frameLen = minOf(FRAME_LENGTH, data.size - offset)
            val frame = data.copyOfRange(offset, offset + frameLen)
            val lengthBytes = byteArrayOf((frameLen and 0xFF).toByte(), ((frameLen shr 8) and 0xFF).toByte())
            val encrypted = cipher.encrypt(frame, aad = lengthBytes)
            out.write(lengthBytes)
            out.write(encrypted)
            offset += frameLen
        }
        return out.toByteArray()
    }

    /**
     * Feed received bytes and return whatever plaintext can be fully decrypted.
     * Partial frames are retained internally until completed.
     */
    fun decrypt(data: ByteArray): ByteArray {
        incoming += data
        val out = ByteArrayOutputStream()
        while (incoming.size >= 2) {
            val length = (incoming[0].toInt() and 0xFF) or ((incoming[1].toInt() and 0xFF) shl 8)
            val blockLength = length + AUTH_TAG_LENGTH
            if (incoming.size < blockLength + 2) break

            val lengthBytes = incoming.copyOfRange(0, 2)
            val block = incoming.copyOfRange(2, 2 + blockLength)
            out.write(cipher.decrypt(block, aad = lengthBytes))
            incoming = incoming.copyOfRange(2 + blockLength, incoming.size)
        }
        return out.toByteArray()
    }

    companion object {
        const val FRAME_LENGTH = 1024 // HAP spec section 5.2.2
        const val AUTH_TAG_LENGTH = 16
    }
}
