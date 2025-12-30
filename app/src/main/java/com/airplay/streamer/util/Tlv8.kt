package com.airplay.streamer.util

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Utility for Type-Length-Value 8-bit encoding used in AirPlay 2
 */
class Tlv8 {
    private val entries = mutableListOf<Entry>()

    data class Entry(val type: Int, val value: ByteArray)

    fun add(type: Int, value: ByteArray) {
        entries.add(Entry(type, value))
    }
    
    fun add(type: Int, value: String) {
        add(type, value.toByteArray())
    }
    
    fun add(type: Int, value: Byte) {
        add(type, byteArrayOf(value))
    }

    fun encode(): ByteArray {
        val out = ByteArrayOutputStream()
        for (entry in entries) {
            // TLV8 supports max length 255. larger values must be fragmented
            var remaining = entry.value
            if (remaining.isEmpty()) {
                out.write(entry.type)
                out.write(0)
                continue
            }
            
            var offset = 0
            while (offset < remaining.size) {
                val chunkSize = minOf(remaining.size - offset, 255)
                out.write(entry.type)
                out.write(chunkSize)
                out.write(remaining, offset, chunkSize)
                offset += chunkSize
            }
        }
        return out.toByteArray()
    }

    companion object {
        fun decode(data: ByteArray): Map<Int, ByteArray> {
            val result = mutableMapOf<Int, ByteArray>()
            val buffer = ByteBuffer.wrap(data)
            
            while (buffer.hasRemaining()) {
                if (buffer.remaining() < 2) break
                val type = buffer.get().toInt() and 0xFF
                val length = buffer.get().toInt() and 0xFF
                
                if (buffer.remaining() < length) break
                val value = ByteArray(length)
                buffer.get(value)
                
                // If key exists, append (fragmentation support)
                val existing = result[type]
                if (existing != null) {
                    result[type] = existing + value
                } else {
                    result[type] = value
                }
            }
            return result
        }
        
        fun getUuid(map: Map<Int, ByteArray>, type: Int): String? {
             // Not implementing full UUID parse, just bytes
             return map[type]?.let { String(it) }
        }
    }
}
