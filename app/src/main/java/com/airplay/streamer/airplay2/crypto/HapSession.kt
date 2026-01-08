package com.airplay.streamer.airplay2.crypto

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * HAP (HomeKit Accessory Protocol) Session Encryption
 * 
 * Encrypts/decrypts RTSP traffic in 1024-byte frames after pair-setup completes.
 * Each frame is: [Length (2 bytes LE)] [Encrypted Data + Tag (16 bytes)]
 */
class HapSession {
    companion object {
        const val FRAME_LENGTH = 1024
        const val AUTH_TAG_LENGTH = 16
    }
    
    private var cipher: Chacha20Cipher? = null
    private var encryptedBuffer = ByteArray(0)
    
    val isEnabled: Boolean
        get() = cipher != null
    
    /**
     * Enable encryption with derived Control keys
     */
    fun enable(outputKey: ByteArray, inputKey: ByteArray) {
        cipher = Chacha20Cipher(outputKey, inputKey)
    }
    
    /**
     * Encrypt outgoing data in 1024-byte frames
     * 
     * Format: [Length (2 bytes LE)] [Encrypted frame + Tag]
     */
    fun encrypt(data: ByteArray): ByteArray {
        val c = cipher ?: return data
        
        val result = mutableListOf<Byte>()
        var offset = 0
        
        while (offset < data.size) {
            val frameSize = minOf(FRAME_LENGTH, data.size - offset)
            val frame = data.copyOfRange(offset, offset + frameSize)
            offset += frameSize
            
            // Length prefix (2 bytes, little-endian)
            val length = ByteBuffer.allocate(2)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort(frameSize.toShort())
                .array()
            
            // Encrypt frame with length as AAD
            val encrypted = c.encrypt(frame, aad = length)
            
            result.addAll(length.toList())
            result.addAll(encrypted.toList())
        }
        
        return result.toByteArray()
    }
    
    /**
     * Decrypt incoming data from 1024-byte frames
     * 
     * Handles partial reads by buffering data.
     */
    fun decrypt(data: ByteArray): ByteArray {
        val c = cipher ?: return data
        
        encryptedBuffer += data
        val result = mutableListOf<Byte>()
        
        while (encryptedBuffer.size >= 2) {
            // Get length from first 2 bytes
            val lengthBytes = encryptedBuffer.copyOfRange(0, 2)
            val blockLength = ByteBuffer.wrap(lengthBytes)
                .order(ByteOrder.LITTLE_ENDIAN)
                .short.toInt() and 0xFFFF
            
            val totalBlockSize = blockLength + AUTH_TAG_LENGTH
            
            if (encryptedBuffer.size < 2 + totalBlockSize) {
                break // Not enough data yet
            }
            
            // Extract and decrypt block
            val block = encryptedBuffer.copyOfRange(2, 2 + totalBlockSize)
            val decrypted = c.decrypt(block, aad = lengthBytes)
            result.addAll(decrypted.toList())
            
            encryptedBuffer = encryptedBuffer.copyOfRange(2 + totalBlockSize, encryptedBuffer.size)
        }
        
        return result.toByteArray()
    }
    
    /**
     * Clear any buffered encrypted data
     */
    fun clearBuffer() {
        encryptedBuffer = ByteArray(0)
    }
}
