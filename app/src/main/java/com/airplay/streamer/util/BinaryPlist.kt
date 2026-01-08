package com.airplay.streamer.util

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

/**
 * Simple Binary PList writer for AirPlay 2 SETUP requests.
 * 
 * Apple's Binary PList format: https://opensource.apple.com/source/CF/CF-550/CFBinaryPList.c
 * 
 * This implementation supports only what's needed for AirPlay 2:
 * - Dictionaries
 * - Arrays
 * - Strings (ASCII/UTF-8)
 * - Integers
 * - Data (byte arrays)
 * - Booleans
 */
object BinaryPlist {
    
    // Object types
    private const val TYPE_NULL = 0x00
    private const val TYPE_BOOL_FALSE = 0x08
    private const val TYPE_BOOL_TRUE = 0x09
    private const val TYPE_INT = 0x10
    private const val TYPE_DATA = 0x40
    private const val TYPE_ASCII_STRING = 0x50
    private const val TYPE_UTF16_STRING = 0x60
    private const val TYPE_ARRAY = 0xA0
    private const val TYPE_DICT = 0xD0
    
    /**
     * Encode a Map (dictionary) as binary plist.
     * Supports nested Maps, Lists, Strings, Integers, ByteArrays, and Booleans.
     */
    fun encode(data: Map<String, Any?>): ByteArray {
        // Build object table by recursively collecting all objects
        val objects = mutableListOf<Any?>()
        val objectIndexMap = IdentityHashMap<Any?, Int>()
        
        collectObjects(data, objects, objectIndexMap)
        
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        
        // Write magic header
        dos.write("bplist00".toByteArray(StandardCharsets.US_ASCII))
        
        // Calculate ref size based on total object count
        val refSize = if (objects.size <= 0xFF) 1 else if (objects.size <= 0xFFFF) 2 else 4
        
        // Write all objects and record their offsets
        val offsetTable = mutableListOf<Int>()
        for (obj in objects) {
            offsetTable.add(baos.size())
            writeObject(dos, obj, objectIndexMap, refSize)
        }
        
        // Write offset table
        val offsetTableOffset = baos.size()
        val offsetSize = calculateByteSize(baos.size())
        
        for (offset in offsetTable) {
            writeIntN(dos, offset.toLong(), offsetSize)
        }
        
        // Write trailer (32 bytes)
        dos.write(ByteArray(6)) // Unused bytes
        dos.writeByte(offsetSize) // Offset int size
        dos.writeByte(refSize) // Object ref size
        dos.writeLong(objects.size.toLong()) // Number of objects
        dos.writeLong(0) // Root object index (always 0)
        dos.writeLong(offsetTableOffset.toLong()) // Offset table offset
        
        return baos.toByteArray()
    }
    
    /** Simple identity-based hash map to handle duplicate string keys correctly */
    private class IdentityHashMap<K, V> {
        private val map = mutableMapOf<Int, V>()
        
        fun containsKey(key: K): Boolean = map.containsKey(System.identityHashCode(key))
        operator fun get(key: K): V? = map[System.identityHashCode(key)]
        operator fun set(key: K, value: V) { map[System.identityHashCode(key)] = value }
    }
    
