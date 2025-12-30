package com.airplay.streamer.raop

import android.util.Log
import com.airplay.streamer.discovery.AirPlayDevice
import com.airplay.streamer.util.LogServer
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AirPlay 2 Client (HTTP-based)
 * Handles Pair-Setup, Pair-Verify, and audio streaming on port 7000
 */
class AirPlay2Client(private val device: AirPlayDevice) {

    companion object {
        private const val TAG = "AirPlay2Client"
        private const val USER_AGENT = "AirPlay/420.14"
    }

    private var socket: Socket? = null
    private var outputStream: BufferedOutputStream? = null
    private var inputStream: BufferedInputStream? = null
    private val isConnected = AtomicBoolean(false)
    private val isPaired = AtomicBoolean(false)
    
    // Auth Helper
    private val auth = AirPlayAuth()
    
    // Session keys after Pair-Verify
    private var sessionKey: ByteArray? = null
    private var audioSocket: DatagramSocket? = null
    private var audioPort: Int = 0

    fun connect() {
        if (isConnected.get()) return
        
        Log.d(TAG, "Connecting to ${device.host}:${device.port} (AirPlay 2)...")
        LogServer.log("AirPlay 2: Connecting to ${device.host}:${device.port}")
        try {
            socket = Socket(device.host, device.port).apply {
                soTimeout = 10000 // 10s timeout
                tcpNoDelay = true
            }
            outputStream = BufferedOutputStream(socket!!.getOutputStream())
            inputStream = BufferedInputStream(socket!!.getInputStream())
            isConnected.set(true)
            Log.d(TAG, "Connected to ${device.host}:${device.port}")
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed: ${e.message}")
            LogServer.log("AirPlay 2: Connection failed: ${e.message}")
            disconnect()
            throw e
        }
    }

    /**
     * Pair-Setup: SRP-6a authentication
     */
    fun pair(pin: String = "0000") {
        if (!isConnected.get()) connect()
        
        Log.d(TAG, "Starting Pair-Setup with PIN: $pin")
        LogServer.log("AirPlay 2: Starting Pair-Setup with PIN: $pin")
        
        // M1: Send Pair-Setup Request
        val m1Data = auth.createPairSetupM1()
        val response1 = sendRequest("POST", "/pair-setup", m1Data, "application/octet-stream")
        
        if (response1.code != 200) {
            LogServer.log("AirPlay 2: Pair-Setup M1 failed: ${response1.code}")
            throw Exception("Pair-Setup M1 failed: ${response1.code}")
        }
        
        // M2: Parse Server Response
        Log.d(TAG, "Pair-Setup M1 Sent. Received M2 (${response1.body.size} bytes)")
        LogServer.log("AirPlay 2: M1 OK, received M2 (${response1.body.size} bytes)")
        
        // Generate M3
        val m3Data = auth.parseM2AndGenerateM3(response1.body, pin)
        
        // M3: Send Client Proof
        val response2 = sendRequest("POST", "/pair-setup", m3Data, "application/octet-stream")
        
        if (response2.code != 200) {
            LogServer.log("AirPlay 2: Pair-Setup M3 failed: ${response2.code}")
            throw Exception("Pair-Setup M3 failed: ${response2.code}")
        }

        Log.d(TAG, "Pair-Setup M3 Sent. Received M4 (${response2.body.size} bytes)")
        LogServer.log("AirPlay 2: M3 OK, received M4 (${response2.body.size} bytes)")
        
        // M4: Parse Server Proof and Finish
        auth.parseM4AndFinish(response2.body)
        
        isPaired.set(true)
        Log.d(TAG, "Pair-Setup Successful!")
        LogServer.log("AirPlay 2: Pair-Setup Successful!")
    }

    /**
     * Setup audio stream after pairing
     * Sends /setup request with binary plist payload
     */
    fun setupAudioStream(): Int {
        if (!isConnected.get()) throw Exception("Not connected")
        
        LogServer.log("AirPlay 2: Setting up audio stream...")
        
        // Create audio socket for receiving/sending
        audioSocket = DatagramSocket()
        val localPort = audioSocket!!.localPort
        
        // Build setup request as binary plist (simplified - using text plist for compatibility)
        val setupPlist = buildSetupPlist(localPort)
        
        val response = sendRequest("POST", "/stream", setupPlist, "application/x-apple-binary-plist")
        
        if (response.code != 200) {
            LogServer.log("AirPlay 2: /stream setup failed: ${response.code}")
            throw Exception("/stream setup failed: ${response.code}")
        }
        
        // Parse response for server audio port
        // For now, assume default port 6001
        audioPort = 6001
        
        LogServer.log("AirPlay 2: Audio stream setup complete. Server port: $audioPort")
        return audioPort
    }
    
