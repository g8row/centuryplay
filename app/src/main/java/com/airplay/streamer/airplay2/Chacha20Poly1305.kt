package com.airplay.streamer.airplay2

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ChaCha20-Poly1305 layer with counter-based nonces, matching pyatv's
 * `support/chacha20.py` (both Chacha20Cipher with nonce_length=8 and
 * Chacha20Cipher8byteNonce produce the same 12-byte nonce: four zero bytes
 * followed by an 8-byte little-endian counter).
 *
 * Separate counters are maintained for the outgoing and incoming directions.
 * AAD and explicit nonces are supported for cases where a fixed nonce is needed
 * (e.g. HAP frame length as AAD, or audio RTP header bytes as AAD).
 *
 * Uses the platform "ChaCha20-Poly1305" cipher (Android API 28+ / JDK 11+).
 */
class Chacha20Poly1305(outKey: ByteArray, inKey: ByteArray) {

    private val outKeySpec = SecretKeySpec(outKey, "ChaCha20")
    private val inKeySpec = SecretKeySpec(inKey, "ChaCha20")

    private var outCounter = 0L
    private var inCounter = 0L

    /** The nonce that the next [encrypt] call will use if none is supplied. */
    fun outNonce(): ByteArray = counterNonce(outCounter)

    /** The nonce that the next [decrypt] call will use if none is supplied. */
    fun inNonce(): ByteArray = counterNonce(inCounter)

    fun encrypt(data: ByteArray, nonce: ByteArray? = null, aad: ByteArray? = null): ByteArray {
        val n = if (nonce == null) {
            counterNonce(outCounter).also { outCounter++ }
        } else {
            padNonce(nonce)
        }
        return run(Cipher.ENCRYPT_MODE, outKeySpec, n, data, aad)
    }

    fun decrypt(data: ByteArray, nonce: ByteArray? = null, aad: ByteArray? = null): ByteArray {
        val n = if (nonce == null) {
            counterNonce(inCounter).also { inCounter++ }
        } else {
            padNonce(nonce)
        }
        return run(Cipher.DECRYPT_MODE, inKeySpec, n, data, aad)
    }

    private fun run(
        mode: Int,
        key: SecretKeySpec,
        nonce: ByteArray,
        data: ByteArray,
        aad: ByteArray?
    ): ByteArray {
        val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        cipher.init(mode, key, IvParameterSpec(nonce))
        if (aad != null) cipher.updateAAD(aad)
        return cipher.doFinal(data)
    }

    companion object {
        private const val NONCE_LENGTH = 12

        /** 12-byte nonce: four zero bytes + 8-byte little-endian counter. */
        fun counterNonce(counter: Long): ByteArray {
            val nonce = ByteArray(NONCE_LENGTH)
            var c = counter
            for (i in 0 until 8) {
                nonce[4 + i] = (c and 0xFF).toByte()
                c = c ushr 8
            }
            return nonce
        }

        /** Left-pad a short explicit nonce to 12 bytes with leading zeros. */
        fun padNonce(nonce: ByteArray): ByteArray {
            if (nonce.size >= NONCE_LENGTH) return nonce
            val padded = ByteArray(NONCE_LENGTH)
            System.arraycopy(nonce, 0, padded, NONCE_LENGTH - nonce.size, nonce.size)
            return padded
        }
    }
}
