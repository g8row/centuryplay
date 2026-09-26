package com.airplay.streamer.airplay2

import com.airplay.streamer.util.LogServer
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * The AirPlay 2 event channel, opened after the first SETUP (which returns its port).
 *
 * Like pyatv (`protocols/airplay/channels.py` EventChannel + `auth/hap_channel.py`
 * setup_channel), traffic is HAP-framed ChaCha20-Poly1305 with keys derived from the
 * pairing secret — output key from "Events-Read-Encryption-Key", input key from
 * "Events-Write-Encryption-Key" — and every request the receiver sends gets a
 * `200 OK` echoing its CSeq/Server headers so the receiver doesn't time the session out.
 *
 * The receiver opens its side slightly late, so connecting is retried (pyatv airplayv2.py).
 */
class AirPlay2EventChannel(
    private val host: String,
    private val port: Int,
    sharedSecret: ByteArray,
) {
    private var socket: Socket? = null
    @Volatile private var running = false
    private val session = HapSession(
        outKey = Hkdf.expand(EVENTS_SALT, EVENTS_READ_INFO, sharedSecret),
        inKey = Hkdf.expand(EVENTS_SALT, EVENTS_WRITE_INFO, sharedSecret),
    )

    /** Called with (method, uri, decrypted body) for each receiver event. */
    var onEvent: ((String, String, ByteArray) -> Unit)? = null

    fun connectWithRetries(retries: Int = 5, backoffMs: Long = 1000): Boolean {
        var attempt = 0
        while (attempt < retries) {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(host, port), 5000)
                socket = s
                running = true
                startReader(s)
                LogServer.d(TAG, "Event channel connected to $host:$port")
                return true
            } catch (e: Exception) {
                attempt++
                LogServer.d(TAG, "Event channel connect failed (attempt $attempt): ${e.message}")
                if (attempt >= retries) return false
                Thread.sleep(backoffMs)
            }
        }
        return false
    }

    private fun startReader(s: Socket) {
        thread(name = "AirPlay2EventChannel", isDaemon = true) {
            val buf = ByteArray(4096)
            var pending = ByteArray(0)
            try {
                val input = s.getInputStream()
                val output = s.getOutputStream()
                while (running && !s.isClosed) {
                    val n = input.read(buf)
                    if (n < 0) break
                    pending += session.decrypt(buf.copyOf(n))
                    while (true) {
                        val (request, rest) = parseRequest(pending) ?: break
                        pending = rest
                        respond(output, request)
                    }
                }
            } catch (e: Exception) {
                if (running) LogServer.d(TAG, "Event channel closed: ${e.message}")
            }
        }
    }

    private class Request(val method: String, val uri: String, val protocol: String, val headers: Map<String, String>, val body: ByteArray)

    private fun parseRequest(data: ByteArray): Pair<Request, ByteArray>? {
        val text = String(data, Charsets.ISO_8859_1)
        val end = text.indexOf("\r\n\r\n")
        if (end < 0) return null
        val lines = text.substring(0, end).split("\r\n")
        val first = lines.first().split(" ")
        val headers = LinkedHashMap<String, String>()
        for (line in lines.drop(1)) {
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim()] = line.substring(i + 1).trim()
        }
        val len = headers.entries.firstOrNull { it.key.equals("Content-Length", true) }?.value?.toIntOrNull() ?: 0
        val bodyStart = end + 4
        if (data.size < bodyStart + len) return null
        val body = data.copyOfRange(bodyStart, bodyStart + len)
        val request = Request(first.getOrElse(0) { "" }, first.getOrElse(1) { "" }, first.getOrElse(2) { "RTSP/1.0" }, headers, body)
        return request to data.copyOfRange(bodyStart + len, data.size)
    }

    private fun respond(output: OutputStream, request: Request) {
        LogServer.d(TAG, "event: ${request.method} ${request.uri} (${request.body.size} bytes)")
        runCatching { onEvent?.invoke(request.method, request.uri, request.body) }
        val sb = StringBuilder("${request.protocol} 200 OK\r\nContent-Length: 0\r\nAudio-Latency: 0\r\n")
        request.headers.entries.firstOrNull { it.key.equals("Server", true) }?.let { sb.append("Server: ${it.value}\r\n") }
        request.headers.entries.firstOrNull { it.key.equals("CSeq", true) }?.let { sb.append("CSeq: ${it.value}\r\n") }
        sb.append("\r\n")
        synchronized(this) {
            output.write(session.encrypt(sb.toString().toByteArray(Charsets.ISO_8859_1)))
            output.flush()
        }
    }

    fun close() {
        running = false
        runCatching { socket?.close() }
        socket = null
    }

    companion object {
        private const val TAG = "AirPlay2Events"
        const val EVENTS_SALT = "Events-Salt"
        const val EVENTS_WRITE_INFO = "Events-Write-Encryption-Key"
        const val EVENTS_READ_INFO = "Events-Read-Encryption-Key"
    }
}
