package com.airplay.streamer.raop

import com.airplay.streamer.util.LogServer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * HAP Session Encryption for AirPlay 2 control channel.
 * 
 * Implements the HomeKit Accessory Protocol frame encryption:
 * - Data is encrypted/decrypted in blocks of up to 1024 bytes
 * - Each frame: [length:2 LE][encrypted_data + tag:16]
 * - Length prefix is used as AAD for authentication
 * - Counter-based 8-byte nonces, front-padded to 12 bytes
 */
class HapSession {
    
    companion object {
        private const val FRAME_LENGTH = 1024
        private const val AUTH_TAG_LENGTH = 16
    }
    
    private var cipher: Chacha20Cipher? = null
    private var encryptedBuffer = ByteArray(0)
    
    val isEnabled: Boolean
        get() = cipher != null
    
    /**
     * Enable encryption with output (write) and input (read) keys.
     * Keys are derived using HKDF from the SRP session key K.
     */
    fun enable(outputKey: ByteArray, inputKey: ByteArray) {
        require(outputKey.size == 32) { "Output key must be 32 bytes" }
        require(inputKey.size == 32) { "Input key must be 32 bytes" }
        cipher = Chacha20Cipher(outputKey, inputKey)
        LogServer.log("HapSession: Encryption enabled")
    }
    
    /**
     * Encrypt outgoing data in 1024-byte frames.
     * Returns: [length:2 LE][ciphertext + tag:16] for each frame
     */
    fun encrypt(data: ByteArray): ByteArray {
        val c = cipher ?: return data
        
        var output = ByteArray(0)
        var remaining = data
        
        while (remaining.isNotEmpty()) {
            val frameSize = minOf(FRAME_LENGTH, remaining.size)
            val frame = remaining.sliceArray(0 until frameSize)
            remaining = remaining.sliceArray(frameSize until remaining.size)
            
            // Length prefix (2 bytes, little-endian) is used as AAD
            val lengthBytes = ByteBuffer.allocate(2)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort(frameSize.toShort())
                .array()
            
            // Encrypt with length as AAD
            val encrypted = c.encrypt(frame, aad = lengthBytes)
            
            output += lengthBytes + encrypted
        }
        
        return output
    }
    
    /**
     * Decrypt incoming data from 1024-byte frames.
     * Handles partial reads - buffers data until complete frames are available.
     */
    fun decrypt(data: ByteArray): ByteArray {
        val c = cipher ?: return data
        
        encryptedBuffer += data
        
        var output = ByteArray(0)
        
        while (encryptedBuffer.size >= 2) {
            // Get length from first 2 bytes (little-endian)
            val lengthBytes = encryptedBuffer.sliceArray(0 until 2)
            val blockLength = ByteBuffer.wrap(lengthBytes)
                .order(ByteOrder.LITTLE_ENDIAN)
                .short.toInt() and 0xFFFF
            
            val totalLength = blockLength + AUTH_TAG_LENGTH
            
            // Check if we have enough data
            if (encryptedBuffer.size < 2 + totalLength) {
                break
            }
            
            // Extract and decrypt block
            val block = encryptedBuffer.sliceArray(2 until 2 + totalLength)
            val decrypted = c.decrypt(block, aad = lengthBytes)
            
            if (decrypted != null) {
                output += decrypted
            } else {
                LogServer.log("HapSession: Decryption failed!")
                break
            }
            
            // Remove processed data from buffer
            encryptedBuffer = encryptedBuffer.sliceArray(2 + totalLength until encryptedBuffer.size)
        }
        
        return output
    }
}

/**
 * ChaCha20-Poly1305 cipher with counter-based nonces.
 * 
 * Used for both HAP session encryption (control channel) and can be
 * used with explicit nonces for audio encryption.
 */
class Chacha20Cipher(
    private val outputKey: ByteArray,
    private val inputKey: ByteArray,
    private val nonceLength: Int = 8
) {
    private var outCounter = 0L
    private var inCounter = 0L
    
    /**
     * Encrypt data with auto-incrementing counter nonce.
     * Returns: ciphertext + tag (16 bytes)
     */
    fun encrypt(data: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        // Generate nonce from counter (little-endian)
        val nonce8 = ByteBuffer.allocate(8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(outCounter)
            .array()
        outCounter++
        
        // Pad to 12 bytes (front-pad with zeros)
        val nonce12 = ByteArray(4) + nonce8
        
        return AirPlay2Crypto.chaCha20Poly1305EncryptWithNonce(outputKey, nonce12, data, aad)
    }
    
    /**
     * Decrypt data with auto-incrementing counter nonce.
     * Input: ciphertext + tag (16 bytes)
     * Returns: plaintext or null if authentication fails
     */
    fun decrypt(data: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray? {
        // Generate nonce from counter (little-endian)
        val nonce8 = ByteBuffer.allocate(8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(inCounter)
            .array()
        inCounter++
        
        // Pad to 12 bytes (front-pad with zeros)
        val nonce12 = ByteArray(4) + nonce8
        
        // Use inputKey for decryption!
        return AirPlay2Crypto.chaCha20Poly1305DecryptWithNonce(inputKey, nonce12, data, aad)
    }
}
