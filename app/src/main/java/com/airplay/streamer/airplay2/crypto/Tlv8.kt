package com.airplay.streamer.airplay2.crypto

/**
 * TLV8 Encoding/Decoding for HomeKit Accessory Protocol (HAP)
 * 
 * TLV8 format: Each entry is [Type (1 byte)] [Length (1 byte)] [Value (0-255 bytes)]
 * Values > 255 bytes are fragmented across multiple entries with same type.
 */
object Tlv8 {
    
    /**
     * TLV types used in AirPlay 2 pair-setup
     */
    object Type {
        const val METHOD = 0x00
        const val IDENTIFIER = 0x01
        const val SALT = 0x02
        const val PUBLIC_KEY = 0x03
        const val PROOF = 0x04
        const val ENCRYPTED_DATA = 0x05
        const val SEQ_NO = 0x06
        const val ERROR = 0x07
        const val SIGNATURE = 0x0A
        const val FLAGS = 0x13
    }
    
    /**
     * Flag values for transient pairing
     */
    object Flags {
        const val TRANSIENT_PAIRING = 0x10
    }
    
    /**
     * Encode a map of TLV type -> value pairs to TLV8 format.
     * Values > 255 bytes are automatically fragmented.
     */
    fun encode(items: Map<Int, ByteArray>): ByteArray {
        val result = mutableListOf<Byte>()
        
        for ((type, value) in items) {
            var offset = 0
            
            // Handle fragmentation for values > 255 bytes
            while (offset < value.size) {
                val chunk = value.copyOfRange(offset, minOf(offset + 255, value.size))
                result.add(type.toByte())
                result.add(chunk.size.toByte())
                result.addAll(chunk.toList())
                offset += 255
            }
            
            // Handle empty values
            if (value.isEmpty()) {
                result.add(type.toByte())
                result.add(0.toByte())
            }
        }
        
        return result.toByteArray()
    }
    
    /**
     * Convenience function to encode with vararg pairs
     */
    fun encode(vararg pairs: Pair<Int, ByteArray>): ByteArray {
        return encode(pairs.toMap())
    }
    
    /**
     * Decode TLV8 format to a map.
     * Fragmented values with same type are automatically reassembled.
     */
    fun decode(data: ByteArray): Map<Int, ByteArray> {
        val result = mutableMapOf<Int, ByteArray>()
        var offset = 0
        
        while (offset < data.size) {
            if (offset + 2 > data.size) break
            
            val type = data[offset].toInt() and 0xFF
            val length = data[offset + 1].toInt() and 0xFF
            offset += 2
            
            if (offset + length > data.size) break
            
            val value = data.copyOfRange(offset, offset + length)
            offset += length
            
            // Concatenate if type already exists (fragmented values)
            result[type] = if (result.containsKey(type)) {
                result[type]!! + value
            } else {
                value
            }
        }
        
        return result
    }
    
    /**
     * Helper to create a single-byte value
     */
    fun byteValue(value: Int): ByteArray = byteArrayOf(value.toByte())
}