    /**
     * Build a simple setup request payload
     * Real AirPlay 2 uses binary plists, but many receivers accept text format
     */
    private fun buildSetupPlist(localPort: Int): ByteArray {
        // Simplified plist - would need proper binary plist for full compatibility
        val plist = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>streams</key>
    <array>
        <dict>
            <key>type</key>
            <integer>96</integer>
            <key>audioFormat</key>
            <integer>262144</integer>
            <key>controlPort</key>
            <integer>$localPort</integer>
            <key>ct</key>
            <integer>2</integer>
            <key>spf</key>
            <integer>352</integer>
        </dict>
    </array>
    <key>timingProtocol</key>
    <string>NTP</string>
</dict>
</plist>"""
        return plist.toByteArray(StandardCharsets.UTF_8)
    }

    /**
     * Stream audio data (simplified - would need proper RTP for full implementation)
     */
    fun streamAudio(audioData: ByteArray) {
        val socket = audioSocket ?: return
        if (audioPort == 0) return
        
        try {
            val address = InetAddress.getByName(device.host)
            val packet = DatagramPacket(audioData, audioData.size, address, audioPort)
            socket.send(packet)
        } catch (e: Exception) {
            LogServer.log("AirPlay 2: Audio send error: ${e.message}")
        }
    }

    fun disconnect() {
        try {
            socket?.close()
            audioSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing socket", e)
        }
        isConnected.set(false)
        isPaired.set(false)
    }
    
    fun isConnected(): Boolean = isConnected.get()
    fun isPaired(): Boolean = isPaired.get()

    // --- HTTP Helper ---

    data class HttpResponse(val code: Int, val headers: Map<String, String>, val body: ByteArray)

    private fun sendRequest(method: String, path: String, body: ByteArray, contentType: String = "application/pairing+tlv8"): HttpResponse {
        val out = outputStream ?: throw Exception("Not connected")
        val contentLen = body.size
        
        val request = StringBuilder()
        request.append("$method $path HTTP/1.1\r\n")
        request.append("Host: ${device.host}\r\n")
        request.append("User-Agent: AirPlay/320.20\r\n")
        request.append("Content-Length: $contentLen\r\n")
        request.append("Content-Type: $contentType\r\n")
        request.append("\r\n")
        
        // Write Headers
        out.write(request.toString().toByteArray(StandardCharsets.UTF_8))
        // Write Body
        if (contentLen > 0) {
            out.write(body)
        }
        out.flush()
        
        return readResponse()
    }

    private fun readResponse(): HttpResponse {
        val stream = inputStream ?: throw Exception("Not connected")
        
        // Read Status Line
        val statusLine = readLine(stream)
        val parts = statusLine.split(" ")
        if (parts.size < 2) throw Exception("Invalid HTTP response: $statusLine")
        val code = parts[1].toInt()
        
        // Read Headers
        val headers = mutableMapOf<String, String>()
        var contentLength = 0
        while (true) {
            val line = readLine(stream)
            if (line.isEmpty()) break
            
            val colon = line.indexOf(":")
            if (colon != -1) {
                val key = line.substring(0, colon).trim()
                val value = line.substring(colon + 1).trim()
                headers[key] = value
                if (key.equals("Content-Length", ignoreCase = true)) {
                    contentLength = value.toInt()
                }
            }
        }
        
        // Read Body
        val body = ByteArray(contentLength)
        if (contentLength > 0) {
            var offset = 0
            while (offset < contentLength) {
                val read = stream.read(body, offset, contentLength - offset)
                if (read == -1) break
                offset += read
            }
        }
        
        return HttpResponse(code, headers, body)
    }

    private fun readLine(stream: BufferedInputStream): String {
        val bytes = java.io.ByteArrayOutputStream()
        var c: Int
        while (stream.read().also { c = it } != -1) {
            if (c == '\n'.code) break
            if (c != '\r'.code) bytes.write(c) 
        }
        return bytes.toString("UTF-8")
    }
}
