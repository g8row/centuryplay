package com.airplay.streamer.airplay2.crypto

import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters

/**
 * HKDF (HMAC-based Key Derivation Function) using SHA-512
 * 
 * Used to derive encryption keys from the SRP session key.
 */
object Hkdf {
    
    /**
     * Derive keys using HKDF-SHA512
     */
    fun deriveKey(salt: String, info: String, ikm: ByteArray, length: Int = 32): ByteArray {
        return deriveKey(salt.toByteArray(Charsets.UTF_8), info.toByteArray(Charsets.UTF_8), ikm, length)
    }
    
    /**
     * Derive a key of specified length using HKDF-SHA512
     */
    fun deriveKey(salt: ByteArray, info: ByteArray, ikm: ByteArray, length: Int): ByteArray {
        val hkdf = HKDFBytesGenerator(SHA512Digest())
        hkdf.init(HKDFParameters(ikm, salt, info))
        
        val output = ByteArray(length)
        hkdf.generateBytes(output, 0, length)
        return output
    }
    
    /**
     * Derive Control channel encryption keys for AirPlay 2
     */
    object Control {
        private const val SALT = "Control-Salt"
        private const val WRITE_KEY_INFO = "Control-Write-Encryption-Key"
        private const val READ_KEY_INFO = "Control-Read-Encryption-Key"
        
        /**
         * Derive output (write) key for sending encrypted data
         */
        fun deriveOutputKey(sessionKey: ByteArray): ByteArray {
            return deriveKey(SALT, WRITE_KEY_INFO, sessionKey)
        }
        
        /**
         * Derive input (read) key for receiving encrypted data
         */
        fun deriveInputKey(sessionKey: ByteArray): ByteArray {
            return deriveKey(SALT, READ_KEY_INFO, sessionKey)
        }
    }
}
