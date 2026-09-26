package com.airplay.streamer.airplay2

import com.airplay.streamer.util.LogServer
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.random.Random

/** Parsed HTTP/RTSP response. */
data class AirPlay2Response(
    val statusCode: Int,
    val headers: Map<String, String>,
    val body: ByteArray,
)

/**
 * Single TCP connection to an AirPlay 2 receiver carrying both HTTP (pairing) and
 * RTSP (SETUP/RECORD/...) requests. After transient pairing, [enableEncryption]
 * installs a [HapSession] so all subsequent traffic is ChaCha20-Poly1305 framed.
 *
 * Mirrors pyatv's `support/http.py` + `support/rtsp.py` behaviour closely enough
 * for the single-device realtime audio path.
 */
class AirPlay2Connection(
    private val host: String,
    private val port: Int,
    private val senderName: String = "centuryplay",
) {
    private var socket: Socket? = null
    private var output: OutputStream? = null
    private var input: InputStream? = null

    /** Local IP chosen by the OS for this connection (used in RTSP URIs / SDP). */
    var localIp: String = "0.0.0.0"
        private set

    private var session: HapSession? = null
    private var plaintext = ByteArray(0) // decrypted bytes; [readPos] onwards not yet consumed
    private var readPos = 0

    private var cseq = 0
    val dacpId: String = com.airplay.streamer.service.DacpServer.dacpId
    val activeRemote: String = com.airplay.streamer.service.DacpServer.activeRemote

    fun connect(timeoutMs: Int = 10000) {
        val s = Socket()
        s.connect(InetSocketAddress(host, port), timeoutMs)
        s.soTimeout = timeoutMs
        socket = s
        output = s.getOutputStream()
        input = s.getInputStream()
        localIp = s.localAddress.hostAddress ?: "0.0.0.0"
    }

    fun setReadTimeout(ms: Int) {
        socket?.soTimeout = ms
    }

    /** Close and open a fresh TCP connection (e.g. after a rejected pairing attempt). */
    fun reopen(timeoutMs: Int = 10000) {
        close()
        session = null
        plaintext = ByteArray(0)
        readPos = 0
        cseq = 0
        connect(timeoutMs)
    }

    /** Turn on HAP encryption for all subsequent requests/responses. */
    fun enableEncryption(outKey: ByteArray, inKey: ByteArray) {
        session = HapSession(outKey, inKey)
    }

    /** HTTP/1.1 POST, used by the pairing exchange (no CSeq/DACP headers). */
    fun post(
        uri: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
    ): AirPlay2Response {
        val hdrs = LinkedHashMap<String, String>()
        hdrs["X-Apple-Client-Name"] = senderName
        hdrs["User-Agent"] = USER_AGENT
        hdrs.putAll(headers)
        return send("POST", uri, "HTTP/1.1", hdrs, body ?: ByteArray(0))
    }

    /** RTSP exchange (adds CSeq, DACP-ID, Active-Remote, Client-Instance). */
    fun exchange(
        method: String,
        uri: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        contentType: String? = null,
    ): AirPlay2Response {
        val hdrs = LinkedHashMap<String, String>()
        hdrs["CSeq"] = (cseq++).toString()
        hdrs["DACP-ID"] = dacpId
        hdrs["Active-Remote"] = activeRemote
        hdrs["Client-Instance"] = dacpId
        if (contentType != null) hdrs["Content-Type"] = contentType
        hdrs.putAll(headers)
        return send(method, uri, "RTSP/1.0", hdrs, body)
    }

    private fun send(
        method: String,
        uri: String,
        protocol: String,
        headers: MutableMap<String, String>,
        body: ByteArray?,
    ): AirPlay2Response {
        val sb = StringBuilder()
        sb.append("$method $uri $protocol\r\n")
        if (body != null) headers["Content-Length"] = body.size.toString()
        for ((key, value) in headers) sb.append("$key: $value\r\n")
        sb.append("\r\n")

        val headerBytes = sb.toString().toByteArray(Charsets.ISO_8859_1)
        val requestBytes = if (body != null) headerBytes + body else headerBytes
        writeRaw(requestBytes)

        return readResponse()
    }

    private fun writeRaw(data: ByteArray) {
        val out = output ?: throw IllegalStateException("not connected")
        val toSend = session?.encrypt(data) ?: data
        out.write(toSend)
        out.flush()
    }

    private fun readResponse(): AirPlay2Response {
        val statusLine = readLine()
        val statusCode = statusLine.split(" ").getOrNull(1)?.toIntOrNull()
            ?: throw IllegalStateException("malformed status line: $statusLine")

        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine()
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
        }

        val contentLength = headers.entries
            .firstOrNull { it.key.equals("Content-Length", ignoreCase = true) }
            ?.value?.toIntOrNull() ?: 0
        val body = if (contentLength > 0) readExact(contentLength) else ByteArray(0)

        return AirPlay2Response(statusCode, headers, body)
    }

    private fun readLine(): String {
        val out = ByteArrayOutputStream()
        var prev = -1
        while (true) {
            val b = readByte()
            if (prev == '\r'.code && b == '\n'.code) {
                val bytes = out.toByteArray()
                return String(bytes, 0, bytes.size - 1, Charsets.ISO_8859_1) // drop trailing \r
            }
            out.write(b)
            prev = b
        }
    }

    private fun readByte(): Int {
        ensurePlaintext(1)
        return plaintext[readPos++].toInt() and 0xFF
    }

    private fun readExact(n: Int): ByteArray {
        ensurePlaintext(n)
        val result = plaintext.copyOfRange(readPos, readPos + n)
        readPos += n
        return result
    }

    /** Block until at least [n] unread plaintext bytes are buffered, refilling from the socket. */
    private fun ensurePlaintext(n: Int) {
        val inp = input ?: throw IllegalStateException("not connected")
        if (plaintext.size - readPos >= n) return
        // compact
        if (readPos > 0) {
            plaintext = plaintext.copyOfRange(readPos, plaintext.size)
            readPos = 0
        }
        val buf = ByteArray(8192)
        while (plaintext.size < n) {
            val read = inp.read(buf)
            if (read < 0) throw java.io.EOFException("connection closed by receiver")
            if (read == 0) continue
            val chunk = buf.copyOfRange(0, read)
            val decoded = session?.decrypt(chunk) ?: chunk
            if (decoded.isNotEmpty()) plaintext += decoded
        }
    }

    fun close() {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }

    fun logD(msg: String) = LogServer.d(TAG, msg)

    companion object {
        private const val TAG = "AirPlay2Connection"
        const val USER_AGENT = "AirPlay/550.10"
    }
}
