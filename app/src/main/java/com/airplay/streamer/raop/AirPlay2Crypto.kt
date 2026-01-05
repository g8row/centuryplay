package com.airplay.streamer.raop

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.crypto.engines.ChaCha7539Engine
import org.bouncycastle.crypto.macs.Poly1305
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
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
        
        // ChaCha20 encryption
        val chacha = ChaCha7539Engine()
        chacha.init(true, ParametersWithIV(KeyParameter(key), nonce))
        
        val ciphertext = ByteArray(plaintext.size)
        chacha.processBytes(plaintext, 0, plaintext.size, ciphertext, 0)
        
        // Poly1305 MAC
        val poly1305Key = ByteArray(32)
        val zeros = ByteArray(32)
        val polyEngine = ChaCha7539Engine()
        polyEngine.init(true, ParametersWithIV(KeyParameter(key), nonce))
        polyEngine.processBytes(zeros, 0, 32, poly1305Key, 0)
        
        val poly = Poly1305()
        poly.init(KeyParameter(poly1305Key))
        
        // AAD with padding
        poly.update(aad, 0, aad.size)
        if (aad.size % 16 != 0) {
            val padding = ByteArray(16 - (aad.size % 16))
            poly.update(padding, 0, padding.size)
        }
        
        // Ciphertext with padding
        poly.update(ciphertext, 0, ciphertext.size)
        if (ciphertext.size % 16 != 0) {
            val padding = ByteArray(16 - (ciphertext.size % 16))
            poly.update(padding, 0, padding.size)
        }
        
        // Lengths (little-endian)
        val lengths = ByteArray(16)
        putLittleEndianLong(lengths, 0, aad.size.toLong())
        putLittleEndianLong(lengths, 8, ciphertext.size.toLong())
        poly.update(lengths, 0, 16)
        
        val tag = ByteArray(16)
        poly.doFinal(tag, 0)
        
        // Return: nonce + ciphertext + tag
        return nonce + ciphertext + tag
    }

    /**
     * Decrypt with ChaCha20-Poly1305 AEAD
     * Input: nonce (12 bytes) + ciphertext + tag (16 bytes)
     */
    fun chaCha20Poly1305Decrypt(key: ByteArray, encrypted: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray? {
        require(key.size == 32) { "Key must be 32 bytes" }
        require(encrypted.size >= 28) { "Encrypted data too short" }
        
        val nonce = encrypted.copyOfRange(0, 12)
        val tag = encrypted.copyOfRange(encrypted.size - 16, encrypted.size)
        val ciphertext = encrypted.copyOfRange(12, encrypted.size - 16)
        
        // Verify Poly1305 MAC
        val poly1305Key = ByteArray(32)
        val zeros = ByteArray(32)
        val polyEngine = ChaCha7539Engine()
        polyEngine.init(true, ParametersWithIV(KeyParameter(key), nonce))
        polyEngine.processBytes(zeros, 0, 32, poly1305Key, 0)
        
        val poly = Poly1305()
        poly.init(KeyParameter(poly1305Key))
        
        poly.update(aad, 0, aad.size)
        if (aad.size % 16 != 0) {
            val padding = ByteArray(16 - (aad.size % 16))
            poly.update(padding, 0, padding.size)
        }
        
        poly.update(ciphertext, 0, ciphertext.size)
        if (ciphertext.size % 16 != 0) {
            val padding = ByteArray(16 - (ciphertext.size % 16))
            poly.update(padding, 0, padding.size)
        }
        
        val lengths = ByteArray(16)
        putLittleEndianLong(lengths, 0, aad.size.toLong())
        putLittleEndianLong(lengths, 8, ciphertext.size.toLong())
        poly.update(lengths, 0, 16)
        
        val expectedTag = ByteArray(16)
        poly.doFinal(expectedTag, 0)
        
        if (!expectedTag.contentEquals(tag)) {
            return null // Authentication failed
        }
        
        // Decrypt with ChaCha20
        val chacha = ChaCha7539Engine()
        chacha.init(false, ParametersWithIV(KeyParameter(key), nonce))
        
        val plaintext = ByteArray(ciphertext.size)
        chacha.processBytes(ciphertext, 0, ciphertext.size, plaintext, 0)
        
        return plaintext
    }

    private fun putLittleEndianLong(bytes: ByteArray, offset: Int, value: Long) {
        for (i in 0..7) {
            bytes[offset + i] = (value shr (8 * i)).toByte()
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
