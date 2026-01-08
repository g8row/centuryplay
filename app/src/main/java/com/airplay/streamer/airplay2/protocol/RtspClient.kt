package com.airplay.streamer.airplay2.protocol

import com.airplay.streamer.airplay2.crypto.*
import com.airplay.streamer.util.LogServer
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * RTSP Client for AirPlay 2 communication
 * 
 * Handles encrypted RTSP requests/responses after transient pairing.
 */
class RtspClient(
    private val host: String,
    private val port: Int = 7000
) {
    companion object {
        private val AIRPLAY_HEADERS = mapOf(
            "User-Agent" to "AirPlay/320.20",
            "Connection" to "keep-alive",
            "X-Apple-HKP" to "4"
        )
    }
    
    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    var cseq = 0
        private set
    
    // Encryption state
    private val hapSession = HapSession()
    
    // SRP state
    private var srpClient: Srp6aClient? = null
    
    // Session state
    var sessionUuid: String? = null
        private set
    var audioSharedSecret: ByteArray? = null
        private set
    var audioControlPort: Int = 0
        private set
    var audioDataPort: Int = 0
        private set
    
    /**
     * Connect to the AirPlay receiver
     */
    fun connect() {
        socket = Socket(host, port).apply {
            soTimeout = 10000
            keepAlive = true
        }
        input = socket!!.getInputStream()
        output = socket!!.getOutputStream()
    }
    
    /**
     * Disconnect from the receiver
     */
    fun disconnect() {
        socket?.close()
        socket = null
        input = null
        output = null
    }
    
    /**
     * Get local IP address of the connected socket
     */
    fun getLocalAddress(): String? = socket?.localAddress?.hostAddress
    
    /**
     * Send RTSP request and get response
     */
    fun sendRtsp(
        method: String,
        path: String,
        body: ByteArray = ByteArray(0),
        contentType: String = "application/octet-stream",
        extraHeaders: Map<String, String> = emptyMap()
    ): RtspResponse {
        cseq++
        
        val headers = mutableMapOf(
            "CSeq" to cseq.toString(),
            "Host" to "$host:$port",
            "Content-Type" to contentType,
            "Content-Length" to body.size.toString()
        )
        headers.putAll(AIRPLAY_HEADERS)
        headers.putAll(extraHeaders)
        
        val requestLine = "$method $path RTSP/1.0\r\n"
        val headerLines = headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }
        var request = (requestLine + headerLines + "\r\n").toByteArray() + body
        
        // Encrypt if HAP session is enabled
        if (hapSession.isEnabled) {
            LogServer.log("RtspClient: Encrypting request (${request.size} bytes)")
            request = hapSession.encrypt(request)
            LogServer.log("RtspClient: Sending encrypted request (${request.size} bytes)")
        } else {
            LogServer.log("RtspClient: Sending plaintext request (${request.size} bytes)")
        }
        
        output!!.write(request)
        output!!.flush()
        
        return readResponse()
    }
    
    /**
     * Read RTSP response, handling HAP encryption if enabled
     */
    private fun readResponse(): RtspResponse {
        val buffer = ByteArray(4096)
        var responseBuffer = ByteArray(0) // Accumulates PLAINTEXT (decrypted or raw)
        
        LogServer.log("RtspClient: Waiting for response...")
        
        while (true) {
            val bytesRead = input!!.read(buffer)
            if (bytesRead < 0) {
                LogServer.log("RtspClient: Connection closed by server (EOF)")
                throw Exception("Connection closed")
            }
            
            LogServer.log("RtspClient: Read $bytesRead bytes from socket")
            
            val chunk = buffer.copyOf(bytesRead)
            
            // Decrypt if HAP session is enabled
            val data = if (hapSession.isEnabled) {
                val decrypted = hapSession.decrypt(chunk)
                LogServer.log("RtspClient: Decrypted ${chunk.size} bytes -> ${decrypted.size} bytes")
                decrypted
            } else {
                chunk
            }
            
            responseBuffer += data
            
            val text = String(responseBuffer)
            if ("\r\n\r\n" in text) {
                val headerEnd = text.indexOf("\r\n\r\n")
                val headerPart = text.substring(0, headerEnd)
                
                var contentLength = 0
                for (line in headerPart.split("\r\n")) {
                    if (line.lowercase().startsWith("content-length:")) {
                        contentLength = line.substringAfter(":").trim().toInt()
                    }
                }
                
                val bodyStart = headerEnd + 4
                if (responseBuffer.size >= bodyStart + contentLength) {
                    LogServer.log("RtspClient: Full response received ($contentLength body bytes)")
                    return parseResponse(responseBuffer)
                }
            }
        }
    }
    
    /**
     * Parse RTSP response bytes
     */
    private fun parseResponse(data: ByteArray): RtspResponse {
        val text = String(data)
        val headerEnd = text.indexOf("\r\n\r\n")
        val headerLines = text.substring(0, headerEnd).split("\r\n")
        
        // Parse status code from first line
        val statusLine = headerLines[0]
        val statusCode = statusLine.split(" ")[1].toInt()
        
        // Parse headers
        val headers = mutableMapOf<String, String>()
        for (line in headerLines.drop(1)) {
            if (":" in line) {
                val (key, value) = line.split(":", limit = 2)
                headers[key.trim()] = value.trim()
            }
        }
        
        // Extract body
        val bodyStart = headerEnd + 4
        val body = data.copyOfRange(bodyStart, data.size)
        
        return RtspResponse(statusCode, headers, body)
    }
    
    /**
     * Enable HAP session encryption using derived Control keys
     */
    fun enableEncryption(sessionKey: ByteArray) {
        val outputKey = Hkdf.Control.deriveOutputKey(sessionKey)
        val inputKey = Hkdf.Control.deriveInputKey(sessionKey)
        
        hapSession.enable(outputKey, inputKey)
    }
}

/**
 * RTSP Response data class
 */
data class RtspResponse(
    val statusCode: Int,
    val headers: Map<String, String>,
    val body: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as RtspResponse
        return statusCode == other.statusCode && headers == other.headers && body.contentEquals(other.body)
    }
    
    override fun hashCode(): Int {
        var result = statusCode
        result = 31 * result + headers.hashCode()
        result = 31 * result + body.contentHashCode()
        return result
    }
}
