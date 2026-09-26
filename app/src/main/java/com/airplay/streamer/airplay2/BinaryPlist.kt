package com.airplay.streamer.airplay2

import com.dd.plist.BinaryPropertyListWriter
import com.dd.plist.NSArray
import com.dd.plist.NSData
import com.dd.plist.NSDictionary
import com.dd.plist.NSNumber
import com.dd.plist.NSObject
import com.dd.plist.NSString
import com.dd.plist.PropertyListParser

/**
 * Binary plist (bplist00) encode/decode for AirPlay 2 RTSP bodies, backed by dd-plist.
 *
 * AirPlay 2 replaces AirPlay 1's SDP ANNOUNCE with binary plist SETUP / GET /info
 * bodies (Content-Type: application/x-apple-binary-plist).
 */
object BinaryPlist {

    const val CONTENT_TYPE = "application/x-apple-binary-plist"

    /** Encode a Kotlin map (with nested maps/lists/primitives/ByteArray) to a binary plist. */
    fun encode(map: Map<String, Any?>): ByteArray =
        BinaryPropertyListWriter.writeToArray(toNs(map))

    /** Parse a binary plist body into an NSDictionary. */
    fun decode(bytes: ByteArray): NSDictionary =
        PropertyListParser.parse(bytes) as NSDictionary

    private fun toNs(value: Any?): NSObject = when (value) {
        is NSObject -> value
        is Map<*, *> -> NSDictionary().apply {
            for ((k, v) in value) put(k.toString(), toNs(v))
        }
        is List<*> -> NSArray(value.size).apply {
            value.forEachIndexed { i, v -> setValue(i, toNs(v)) }
        }
        is ByteArray -> NSData(value)
        is Boolean -> NSNumber(value)
        is Int -> NSNumber(value)
        is Long -> NSNumber(value)
        is Double -> NSNumber(value)
        is String -> NSString(value)
        null -> NSString("")
        else -> NSString(value.toString())
    }

    // --- typed accessors for decoded responses ---

    fun int(dict: NSDictionary, key: String): Int? = (dict[key] as? NSNumber)?.intValue()

    fun data(dict: NSDictionary, key: String): ByteArray? = (dict[key] as? NSData)?.bytes()

    fun firstStream(dict: NSDictionary): NSDictionary? =
        (dict["streams"] as? NSArray)?.array?.firstOrNull() as? NSDictionary
}
