package com.airplay.streamer.raop

import com.airplay.streamer.util.LogServer
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest

data class RtspResponse(val code: Int, val reason: String, val headers: Map<String, String>, val body: ByteArray) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

/**
 * One RTSP control connection to a RAOP receiver. All requests are serialised; each call
 * returns the full response (headers + body). Handles Digest authentication transparently
 * once [password] is set: a 401 is retried with credentials (lowercase hex as
 * shairport-sync expects, then uppercase hex as Apple receivers historically used).
 */
class RtspConnection(
    private val host: String,
    private val port: Int,
    private val userAgent: String,
    private val baseHeaders: () -> Map<String, String>,
) {
    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var cseq = 0
    private val lock = Any()

    var password: String? = null
    private var authRealm: String? = null
    private var authNonce: String? = null
    private var authUppercase = false

    val localIp: String get() = socket?.localAddress?.hostAddress ?: "0.0.0.0"
    val isOpen: Boolean get() = socket?.let { it.isConnected && !it.isClosed } == true

    fun open(connectTimeoutMs: Int = 5000, readTimeoutMs: Int = 10000) {
        close()
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), connectTimeoutMs)
        s.soTimeout = readTimeoutMs
        socket = s
        input = BufferedInputStream(s.getInputStream())
        output = s.getOutputStream()
        cseq = 0
    }

    fun close() {
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }

    fun setReadTimeout(ms: Int) {
        socket?.soTimeout = ms
    }

    fun request(
        method: String,
        uri: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        protocol: String = "RTSP/1.0",
        quiet: Boolean = false,
    ): RtspResponse = synchronized(lock) {
        var response = send(method, uri, headers, body, protocol, quiet)
        if (response.code == 401) {
            val challenge = response.header("WWW-Authenticate")
            if (challenge != null && challenge.startsWith("Digest", ignoreCase = true) && password != null) {
                authRealm = Regex("realm=\"([^\"]*)\"").find(challenge)?.groupValues?.get(1)
                authNonce = Regex("nonce=\"([^\"]*)\"").find(challenge)?.groupValues?.get(1)
                authUppercase = false
                response = send(method, uri, headers, body, protocol, quiet)
                if (response.code == 401) {
                    authUppercase = true
                    response = send(method, uri, headers, body, protocol, quiet)
                }
            }
        }
        response
    }

    /**
     * Returns true if the receiver closed the connection. Must not be called while a
     * request is in flight on another thread (it takes the same lock).
     */
    fun probeClosed(): Boolean = synchronized(lock) {
        val s = socket ?: return true
        if (s.isClosed) return true
        val inp = input ?: return true
        val previous = s.soTimeout
        return try {
            if (inp.available() > 0) return false
            s.soTimeout = 50
            inp.mark(1)
            val b = inp.read()
            if (b >= 0) inp.reset()
            b < 0
        } catch (_: SocketTimeoutException) {
            false
        } catch (_: Exception) {
            true
        } finally {
            runCatching { s.soTimeout = previous }
        }
    }

    private fun send(
        method: String, uri: String, headers: Map<String, String>, body: ByteArray?,
        protocol: String, quiet: Boolean,
    ): RtspResponse {
        val out = output ?: throw IllegalStateException("RTSP connection not open")
        val sb = StringBuilder()
        sb.append(method).append(' ').append(uri).append(' ').append(protocol).append("\r\n")
        sb.append("CSeq: ").append(++cseq).append("\r\n")
        sb.append("User-Agent: ").append(userAgent).append("\r\n")
        for ((k, v) in baseHeaders()) sb.append(k).append(": ").append(v).append("\r\n")
        for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
        authorization(method, uri)?.let { sb.append("Authorization: ").append(it).append("\r\n") }
        if (body != null) sb.append("Content-Length: ").append(body.size).append("\r\n")
        sb.append("\r\n")
        if (!quiet) LogServer.d(TAG, "> $method $uri")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        if (body != null) out.write(body)
        out.flush()
        val resp = readResponse()
        if (!quiet) LogServer.d(TAG, "< ${resp.code} ${resp.reason} ($method)")
        return resp
    }

    private fun authorization(method: String, uri: String): String? {
        val pwd = password ?: return null
        val realm = authRealm ?: return null
        val nonce = authNonce ?: return null
        val user = "iTunes"
        val ha1 = md5Hex("$user:$realm:$pwd")
        val ha2 = md5Hex("$method:$uri")
        val response = md5Hex("$ha1:$nonce:$ha2")
        return "Digest username=\"$user\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", response=\"$response\""
    }

    private fun md5Hex(s: String): String {
        val hex = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return if (authUppercase) hex.uppercase() else hex
    }

    private fun readResponse(): RtspResponse {
        val inp = input ?: throw IllegalStateException("RTSP connection not open")
        val statusLine = readLine(inp) ?: throw java.io.EOFException("receiver closed the connection")
        val parts = statusLine.split(" ", limit = 3)
        val code = parts.getOrNull(1)?.toIntOrNull() ?: throw java.io.IOException("bad status line: $statusLine")
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(inp) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
        }
        val len = headers.entries.firstOrNull { it.key.equals("Content-Length", true) }?.value?.toIntOrNull() ?: 0
        val body = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = inp.read(body, off, len - off)
            if (n < 0) break
            off += n
        }
        return RtspResponse(code, parts.getOrElse(2) { "" }, headers, body)
    }

    private fun readLine(inp: InputStream): String? {
        val out = ByteArrayOutputStream(128)
        while (true) {
            val b = inp.read()
            if (b < 0) return if (out.size() == 0) null else out.toString(Charsets.ISO_8859_1.name())
            if (b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
        }
        return out.toString(Charsets.ISO_8859_1.name())
    }

    companion object {
        private const val TAG = "Rtsp"
    }
}
