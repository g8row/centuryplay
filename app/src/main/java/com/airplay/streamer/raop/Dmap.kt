package com.airplay.streamer.raop

import java.io.ByteArrayOutputStream

/** Minimal DMAP (DAAP) tag encoder for RAOP track metadata (`application/x-dmap-tagged`). */
object Dmap {
    fun string(tag: String, value: String): ByteArray = item(tag, value.toByteArray(Charsets.UTF_8))

    fun item(tag: String, data: ByteArray): ByteArray {
        require(tag.length == 4)
        val out = ByteArrayOutputStream(8 + data.size)
        out.write(tag.toByteArray(Charsets.US_ASCII))
        out.write(data.size ushr 24); out.write(data.size ushr 16); out.write(data.size ushr 8); out.write(data.size)
        out.write(data)
        return out.toByteArray()
    }

    fun container(tag: String, vararg children: ByteArray): ByteArray {
        val body = ByteArrayOutputStream()
        children.forEach { body.write(it) }
        return item(tag, body.toByteArray())
    }

    /** `mlit` listing item with title (minm), artist (asar) and album (asal) — as iTunes sends. */
    fun trackInfo(title: String?, artist: String?, album: String?): ByteArray {
        val children = buildList {
            add(string("minm", title ?: ""))
            add(string("asar", artist ?: ""))
            add(string("asal", album ?: ""))
        }
        return container("mlit", *children.toTypedArray())
    }
}
