package com.airplay.streamer.airplay2.crypto

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.engines.ChaCha7539Engine
import org.bouncycastle.crypto.macs.Poly1305
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.airplay.streamer.util.LogServer

/**
 * ChaCha20-Poly1305 AEAD Cipher for AirPlay 2
 * 
 * Uses counter-based nonces (8-byte little-endian) for HAP/RTSP encryption.
 */
class Chacha20Cipher(
    private val encryptKey: ByteArray,
    private val decryptKey: ByteArray,
    private val nonceLength: Int = 8
) {
    init {
        LogServer.log("Chacha20Cipher: Init outKey=${encryptKey.toHex().take(16)}... inKey=${decryptKey.toHex().take(16)}...")
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private var encryptCounter: Long = 0
    private var decryptCounter: Long = 0
    
    /**
     * Pad nonce to 12 bytes (required by ChaCha20-Poly1305)
     */
    private fun padNonce(nonce: ByteArray): ByteArray {
        return if (nonce.size < 12) {
            ByteArray(12 - nonce.size) + nonce
        } else {
            nonce
        }
    }
    
    /**
     * Generate next nonce for encryption
     */
    private fun nextEncryptNonce(): ByteArray {
        val nonce = ByteBuffer.allocate(8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(encryptCounter++)
            .array()
        return padNonce(nonce)
    }
    
    /**
     * Generate next nonce for decryption
     */
    private fun nextDecryptNonce(): ByteArray {
        val nonce = ByteBuffer.allocate(8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(decryptCounter++)
            .array()
        return padNonce(nonce)
    }
    
    /**
     * Encrypt data with auto-incrementing counter nonce
     */
    fun encrypt(plaintext: ByteArray, aad: ByteArray? = null): ByteArray {
        return encrypt(plaintext, nextEncryptNonce(), aad)
    }
    
    /**
     * Encrypt data with specified nonce
     */
    fun encrypt(plaintext: ByteArray, nonce: ByteArray, aad: ByteArray? = null): ByteArray {
        val paddedNonce = if (nonce.size != 12) padNonce(nonce) else nonce
        LogServer.log("Chacha20Cipher: Encrypt ${plaintext.size} bytes. Nonce=${paddedNonce.toHex()} AAD=${aad?.toHex() ?: "null"}")
        return chacha20Poly1305Encrypt(encryptKey, paddedNonce, plaintext, aad ?: ByteArray(0))
    }
    
    /**
     * Decrypt data with auto-incrementing counter nonce
     */
    fun decrypt(ciphertext: ByteArray, aad: ByteArray? = null): ByteArray {
        return decrypt(ciphertext, nextDecryptNonce(), aad)
    }
    
    /**
     * Decrypt data with specified nonce
     */
    fun decrypt(ciphertext: ByteArray, nonce: ByteArray, aad: ByteArray? = null): ByteArray {
        val paddedNonce = if (nonce.size != 12) padNonce(nonce) else nonce
        return chacha20Poly1305Decrypt(decryptKey, paddedNonce, ciphertext, aad ?: ByteArray(0))
    }
    
    /**
     * ChaCha20-Poly1305 encryption using BouncyCastle
     */
    private fun chacha20Poly1305Encrypt(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray
    ): ByteArray {
        // Use BouncyCastle's ChaCha20-Poly1305 implementation
        val cipher = org.bouncycastle.crypto.modes.ChaCha20Poly1305()
        val params = org.bouncycastle.crypto.params.AEADParameters(
            KeyParameter(key),
            128, // Tag size in bits
            nonce,
            aad
        )
        cipher.init(true, params)
        
        val output = ByteArray(cipher.getOutputSize(plaintext.size))
        var len = cipher.processBytes(plaintext, 0, plaintext.size, output, 0)
        len += cipher.doFinal(output, len)
        
        return output.copyOf(len)
    }
    
    /**
     * ChaCha20-Poly1305 decryption using BouncyCastle
     */
    private fun chacha20Poly1305Decrypt(
        key: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        aad: ByteArray
    ): ByteArray {
        val cipher = org.bouncycastle.crypto.modes.ChaCha20Poly1305()
        val params = org.bouncycastle.crypto.params.AEADParameters(
            KeyParameter(key),
            128, // Tag size in bits
            nonce,
            aad
        )
        cipher.init(false, params)
        
        val output = ByteArray(cipher.getOutputSize(ciphertext.size))
        var len = cipher.processBytes(ciphertext, 0, ciphertext.size, output, 0)
        len += cipher.doFinal(output, len)
        
        return output.copyOf(len)
    }
}
