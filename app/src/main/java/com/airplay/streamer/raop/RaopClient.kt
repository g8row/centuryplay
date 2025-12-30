package com.airplay.streamer.raop

import android.util.Log
import com.airplay.streamer.util.LogServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.security.KeyFactory
import java.security.spec.RSAPublicKeySpec
import java.math.BigInteger
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.IvParameterSpec
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * RAOP (Remote Audio Output Protocol) client for AirPlay 1 speakers
 * Implements the RTSP-based protocol for audio streaming
 */
class RaopClient(
    private val host: String,
    private val port: Int
) {
    companion object {
        private const val TAG = "RaopClient"
        private const val USER_AGENT = "iTunes/12.3 (Windows; N)"
        private const val SAMPLE_RATE = 44100
        private const val CHANNELS = 2
        private const val BITS_PER_SAMPLE = 16
        private const val FRAMES_PER_PACKET = 352
    }
    
    // Client identifiers (as per AirPlay spec)
    private val clientInstance = generateHexId(8)
    private val dacpId = clientInstance
    private val activeRemote = Random.nextLong(100000000, 4294967295).toString()

    private var rtspSocket: Socket? = null
    private var rtspReader: BufferedReader? = null
    private var rtspWriter: PrintWriter? = null
    private var audioSocket: DatagramSocket? = null
    private var controlSocket: DatagramSocket? = null
    private var timingSocket: DatagramSocket? = null

    // Helper to run the timing thread
    private var isTimingRunning = AtomicBoolean(false)

    private val cSeq = AtomicInteger(0)
    private var sessionId: String? = null
    private var serverSessionId: String? = null
    private val localSessionId: String = Random.nextLong(0, Long.MAX_VALUE).toString()
    private var serverPort: Int = 0
    private var controlPort: Int = 0
    private var timingPort: Int = 0

    private val isConnected = AtomicBoolean(false)
    private val isStreaming = AtomicBoolean(false)

    private var rtpSequence: Int = Random.nextInt(0xFFFF)
    private var rtpTimestamp: Long = Random.nextLong(0xFFFFFFFFL)
    private val ssrc: Int = Random.nextInt()

    // Audio buffer for PCM data
    private val alacEncoder = AlacEncoder()

    // RSA Public Key for AirPlay (Standard)
    private val RSA_MODULUS = "59dE8qLieItsH1WgjrcFRKj6eUWqi+bGLOX1HL3U3GhC/j0Qg90u3sG/1CUtwC" +
            "5vOYvfDmFI6oSFXi5ELabWJmT2dKHzBJKa3k9ok+8t9ucRqMd6DZHJ2YCCLlDR" +
            "KSKv6kDqnw4UwPdpOMXziC/AMj3Z/lUVX1G7WSHCAWKf1zNS1eLvqr+boEjXuB" +
            "OitnZ/bDzPHrTOZz0Dew0uowxf/+sG+NCK3eQJVxqcaJ/vEHKIVd2M+5qL71yJ" +
            "Q+87X6oV3eaYvt3zWZYD6z5vYTcrtij2VZ9Zmni/UAaHqn9JdsBWLUEpVviYnh" +
            "imNVvYFZeCXg/IdTQ+x4IRdiXNv5hEew=="
    private val RSA_EXPONENT = "AQAB"

    private var aesKey: ByteArray? = null
    private var aesIv: ByteArray? = null

    interface StreamingCallback {
        fun onConnected()
        fun onDisconnected()
        fun onError(error: String)
    }

    var callback: StreamingCallback? = null
    /**
     * Connect to the AirPlay speaker
     */
    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            logD("Connecting to $host:$port")
            
            // Create RTSP socket with short timeout for diagnostics
            rtspSocket = Socket(host, port).apply {
                soTimeout = 3000  // 3 second timeout for faster feedback
            }
            logD("RTSP socket connected")
            
            rtspReader = BufferedReader(InputStreamReader(rtspSocket!!.getInputStream()))
            rtspWriter = PrintWriter(OutputStreamWriter(rtspSocket!!.getOutputStream()), true)

            // Create UDP sockets for audio/control/timing
            audioSocket = DatagramSocket()
            controlSocket = DatagramSocket()
            timingSocket = DatagramSocket()
            logD("UDP sockets: audio=${audioSocket?.localPort}, ctrl=${controlSocket?.localPort}, time=${timingSocket?.localPort}")

            // Diagnostic: Try OPTIONS first to see if server responds at all
            logD("Testing OPTIONS...")
            val optionsResult = testOptions()
            logD("OPTIONS result: $optionsResult")
            
            // Now try ANNOUNCE with longer timeout
            rtspSocket?.soTimeout = 10000
            logD("Starting ANNOUNCE...")
            if (!announce()) {
                logE("ANNOUNCE failed")
                disconnect()
                return@withContext false
            }
            logD("ANNOUNCE succeeded")

            logD("Starting SETUP...")
            if (!setup()) {
                logE("SETUP failed")
                disconnect()
                return@withContext false
            }
            logD("SETUP succeeded - serverPort=$serverPort")

            logD("Starting RECORD...")
            if (!record()) {
                logE("RECORD failed")
                disconnect()
                return@withContext false
            }
            logD("RECORD succeeded - streaming ready!")

            isConnected.set(true)
            callback?.onConnected()
            true
        } catch (e: Exception) {
            logE("Connection failed: ${e.message}")
            callback?.onError("Connection failed: ${e.message}")
            disconnect()
            false
        }
    }

    /**
     * OPTIONS - Query server capabilities (required before ANNOUNCE)
     */
    private fun options(): Boolean {
        val sb = StringBuilder()
        sb.append("OPTIONS * RTSP/1.0\r\n")
        sb.append("CSeq: ${cSeq.incrementAndGet()}\r\n")
        sb.append("User-Agent: $USER_AGENT\r\n")
        sb.append("Client-Instance: $clientInstance\r\n")
        sb.append("DACP-ID: $dacpId\r\n")
        sb.append("Active-Remote: $activeRemote\r\n")
        sb.append("\r\n")
        
        val request = sb.toString()
        Log.d(TAG, "OPTIONS request:\n$request")

        rtspWriter?.print(request)
        rtspWriter?.flush()

        val response = parseRtspResponse()
        Log.d(TAG, "OPTIONS response: code=${response?.first}, headers=${response?.second}")
        // Some servers return 200, some return 501 (not implemented) but still work
        return response != null && (response.first == 200 || response.first == 501)
    }

    private fun generateAppleChallenge(): String {
        val bytes = ByteArray(16)
        Random.nextBytes(bytes)
        return Base64.getEncoder().encodeToString(bytes)
    }

    /**
     * ANNOUNCE - Describe the audio format to the server
     */
    private fun announce(): Boolean {
        // Generate encryption keys
        generateKeys()
        val rsaAesKey = encryptRsaAesKey()
        if (rsaAesKey == null) {
            logE("Failed to encrypt AES key")
            return false
        }
        val aesIvBase64 = Base64.getEncoder().encodeToString(aesIv)

        val localIp = rtspSocket?.localAddress?.hostAddress ?: "0.0.0.0"
        val sdp = buildSdp(localIp, rsaAesKey, aesIvBase64)

        logD("SDP content:\n$sdp")

        // Generate Apple-Challenge for authentication
        val challenge = generateAppleChallenge()
        
        val request = buildRtspRequest(
            "ANNOUNCE",
            mapOf(
                "Content-Type" to "application/sdp",
                "Content-Length" to sdp.toByteArray().size.toString(),
                "Apple-Challenge" to challenge
            ),
            sdp
        )
        logD("ANNOUNCE request:\n$request")

        rtspWriter?.print(request)
        rtspWriter?.flush()

        val response = parseRtspResponse()
        logD("ANNOUNCE response: code=${response?.first}, headers=${response?.second}")
        return response?.first == 200
    }


    /**
     * Diagnostic: Test OPTIONS request
     */
    private fun testOptions(): Boolean {
        try {
            val challenge = generateAppleChallenge()
            
            val sb = StringBuilder()
            sb.append("OPTIONS * RTSP/1.0\r\n")
            sb.append("CSeq: ${cSeq.incrementAndGet()}\r\n")
            sb.append("User-Agent: iTunes/10.6 (Windows; N)\r\n")
            sb.append("Client-Instance: $clientInstance\r\n")
            sb.append("DACP-ID: $dacpId\r\n")
            sb.append("Active-Remote: $activeRemote\r\n")
            sb.append("Apple-Challenge: $challenge\r\n")
            sb.append("\r\n")
            
            val request = sb.toString()
            logD("Diagnostic OPTIONS request:\n$request")


            rtspWriter?.print(request)
            rtspWriter?.flush()

            val response = parseRtspResponse()
            logD("Diagnostic OPTIONS response: code=${response?.first}")
            response?.second?.forEach { (key, value) ->
                logD("Header: $key = $value")
            }
            return response != null
        } catch (e: Exception) {
            logE("Diagnostic OPTIONS failed: ${e.message}")
            return false
        }
    }

    /**
     * Generate random AES key and IV
     */
    private fun generateKeys() {
        aesKey = ByteArray(16)
        aesIv = ByteArray(16)
        Random.nextBytes(aesKey!!)
        Random.nextBytes(aesIv!!)
    }

    /**
     * Encrypt the AES key with standard AirPlay RSA Public Key
     */
    private fun encryptRsaAesKey(): String? {
        try {
            val modulusBytes = Base64.getDecoder().decode(RSA_MODULUS)
            val exponentBytes = Base64.getDecoder().decode(RSA_EXPONENT)
            
            val modulus = BigInteger(1, modulusBytes)
            val exponent = BigInteger(1, exponentBytes)
            
            val spec = RSAPublicKeySpec(modulus, exponent)
            val factory = KeyFactory.getInstance("RSA")
            val publicKey = factory.generatePublic(spec)
            
            // Try OAEP padding - shairport-sync may expect this
            val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding")
            cipher.init(Cipher.ENCRYPT_MODE, publicKey)
            
            val encryptedKey = cipher.doFinal(aesKey)
            return Base64.getEncoder().encodeToString(encryptedKey)
        } catch (e: Exception) {
            logE("RSA Encryption failed: ${e.message}")
            return null
        }
    }

    /**
     * SETUP - Configure the streaming ports
     */

    private fun setup(): Boolean {
        val localControlPort = controlSocket?.localPort ?: return false
        val localTimingPort = timingSocket?.localPort ?: return false
        
        Log.d(TAG, "SETUP with control_port=$localControlPort, timing_port=$localTimingPort")

        // Start timing responder thread
        startTimingResponder()

        val request = buildRtspRequest(
            "SETUP",
            mapOf(
                "Transport" to "RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;control_port=$localControlPort;timing_port=$localTimingPort"
            )
        )
        logD("SETUP request:\n$request")

        rtspWriter?.print(request)
        rtspWriter?.flush()

        val response = parseRtspResponse()
        logD("SETUP response: code=${response?.first}")
        
        if (response != null && response.first == 200) {
            // Parse Transport header for server ports
            val transportHeader = response.second["Transport"] ?: return false
            parseTransportHeader(transportHeader)
            logD("SETUP succeeded - serverPort=$serverPort")

            // Parse Session header
            val sessionVal = response.second["Session"]
            if (sessionVal != null) {
                serverSessionId = sessionVal.split(";")[0].trim()
                logD("Captured server session ID: $serverSessionId")
            }
            return true
        }
        return false
    }

    private fun startTimingResponder() {
        val socket = timingSocket ?: return
        logD("startTimingResponder called. Socket port: ${socket.localPort}")
        isTimingRunning.set(true)
        Thread {
            logD("Timing thread started execution")
            val buffer = ByteArray(128)
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                while (isTimingRunning.get() && !socket.isClosed) {
                    socket.receive(packet)
                    // logD("Timing Packet Received! Length: ${packet.length}") // Verbose
                    if (packet.length >= 32) { 
                        val req = packet.data
                        val response = ByteArray(packet.length)
                        
                        // Copy header (first 8 bytes)
                        System.arraycopy(req, 0, response, 0, 8)
                        
                        // Set Type to Reply (0x53 | 0x80 = 0xD3)
                        response[1] = (0x53 or 0x80).toByte()
                        
                        // NTP Logic: Copy T_xmit (24-31) to T_orig (8-15)
                        System.arraycopy(req, 24, response, 8, 8)
                        
                        // Current time
                        val now = System.currentTimeMillis()
                        val ntpSec = (now / 1000) + 2208988800L
                        val ntpFrac = ((now % 1000) * 4294967296.0 / 1000.0).toLong()
                        
                        // Write T_recv (16-23) and T_xmit (24-31)
                        writeNtpTimestamp(response, 16, ntpSec, ntpFrac)
                        writeNtpTimestamp(response, 24, ntpSec, ntpFrac)

                        val reply = DatagramPacket(response, packet.length, packet.address, packet.port)
                        socket.send(reply)
                    }
                }
            } catch (e: Exception) {
                if (isTimingRunning.get()) {
                    logE("Timing thread error: ${e.message}")
                }
            }
            logD("Timing thread finished")
        }.start()
    }
    
    private fun writeNtpTimestamp(buffer: ByteArray, offset: Int, seconds: Long, fraction: Long) {
        // Seconds (32-bit big endian)
        buffer[offset] = (seconds shr 24).toByte()
        buffer[offset+1] = (seconds shr 16).toByte()
        buffer[offset+2] = (seconds shr 8).toByte()
        buffer[offset+3] = (seconds).toByte()
        
        // Fraction (32-bit big endian)
        buffer[offset+4] = (fraction shr 24).toByte()
        buffer[offset+5] = (fraction shr 16).toByte()
        buffer[offset+6] = (fraction shr 8).toByte()
        buffer[offset+7] = (fraction).toByte()
    }


    
    // ...

    /**
     * RECORD - Start the streaming session
     */
    private fun record(): Boolean {
        // Ensure Content-Length is STRICTLY omitted from header map
        val headerMap = mapOf(
            "Range" to "npt=0-",
            "RTP-Info" to "seq=$rtpSequence;rtptime=$rtpTimestamp"
        )
        
        val request = buildRtspRequest(
            "RECORD",
            headerMap,
            body = "",
            sessionId = serverSessionId 
        )
        logD("RECORD request (Check for Content-Length):\n$request")

        rtspWriter?.print(request)
        rtspWriter?.flush()

        val response = parseRtspResponse()
        logD("RECORD response: code=${response?.first}")
        
        if (response?.first == 200) {
            isStreaming.set(true)
            return true
        }
        return false
    }

    // Audio buffer for PCM data
    private val audioBuffer = java.io.ByteArrayOutputStream()
    private val PACKET_SIZE = 1408 // 352 frames * 4 bytes
    private var debugPacketCount = 0

    /**
     * Stream PCM audio data
     * @param pcmData PCM audio samples (16-bit stereo, 44100Hz, Little Endian)
     */
    suspend fun streamAudio(pcmData: ByteArray) = withContext(Dispatchers.IO) {
        if (!isStreaming.get() || audioSocket == null) return@withContext

        try {
            // Append new data to buffer
            synchronized(audioBuffer) {
                audioBuffer.write(pcmData)
            }

            // Process full packets
            val bufferBytes = synchronized(audioBuffer) { audioBuffer.toByteArray() }
            if (bufferBytes.size >= PACKET_SIZE) {
                var offset = 0
                while (offset + PACKET_SIZE <= bufferBytes.size) {
                    val chunk = bufferBytes.copyOfRange(offset, offset + PACKET_SIZE)
                    
                    // Debug Logging: Calculate RMS periodically
                    debugPacketCount++
                    if (debugPacketCount % 50 == 0) {
                        var sum = 0.0
                        val samples = chunk.size / 2 // 16-bit samples
                        for (i in 0 until chunk.size step 2) {
                             val sample = ((chunk[i+1].toInt() shl 8) or (chunk[i].toInt() and 0xFF)).toShort()
                             sum += sample.toDouble() * sample.toDouble()
                        }
                        val rms = Math.sqrt(sum / samples).toInt()
                        LogServer.log("SND: Pkt $rtpSequence, RMS=$rms (Max 32767), Vol=${Math.round(20 * Math.log10(rms.toDouble()))}dB")
                    }

                    // Process chunk: Send Little Endian (Native) - Do NOT swap
                    val leData = chunk.copyOf()
                    
                    // Send Unencrypted L16 (Native LE)
                    // We removed a=rsaaeskey from SDP, so shairport should accept cleartext
                    val rtpPacket = buildRtpPacket(leData)

                    // Send to server
                    val address = InetAddress.getByName(host)
                    val packet = DatagramPacket(rtpPacket, rtpPacket.size, address, serverPort)
                    audioSocket?.send(packet)

                    // Update sequence and timestamp
                    rtpSequence = (rtpSequence + 1) and 0xFFFF
                    rtpTimestamp += FRAMES_PER_PACKET
                    
                    offset += PACKET_SIZE
                }
                
                // Keep remaining bytes
                synchronized(audioBuffer) {
                    audioBuffer.reset()
                    if (offset < bufferBytes.size) {
                        val remaining = bufferBytes.copyOfRange(offset, bufferBytes.size)
                        audioBuffer.write(remaining)
                    }
                }
            }
        } catch (e: Exception) {
            callback?.onError("Streaming error: ${e.message}")
        }
    }

    /**
     * Set volume (0.0 to 1.0)
     */
    suspend fun setVolume(volume: Float): Boolean = withContext(Dispatchers.IO) {
        if (!isConnected.get()) return@withContext false

        // AirPlay volume is in dB, -144 (mute) to 0 (max)
        val dbVolume = if (volume <= 0f) -144f else (volume * 30f - 30f)
        val volumeStr = "volume: $dbVolume\r\n"

        val request = buildRtspRequest(
            "SET_PARAMETER",
            mapOf(
                "Content-Type" to "text/parameters",
                "Content-Length" to volumeStr.length.toString()
            ),
            volumeStr
        )

        rtspWriter?.print(request)
        rtspWriter?.flush()

        parseRtspResponse()?.first == 200
    }

    /**
     * Disconnect from the speaker
     */
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        if (isConnected.get()) {
            try {
                val request = buildRtspRequest("TEARDOWN")
                rtspWriter?.print(request)
                rtspWriter?.flush()
                parseRtspResponse()
            } catch (_: Exception) { }
        }

        isTimingRunning.set(false)
        isStreaming.set(false)
        isConnected.set(false)

        rtspSocket?.close()
        rtspSocket = null
        rtspReader = null
        rtspWriter = null

        audioSocket?.close()
        audioSocket = null
        controlSocket?.close()
        controlSocket = null
        timingSocket?.close()
        timingSocket = null

        callback?.onDisconnected()
    }

    private fun buildRtspRequest(
        method: String,
        headers: Map<String, String> = emptyMap(),
        body: String = "",
        sessionId: String? = null
    ): String {
        val sb = StringBuilder()
        // URL format per AirPlay spec: rtsp://host/sessionId (no port in URL)
        sb.append("$method rtsp://$host/$localSessionId RTSP/1.0\r\n")
        sb.append("CSeq: ${cSeq.incrementAndGet()}\r\n")
        sb.append("User-Agent: $USER_AGENT\r\n")
        sb.append("Client-Instance: $clientInstance\r\n")
        sb.append("DACP-ID: $dacpId\r\n")
        sb.append("Active-Remote: $activeRemote\r\n")

        if (sessionId != null) {
            sb.append("Session: $sessionId\r\n")
        }

        headers.forEach { (key, value) ->
            sb.append("$key: $value\r\n")
        }

        sb.append("\r\n")
        sb.append(body)

        return sb.toString()
    }

    private fun parseRtspResponse(): Pair<Int, Map<String, String>>? {
        val headers = mutableMapOf<String, String>()

        val statusLine = rtspReader?.readLine() ?: return null
        val statusCode = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: return null

        while (true) {
            val line = rtspReader?.readLine() ?: break
            if (line.isEmpty()) break

            val colonIndex = line.indexOf(':')
            if (colonIndex > 0) {
                val key = line.substring(0, colonIndex).trim()
                val value = line.substring(colonIndex + 1).trim()
                headers[key] = value
            }
        }

        return statusCode to headers
    }

    private fun parseTransportHeader(transport: String) {
        transport.split(";").forEach { part ->
            when {
                part.startsWith("server_port=") -> {
                    serverPort = part.substringAfter("=").toIntOrNull() ?: 0
                }
                part.startsWith("control_port=") -> {
                    controlPort = part.substringAfter("=").toIntOrNull() ?: 0
                }
                part.startsWith("timing_port=") -> {
                    timingPort = part.substringAfter("=").toIntOrNull() ?: 0
                }
            }
        }
    }

    private fun buildSdp(localIp: String, rsaAesKey: String, aesIv: String): String {
        return """
v=0
o=iTunes $localSessionId 0 IN IP4 $localIp
s=iTunes
c=IN IP4 $host
t=0 0
m=audio 0 RTP/AVP 96
a=rtpmap:96 L16/44100/2
""".trimIndent().replace("\n", "\r\n")
    }

    private fun buildRtpPacket(data: ByteArray): ByteArray {
        val header = ByteArray(12)

        // RTP header
        header[0] = 0x80.toByte() // Version 2
        header[1] = 0x60.toByte() // Payload type 96

        // Sequence number (big endian)
        header[2] = (rtpSequence shr 8).toByte()
        header[3] = rtpSequence.toByte()

        // Timestamp (big endian)
        header[4] = (rtpTimestamp shr 24).toByte()
        header[5] = (rtpTimestamp shr 16).toByte()
        header[6] = (rtpTimestamp shr 8).toByte()
        header[7] = rtpTimestamp.toByte()

        // SSRC (big endian)
        header[8] = (ssrc shr 24).toByte()
        header[9] = (ssrc shr 16).toByte()
        header[10] = (ssrc shr 8).toByte()
        header[11] = ssrc.toByte()

        return header + data
    }

    private fun generateSessionId(): String {
        return Random.nextLong(0, Long.MAX_VALUE).toString()
    }

    private fun generateClientId(): String {
        val bytes = ByteArray(8)
        Random.nextBytes(bytes)
        return bytes.joinToString("") { "%02X".format(it) }
    }
    
    private fun generateHexId(bytes: Int): String {
        val data = ByteArray(bytes)
        Random.nextBytes(data)
        return data.joinToString("") { "%02X".format(it) }
    }

    fun isConnected(): Boolean = isConnected.get()
    fun isStreaming(): Boolean = isStreaming.get()
    
    // Logging helpers - log to both Android logcat and web LogServer
    private fun logD(msg: String) {
        Log.d(TAG, msg)
        LogServer.d(TAG, msg)
    }
    
    private fun logE(msg: String) {
        Log.e(TAG, msg)
        LogServer.e(TAG, msg)
    }
}