    /**
     * Recursively collect all objects into a flat list.
     * Each object gets a unique index based on identity.
     */
    private fun collectObjects(obj: Any?, objects: MutableList<Any?>, indexMap: IdentityHashMap<Any?, Int>) {
        // Skip if already collected
        if (obj != null && indexMap.containsKey(obj)) {
            return
        }
        
        // Add this object
        val index = objects.size
        objects.add(obj)
        if (obj != null) {
            indexMap[obj] = index
        }
        
        // Recursively add children
        when (obj) {
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                val map = obj as Map<String, Any?>
                // First add all keys, then all values
                for (key in map.keys) {
                    collectObjects(key, objects, indexMap)
                }
                for (value in map.values) {
                    collectObjects(value, objects, indexMap)
                }
            }
            is List<*> -> {
                for (item in obj) {
                    collectObjects(item, objects, indexMap)
                }
            }
        }
    }
    
    private fun writeObject(dos: DataOutputStream, obj: Any?, indexMap: IdentityHashMap<Any?, Int>, refSize: Int) {
        when (obj) {
            null -> dos.writeByte(TYPE_NULL)
            
            is Boolean -> dos.writeByte(if (obj) TYPE_BOOL_TRUE else TYPE_BOOL_FALSE)
            
            is Int, is Long -> {
                val value = if (obj is Int) obj.toLong() else obj as Long
                writeInt(dos, value)
            }
            
            is ByteArray -> {
                writeTypeAndLength(dos, TYPE_DATA, obj.size)
                dos.write(obj)
            }
            
            is String -> {
                val bytes = obj.toByteArray(StandardCharsets.UTF_8)
                val isAscii = bytes.all { it >= 0 && it < 128 }
                if (isAscii) {
                    writeTypeAndLength(dos, TYPE_ASCII_STRING, obj.length)
                    dos.write(bytes)
                } else {
                    // UTF-16 BE
                    val utf16 = obj.toByteArray(StandardCharsets.UTF_16BE)
                    writeTypeAndLength(dos, TYPE_UTF16_STRING, obj.length)
                    dos.write(utf16)
                }
            }
            
            is List<*> -> {
                writeTypeAndLength(dos, TYPE_ARRAY, obj.size)
                for (item in obj) {
                    val itemIndex = indexMap[item] ?: 0
                    writeIntN(dos, itemIndex.toLong(), refSize)
                }
            }
            
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                val map = obj as Map<String, Any?>
                writeTypeAndLength(dos, TYPE_DICT, map.size)
                // Write key refs first
                for (key in map.keys) {
                    val keyIndex = indexMap[key] ?: 0
                    writeIntN(dos, keyIndex.toLong(), refSize)
                }
                // Then value refs
                for (value in map.values) {
                    val valueIndex = indexMap[value] ?: 0
                    writeIntN(dos, valueIndex.toLong(), refSize)
                }
            }
        }
    }
    
    private fun writeInt(dos: DataOutputStream, value: Long) {
        val byteSize = when {
            value >= 0 && value <= 0xFF -> 1
            value >= 0 && value <= 0xFFFF -> 2
            value >= 0 && value <= 0xFFFFFFFFL -> 4
            else -> 8
        }
        val sizeCode = when (byteSize) {
            1 -> 0
            2 -> 1
            4 -> 2
            else -> 3
        }
        dos.writeByte(TYPE_INT or sizeCode)
        writeIntN(dos, value, byteSize)
    }
    
    private fun writeTypeAndLength(dos: DataOutputStream, type: Int, length: Int) {
        if (length < 15) {
            dos.writeByte(type or length)
        } else {
            dos.writeByte(type or 0x0F)
            // Write length as int
            writeInt(dos, length.toLong())
        }
    }
    
    private fun writeIntN(dos: DataOutputStream, value: Long, byteSize: Int) {
        when (byteSize) {
            1 -> dos.writeByte(value.toInt())
            2 -> dos.writeShort(value.toInt())
            4 -> dos.writeInt(value.toInt())
            8 -> dos.writeLong(value)
        }
    }
    
    private fun calculateByteSize(maxValue: Number): Int {
        val value = maxValue.toLong()
        return when {
            value <= 0xFF -> 1
            value <= 0xFFFF -> 2
            value <= 0xFFFFFFFFL -> 4
            else -> 8
        }
    }
    
    /**
     * Parse a binary plist into a Map.
     * Returns null if parsing fails or format is unsupported.
     */
    fun decode(data: ByteArray): Map<String, Any?>? {
        try {
            // Check magic header
            val header = String(data.sliceArray(0..7), StandardCharsets.US_ASCII)
            if (!header.startsWith("bplist")) {
                return null
            }
            
            // Read trailer (last 32 bytes)
            val trailerOffset = data.size - 32
            val offsetSize = data[trailerOffset + 6].toInt() and 0xFF
            val refSize = data[trailerOffset + 7].toInt() and 0xFF
            val numObjects = readLong(data, trailerOffset + 8)
            val rootIndex = readLong(data, trailerOffset + 16)
            val offsetTableOffset = readLong(data, trailerOffset + 24)
            
            // Read offset table
            val offsets = mutableListOf<Int>()
            for (i in 0 until numObjects.toInt()) {
                val offset = readIntN(data, offsetTableOffset.toInt() + i * offsetSize, offsetSize)
                offsets.add(offset)
            }
            
            // Parse root object
            return parseObject(data, offsets, refSize, rootIndex.toInt()) as? Map<String, Any?>
        } catch (e: Exception) {
            LogServer.log("BinaryPlist: Failed to decode: ${e.message}")
            return null
        }
    }
    
    private fun parseObject(data: ByteArray, offsets: List<Int>, refSize: Int, index: Int): Any? {
        val offset = offsets[index]
        val marker = data[offset].toInt() and 0xFF
        val type = marker and 0xF0
        var info = marker and 0x0F
        
        var dataOffset = offset + 1
        
        // Handle extended length
        if (info == 0x0F && type != TYPE_NULL && type != TYPE_BOOL_FALSE) {
            val lenMarker = data[dataOffset].toInt() and 0xFF
            val lenSize = 1 shl (lenMarker and 0x0F)
            dataOffset++
            info = readIntN(data, dataOffset, lenSize)
            dataOffset += lenSize
        }
        
        return when (type) {
            0x00 -> when (marker) {
                TYPE_BOOL_FALSE, TYPE_NULL -> false
                TYPE_BOOL_TRUE -> true
                else -> null
            }
            
            TYPE_INT -> {
                val size = 1 shl info
                readIntN(data, dataOffset, size).toLong()
            }
            
            TYPE_DATA -> {
                data.sliceArray(dataOffset until dataOffset + info)
            }
            
            TYPE_ASCII_STRING -> {
                String(data.sliceArray(dataOffset until dataOffset + info), StandardCharsets.US_ASCII)
            }
            
            TYPE_UTF16_STRING -> {
                String(data.sliceArray(dataOffset until dataOffset + info * 2), StandardCharsets.UTF_16BE)
            }
            
            TYPE_ARRAY -> {
                val list = mutableListOf<Any?>()
                for (i in 0 until info) {
                    val itemRef = readIntN(data, dataOffset + i * refSize, refSize)
                    list.add(parseObject(data, offsets, refSize, itemRef))
                }
                list
            }
            
            TYPE_DICT -> {
                val map = mutableMapOf<String, Any?>()
                for (i in 0 until info) {
                    val keyRef = readIntN(data, dataOffset + i * refSize, refSize)
                    val valueRef = readIntN(data, dataOffset + info * refSize + i * refSize, refSize)
                    val key = parseObject(data, offsets, refSize, keyRef) as? String ?: continue
                    val value = parseObject(data, offsets, refSize, valueRef)
                    map[key] = value
                }
                map
            }
            
            else -> null
        }
    }
    
    private fun readLong(data: ByteArray, offset: Int): Long {
        var result = 0L
        for (i in 0..7) {
            result = (result shl 8) or (data[offset + i].toLong() and 0xFF)
        }
        return result
    }
    
    private fun readIntN(data: ByteArray, offset: Int, size: Int): Int {
        var result = 0
        for (i in 0 until size) {
            result = (result shl 8) or (data[offset + i].toInt() and 0xFF)
        }
        return result
    }
}
