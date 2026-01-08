package com.airplay.streamer.raop

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.crypto.params.KeyParameter
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Cryptographic utilities for AirPlay 2 authentication and encryption.
 * 
 * AirPlay 2 uses:
 * - Ed25519 for digital signatures (pair-setup/verify)
 * - X25519 (Curve25519) for key exchange
 * - ChaCha20-Poly1305 for authenticated encryption
 * - HKDF for key derivation
 */
object AirPlay2Crypto {

    private val random = SecureRandom()

    // ==================== Ed25519 ====================

    /**
     * Generate Ed25519 key pair for signing
     */
    fun generateEd25519KeyPair(): Pair<ByteArray, ByteArray> {
        val generator = Ed25519KeyPairGenerator()
        generator.init(Ed25519KeyGenerationParameters(random))
        val keyPair = generator.generateKeyPair()
        
        val privateKey = (keyPair.private as Ed25519PrivateKeyParameters).encoded
        val publicKey = (keyPair.public as Ed25519PublicKeyParameters).encoded
        
        return Pair(privateKey, publicKey)
    }

    /**
     * Sign data with Ed25519 private key
     */
    fun ed25519Sign(privateKey: ByteArray, message: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(privateKey, 0))
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    /**
     * Verify Ed25519 signature
     */
    fun ed25519Verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        val verifier = Ed25519Signer()
        verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
        verifier.update(message, 0, message.size)
        return verifier.verifySignature(signature)
    }

    // ==================== X25519 (Curve25519) ====================

    /**
     * Generate X25519 key pair for key exchange
     */
    fun generateX25519KeyPair(): Pair<ByteArray, ByteArray> {
        val generator = X25519KeyPairGenerator()
        generator.init(X25519KeyGenerationParameters(random))
        val keyPair = generator.generateKeyPair()
        
        val privateKey = (keyPair.private as X25519PrivateKeyParameters).encoded
        val publicKey = (keyPair.public as X25519PublicKeyParameters).encoded
        
        return Pair(privateKey, publicKey)
    }

    /**
     * Perform X25519 key agreement to derive shared secret
     */
    fun x25519SharedSecret(privateKey: ByteArray, peerPublicKey: ByteArray): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(X25519PrivateKeyParameters(privateKey, 0))
        
        val sharedSecret = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(X25519PublicKeyParameters(peerPublicKey, 0), sharedSecret, 0)
        
        return sharedSecret
    }

    // ==================== HKDF (RFC 5869) ====================

    /**
     * HKDF-Extract: Extract a pseudorandom key from input keying material
     */
    fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        val saltKey = if (salt.isEmpty()) ByteArray(64) else salt
        mac.init(SecretKeySpec(saltKey, "HmacSHA512"))
        return mac.doFinal(ikm)
    }

    /**
     * HKDF-Expand: Expand the pseudorandom key to desired length
     */
    fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(prk, "HmacSHA512"))
        
        val hashLen = 64 // SHA-512 output
        val n = (length + hashLen - 1) / hashLen
        
        val result = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0
        
        for (i in 1..n) {
            mac.reset()
            mac.update(t)
            mac.update(info)
            mac.update(i.toByte())
            t = mac.doFinal()
            
            val copyLen = minOf(hashLen, length - offset)
            System.arraycopy(t, 0, result, offset, copyLen)
            offset += copyLen
        }
        
        return result
    }

    /**
     * Full HKDF: Extract + Expand
     */
    fun hkdf(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hkdfExtract(salt, ikm)
        return hkdfExpand(prk, info, length)
    }
    
    /**
     * HKDF with string salt and info (convenience for Control-Salt, etc.)
     * Matches Python: hkdf_expand(salt_str, info_str, shared_secret) -> 32 bytes
     */
    fun hkdfExpand(salt: String, info: String, sharedSecret: ByteArray): ByteArray {
        return hkdf(salt.toByteArray(Charsets.UTF_8), sharedSecret, info.toByteArray(Charsets.UTF_8), 32)
    }

    // ==================== ChaCha20-Poly1305 ====================

    /**
     * Encrypt with ChaCha20-Poly1305 AEAD
     * Returns: nonce (12 bytes) + ciphertext + tag (16 bytes)
     */
    fun chaCha20Poly1305Encrypt(key: ByteArray, plaintext: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        require(key.size == 32) { "Key must be 32 bytes" }
        
        // Generate random 12-byte nonce
        val nonce = ByteArray(12)
        random.nextBytes(nonce)
        
        val cipher = ChaCha20Poly1305()
        val params = AEADParameters(KeyParameter(key), 128, nonce, aad)
        cipher.init(true, params)
        
        val output = ByteArray(cipher.getOutputSize(plaintext.size))
        var len = cipher.processBytes(plaintext, 0, plaintext.size, output, 0)
        len += cipher.doFinal(output, len)
        
        // Return: nonce + ciphertext + tag
        return nonce + output.copyOf(len)
    }

    /**
     * Decrypt with ChaCha20-Poly1305 AEAD
     * Input: nonce (12 bytes) + ciphertext + tag (16 bytes)
     */
    fun chaCha20Poly1305Decrypt(key: ByteArray, encrypted: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray? {
        require(key.size == 32) { "Key must be 32 bytes" }
        require(encrypted.size >= 28) { "Encrypted data too short" }
        
        val nonce = encrypted.copyOfRange(0, 12)
        val ciphertextWithTag = encrypted.copyOfRange(12, encrypted.size)
        
        return try {
            val cipher = ChaCha20Poly1305()
            val params = AEADParameters(KeyParameter(key), 128, nonce, aad)
            cipher.init(false, params)
            
            val output = ByteArray(cipher.getOutputSize(ciphertextWithTag.size))
            var len = cipher.processBytes(ciphertextWithTag, 0, ciphertextWithTag.size, output, 0)
            len += cipher.doFinal(output, len)
            
            output.copyOf(len)
        } catch (e: Exception) {
            null // Authentication failed
        }
    }

    /**
     * Encrypt with ChaCha20-Poly1305 using a specific nonce
     * Returns: ciphertext + tag (16 bytes) - no nonce prepended
     */
    fun chaCha20Poly1305EncryptWithNonce(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        require(key.size == 32) { "Key must be 32 bytes" }
        require(nonce.size == 12) { "Nonce must be 12 bytes" }
        
        val cipher = ChaCha20Poly1305()
        val params = AEADParameters(KeyParameter(key), 128, nonce, aad)
        cipher.init(true, params)
        
        val output = ByteArray(cipher.getOutputSize(plaintext.size))
        var len = cipher.processBytes(plaintext, 0, plaintext.size, output, 0)
        len += cipher.doFinal(output, len)
        
        return output.copyOf(len)
    }

    /**
     * Decrypt with ChaCha20-Poly1305 using a specific nonce
     * Input: ciphertext + tag (16 bytes) - no nonce prepended
     */
    fun chaCha20Poly1305DecryptWithNonce(key: ByteArray, nonce: ByteArray, encrypted: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray? {
        require(key.size == 32) { "Key must be 32 bytes" }
        require(nonce.size == 12) { "Nonce must be 12 bytes" }
        require(encrypted.size >= 16) { "Encrypted data too short" }
        
        return try {
            val cipher = ChaCha20Poly1305()
            val params = AEADParameters(KeyParameter(key), 128, nonce, aad)
            cipher.init(false, params)
            
            val output = ByteArray(cipher.getOutputSize(encrypted.size))
            var len = cipher.processBytes(encrypted, 0, encrypted.size, output, 0)
            len += cipher.doFinal(output, len)
            
            output.copyOf(len)
        } catch (e: Exception) {
            null // Authentication failed
        }
    }

    // ==================== SHA-512 ====================

    fun sha512(data: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-512").digest(data)
    }

    // ==================== Utilities ====================

    fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}
