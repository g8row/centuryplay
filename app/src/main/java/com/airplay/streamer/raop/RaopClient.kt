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
    // Helper to run the sync packet thread
    private var isSyncRunning = AtomicBoolean(false)
    // Helper to run connection health monitor
    private var isHealthMonitorRunning = AtomicBoolean(false)
    private var syncSequence = 0
    
    private val HEALTH_CHECK_INTERVAL_MS = 3000L  // Check every 3 seconds

    private val cSeq = AtomicInteger(0)
    private var sessionId: String? = null
    private var serverSessionId: String? = null
    private val localSessionId: String = Random.nextLong(0, Long.MAX_VALUE).toString()
    private var serverPort: Int = 0
    private var serverControlPort: Int = 0  // Server's control port for sync packets
    private var serverTimingPort: Int = 0   // Server's timing port
    private var controlPort: Int = 0
    private var timingPort: Int = 0

    private val isConnected = AtomicBoolean(false)
    private val isStreaming = AtomicBoolean(false)

    private var rtpSequence: Int = Random.nextInt(0xFFFF)
    private var rtpTimestamp: Long = Random.nextLong(0xFFFFFFFFL)
    private val ssrc: Int = Random.nextInt()

    // Audio buffer for PCM data
    private val alacEncoder = AlacEncoder()

    // Apple's RSA Public Key for AirPlay (2048-bit) - from shairport-sync's super_secret_key
    // This is the correct key that shairport-sync and other receivers use
    private val RSA_MODULUS = "59dE8qLieItsH1WgjrcFRKj6eUWqi+bGLOX1HL3U3GhC/j0Qg90u3sG/1CUtwC" +
            "5vOYvfDmFI6oSFXi5ELabWJmT2dKHzBJKa3k9ok+8t9ucRqMd6DZHJ2YCCLlDR" +
            "KSKv6kDqnw4UwPdpOMXziC/AMj3Z/lUVX1G7WSHCAWKf1zNS1eLvqr+boEjXuB" +
            "OitnZ/bDzPHrTOZz0Dew0uowxf/+sG+NCK3eQJVxqcaJ/vEHKIVd2M+5qL71yJ" +
            "Q+87X6oV3eaYvt3zWZYD6z5vYTcrtij2VZ9Zmni/UAaHqn9JdsBWLUEpVviYnh" +
            "imNVvYFZeCXg/IdTQ+x4IRdiXNv5hEew=="
    private val RSA_EXPONENT = "AQAB"

    private var aesKey: ByteArray? = null
    private var aesIv: ByteArray? = null
    private var aesCipher: Cipher? = null

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
     * Generate random AES key and IV, and initialize the cipher
     */
    private fun generateKeys() {
        aesKey = ByteArray(16)
        aesIv = ByteArray(16)
        Random.nextBytes(aesKey!!)
        Random.nextBytes(aesIv!!)
        
        // Initialize AES-128-CBC cipher for audio encryption
        try {
            aesCipher = Cipher.getInstance("AES/CBC/NoPadding")
            val keySpec = SecretKeySpec(aesKey, "AES")
            val ivSpec = IvParameterSpec(aesIv)
            aesCipher?.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
            logD("AES cipher initialized successfully")
        } catch (e: Exception) {
            logE("Failed to initialize AES cipher: ${e.message}")
            aesCipher = null
        }
    }

    /**
     * Encrypt the AES key with Apple's standard AirPlay RSA Public Key
     * shairport-sync uses RSA-OAEP with SHA-1 for decryption
     */
    private fun encryptRsaAesKey(): String? {
        try {
            // Decode the modulus and exponent from base64
            val modulusBytes = Base64.getDecoder().decode(RSA_MODULUS)
            val exponentBytes = Base64.getDecoder().decode(RSA_EXPONENT)
            
            val modulus = BigInteger(1, modulusBytes)
            val exponent = BigInteger(1, exponentBytes)
            
            val spec = RSAPublicKeySpec(modulus, exponent)
            val factory = KeyFactory.getInstance("RSA")
            val publicKey = factory.generatePublic(spec)
            
            // shairport-sync expects RSA-OAEP with SHA-1 (not PKCS1 v1.5)
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
            var timingPacketCount = 0
            try {
                while (isTimingRunning.get() && !socket.isClosed) {
                    socket.receive(packet)
                    timingPacketCount++
                    
                    // Log packet details for debugging
                    if (timingPacketCount <= 5 || timingPacketCount % 100 == 0) {
                        val req = packet.data
                        val type = req[1].toInt() and 0xFF
                        logD("Timing Packet #$timingPacketCount: type=0x${type.toString(16)}, len=${packet.length}, from=${packet.address}:${packet.port}")
                    }
                    
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
                        
                        if (timingPacketCount <= 5) {
                            logD("Timing Response #$timingPacketCount sent: sec=$ntpSec, frac=$ntpFrac")
                        }
                    }
                }
            } catch (e: Exception) {
                if (isTimingRunning.get()) {
                    logE("Timing thread error: ${e.message}")
                    // If timing fails and we're still supposed to be connected, trigger disconnect
                    if (isConnected.get()) {
                        logE("Timing socket closed unexpectedly - server may have disconnected")
                        handleServerDisconnect()
                    }
                }
            }
            logD("Timing thread finished. Total packets handled: $timingPacketCount")
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

    /**
     * Start sync packet sender - sends RTP timestamp to NTP time mapping to the receiver
     * This is essential for the receiver to know when to play audio frames
     */
    private fun startSyncSender() {
        val socket = controlSocket ?: return
        if (serverControlPort == 0) {
            logE("Cannot start sync sender - serverControlPort is 0")
            return
        }
        
        logD("Starting sync sender to $host:$serverControlPort")
        isSyncRunning.set(true)
        syncSequence = 0
        
        // Record the starting point: this RTP timestamp corresponds to this NTP time + latency
        // Latency of ~2.5 seconds (110250 samples) gives the receiver time to buffer
        val latencyMs = 2500L
        val latencySamples = (latencyMs * SAMPLE_RATE / 1000)
        syncStartRtpTimestamp = rtpTimestamp
        syncStartTimeMs = System.currentTimeMillis()
        
        logD("Sync timing established: rtpTimestamp=$syncStartRtpTimestamp at time=$syncStartTimeMs, latency=${latencyMs}ms")
        
        Thread {
            try {
                val address = InetAddress.getByName(host)
                var lastSyncTime = 0L
                
                while (isSyncRunning.get() && !socket.isClosed) {
                    val now = System.currentTimeMillis()
                    
                    // Send sync packet every 300ms
                    if (now - lastSyncTime >= 300) {
                        // Calculate what RTP timestamp corresponds to NOW + latency
                        // elapsed = time since we started
                        val elapsedMs = now - syncStartTimeMs
                        // The RTP timestamp that should play at (now + latency) is:
                        // startRtp + elapsed_samples
                        val elapsedSamples = (elapsedMs * SAMPLE_RATE / 1000)
                        val currentPlayRtp = syncStartRtpTimestamp + elapsedSamples
                        
                        // The NTP time for this RTP timestamp is: now + latency
                        val playTimeMs = now + latencyMs
                        
                        // Build sync packet
                        val syncPacket = buildSyncPacket(currentPlayRtp, playTimeMs, latencySamples)
                        val packet = DatagramPacket(syncPacket, syncPacket.size, address, serverControlPort)
                        socket.send(packet)
                        
                        syncSequence++
                        if (syncSequence <= 5 || syncSequence % 20 == 0) {
                            logD("Sync #$syncSequence: playRtp=$currentPlayRtp, playTime=$playTimeMs, currentRtp=$rtpTimestamp")
                        }
                        lastSyncTime = now
                    }
                    
                    Thread.sleep(50) // Check every 50ms
                }
            } catch (e: Exception) {
                if (isSyncRunning.get()) {
                    logE("Sync sender error: ${e.message}")
                }
            }
            logD("Sync sender finished")
        }.start()
    }
    
    // Sync timing variables
    private var syncStartRtpTimestamp: Long = 0
    private var syncStartTimeMs: Long = 0
    
    /**
     * Build an AirPlay sync/control packet
     * Format (20 bytes):
     * - byte 0: 0x80 (RTP version 2) or 0x90 (with extension, for first sync)
     * - byte 1: 0xd4 (payload type 84 = sync)
     * - bytes 2-3: sequence number (big-endian)
     * - bytes 4-7: RTP timestamp that should play at the given NTP time (big-endian)
     * - bytes 8-15: NTP timestamp when the RTP audio should play (64-bit big-endian)
     * - bytes 16-19: RTP timestamp + latency for next sync point (big-endian)
     */
    private fun buildSyncPacket(playRtp: Long, playTimeMs: Long, latencySamples: Long): ByteArray {
        val packet = ByteArray(20)
        
        // Header: version 2, with extension bit for first packet
        packet[0] = if (syncSequence == 0) 0x90.toByte() else 0x80.toByte()
        packet[1] = 0xd4.toByte()  // Payload type 84 (sync)
        
        // Sequence number (big-endian)
        packet[2] = (syncSequence shr 8).toByte()
        packet[3] = syncSequence.toByte()
        
        // RTP timestamp that should play at the NTP time below (big-endian)
        packet[4] = (playRtp shr 24).toByte()
        packet[5] = (playRtp shr 16).toByte()
        packet[6] = (playRtp shr 8).toByte()
        packet[7] = playRtp.toByte()
        
        // NTP timestamp when playRtp should be played (64-bit big-endian)
        val ntpSec = (playTimeMs / 1000) + 2208988800L
        val ntpFrac = ((playTimeMs % 1000) * 4294967296.0 / 1000.0).toLong()
        writeNtpTimestamp(packet, 8, ntpSec, ntpFrac)
        
        // Next RTP timestamp (play point + latency)
        val nextRtp = playRtp + latencySamples
        packet[16] = (nextRtp shr 24).toByte()
        packet[17] = (nextRtp shr 16).toByte()
        packet[18] = (nextRtp shr 8).toByte()
        packet[19] = nextRtp.toByte()
        
        return packet
    }
    
    private fun stopSyncSender() {
        isSyncRunning.set(false)
    }
    
    /**
     * Start connection health monitor
     * Monitors the TCP RTSP socket to detect if the server has disconnected
     */
    private fun startHealthMonitor() {
        logD("Starting connection health monitor")
        isHealthMonitorRunning.set(true)
        
        Thread {
            try {
                val socket = rtspSocket
                val reader = rtspReader
                
                while (isHealthMonitorRunning.get() && isConnected.get()) {
                    Thread.sleep(HEALTH_CHECK_INTERVAL_MS)
                    
                    if (!isHealthMonitorRunning.get() || !isConnected.get()) break
                    
                    // Check if TCP socket is still valid
                    val currentSocket = rtspSocket
                    if (currentSocket == null || currentSocket.isClosed || !currentSocket.isConnected) {
                        logE("Health check: RTSP socket is closed/disconnected")
                        handleServerDisconnect()
                        break
                    }
                    
                    // Try to check if socket is still alive by checking input stream
                    // If server closed connection, socket.isConnected() may still return true
                    // but reading will fail
                    try {
                        currentSocket.soTimeout = 100  // Very short timeout
                        // Check if there's data or if the connection is closed
                        // If the server closed, this will throw or return -1
                        val inputStream = currentSocket.getInputStream()
                        val available = inputStream.available()
                        // If available > 0, there's unexpected data (server sent something)
                        // That's actually fine, we just want to make sure the socket is alive
                        logD("Health check: socket OK, available=$available")
                    } catch (e: java.net.SocketTimeoutException) {
                        // Timeout is expected and fine - means socket is still alive
                        logD("Health check: socket OK (timeout expected)")
                    } catch (e: java.io.IOException) {
                        logE("Health check: socket read failed - ${e.message}")
                        handleServerDisconnect()
                        break
                    } finally {
                        // Restore original timeout
                        try { currentSocket.soTimeout = 10000 } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                if (isHealthMonitorRunning.get()) {
                    logE("Health monitor error: ${e.message}")
                }
            }
            logD("Health monitor finished")
        }.start()
    }
    
    private fun stopHealthMonitor() {
        isHealthMonitorRunning.set(false)
    }
    
    /**
     * Handle unexpected server disconnect
     * Called when we detect the server has stopped responding
     */
    private fun handleServerDisconnect() {
        if (!isConnected.get()) return  // Already disconnected
        
        logE("Server disconnect detected - cleaning up")
        
        // Set flags immediately
        isStreaming.set(false)
        isConnected.set(false)
        
        // Stop all background threads
        stopHealthMonitor()
        stopSyncSender()
        stopTimingResponder()
        
        // Close sockets (don't wait for TEARDOWN since server is gone)
        try { audioSocket?.close() } catch (_: Exception) {}
        audioSocket = null
        try { controlSocket?.close() } catch (_: Exception) {}
        controlSocket = null
        try { timingSocket?.close() } catch (_: Exception) {}
        timingSocket = null
        try { rtspWriter?.close() } catch (_: Exception) {}
        rtspWriter = null
        try { rtspReader?.close() } catch (_: Exception) {}
        rtspReader = null
        try { rtspSocket?.close() } catch (_: Exception) {}
        rtspSocket = null
        
        // Reset state
        serverPort = 0
        serverControlPort = 0
        serverTimingPort = 0
        serverSessionId = null
        
        // Clear audio buffer
        synchronized(audioBuffer) {
            audioBuffer.reset()
        }
        
        logD("Server disconnect cleanup complete")
        callback?.onError("Server disconnected")
        callback?.onDisconnected()
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
            // Start sending sync packets to tell receiver when to play audio
            startSyncSender()
            // Start connection health monitor
            startHealthMonitor()
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

                    // Convert PCM from little-endian to big-endian (network byte order)
                    // L16 format (RFC 3551) requires big-endian (network byte order)
                    val beData = swapEndianness(chunk)
                    
                    // Encrypt audio data with AES-128-CBC (only if encryption is enabled)
                    val payloadData = if (useEncryption) {
                        encryptAudio(beData)
                    } else {
                        beData  // Send unencrypted for testing
                    }
                    
                    // Build RTP packet with payload
                    val rtpPacket = buildRtpPacket(payloadData)

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
     * Disconnect from the speaker - robust teardown with proper cleanup
     * This ensures all threads are stopped, all sockets are closed, and TEARDOWN is sent
     */
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        logD("disconnect() called - starting robust teardown")
        
        // First, stop all flags to signal threads to exit
        val wasStreaming = isStreaming.getAndSet(false)
        val wasConnected = isConnected.getAndSet(false)
        
        // Stop background threads immediately
        logD("Stopping health monitor...")
        stopHealthMonitor()
        
        logD("Stopping sync sender...")
        stopSyncSender()
        
        logD("Stopping timing responder...")
        stopTimingResponder()
        
        // Give threads a moment to notice the flags
        try { Thread.sleep(100) } catch (_: Exception) {}
        
        // Send TEARDOWN if we were connected (with timeout)
        if (wasConnected) {
            try {
                logD("Sending TEARDOWN request...")
                rtspSocket?.soTimeout = 2000  // 2 second timeout for teardown
                val request = buildRtspRequest("TEARDOWN", sessionId = serverSessionId)
                rtspWriter?.print(request)
                rtspWriter?.flush()
                val response = parseRtspResponse()
                logD("TEARDOWN response: ${response?.first}")
            } catch (e: Exception) {
                logD("TEARDOWN failed (expected if connection lost): ${e.message}")
            }
        }
        
        // Close RTSP connection
        logD("Closing RTSP socket...")
        try {
            rtspWriter?.close()
        } catch (_: Exception) {}
        rtspWriter = null
        
        try {
            rtspReader?.close()
        } catch (_: Exception) {}
        rtspReader = null
        
        try {
            rtspSocket?.close()
        } catch (_: Exception) {}
        rtspSocket = null
        
        // Close UDP sockets (this will unblock any blocking receive() calls)
        logD("Closing UDP sockets...")
        try {
            audioSocket?.close()
        } catch (_: Exception) {}
        audioSocket = null
        
        try {
            controlSocket?.close()
        } catch (_: Exception) {}
        controlSocket = null
        
        try {
            timingSocket?.close()
        } catch (_: Exception) {}
        timingSocket = null
        
        // Reset state
        serverPort = 0
        serverControlPort = 0
        serverTimingPort = 0
        serverSessionId = null
        syncSequence = 0
        syncStartRtpTimestamp = 0
        syncStartTimeMs = 0
        
        // Clear audio buffer
        synchronized(audioBuffer) {
            audioBuffer.reset()
        }
        
        logD("Teardown complete")
        callback?.onDisconnected()
    }
    
    /**
     * Stop the timing responder thread safely
     */
    private fun stopTimingResponder() {
        isTimingRunning.set(false)
        // Socket close will interrupt the blocking receive()
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
            val trimmedPart = part.trim()
            when {
                trimmedPart.startsWith("server_port=") -> {
                    serverPort = trimmedPart.substringAfter("=").toIntOrNull() ?: 0
                    logD("Parsed server_port (audio): $serverPort")
                }
                trimmedPart.startsWith("control_port=") -> {
                    serverControlPort = trimmedPart.substringAfter("=").toIntOrNull() ?: 0
                    logD("Parsed control_port (server): $serverControlPort")
                }
                trimmedPart.startsWith("timing_port=") -> {
                    serverTimingPort = trimmedPart.substringAfter("=").toIntOrNull() ?: 0
                    logD("Parsed timing_port (server): $serverTimingPort")
                }
            }
        }
    }

    // Encryption is required for proper AirPlay compatibility
    // When true, audio is encrypted with AES-128-CBC and keys are exchanged via RSA
    private var useEncryption = true
    
    private fun buildSdp(localIp: String, rsaAesKey: String, aesIv: String): String {
        val baseSdp = """
v=0
o=iTunes $localSessionId 0 IN IP4 $localIp
s=iTunes
c=IN IP4 $host
t=0 0
m=audio 0 RTP/AVP 96
a=rtpmap:96 L16/44100/2"""
        
        // Only include encryption parameters if encryption is enabled
        return if (useEncryption) {
            """
$baseSdp
a=rsaaeskey:$rsaAesKey
a=aesiv:$aesIv
""".trimIndent().replace("\n", "\r\n")
        } else {
            logD("ENCRYPTION DISABLED - streaming unencrypted L16 audio")
            baseSdp.trimIndent().replace("\n", "\r\n")
        }
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

    /**
     * Swap byte order for 16-bit PCM samples (little-endian to big-endian)
     * L16 format (RFC 3551) requires network byte order (big-endian)
     */
    private fun swapEndianness(data: ByteArray): ByteArray {
        val result = ByteArray(data.size)
        for (i in 0 until data.size step 2) {
            if (i + 1 < data.size) {
                result[i] = data[i + 1]
                result[i + 1] = data[i]
            }
        }
        return result
    }

    /**
     * Encrypt audio data using AES-128-CBC
     * AirPlay uses a modified CBC mode where only complete 16-byte blocks are encrypted,
     * and the remaining bytes (< 16) are left unencrypted at the end.
     * Each packet uses a fresh cipher starting from the original IV.
     */
    private fun encryptAudio(data: ByteArray): ByteArray {
        val key = aesKey ?: return data
        val iv = aesIv ?: return data
        
        try {
            // Create a fresh cipher for each packet with the original IV
            // AirPlay resets the IV for each packet (unlike standard CBC chaining)
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            val keySpec = SecretKeySpec(key, "AES")
            val ivSpec = IvParameterSpec(iv)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
            
            // Only encrypt complete 16-byte blocks
            val blockSize = 16
            val numCompleteBlocks = data.size / blockSize
            val encryptedSize = numCompleteBlocks * blockSize
            val remainingBytes = data.size - encryptedSize
            
            if (encryptedSize == 0) {
                // No complete blocks to encrypt, return data as-is
                return data
            }
            
            // Encrypt the complete blocks
            val toEncrypt = data.copyOfRange(0, encryptedSize)
            val encrypted = cipher.doFinal(toEncrypt)
            
            // Combine encrypted blocks with unencrypted remainder
            return if (remainingBytes > 0) {
                val remainder = data.copyOfRange(encryptedSize, data.size)
                encrypted + remainder
            } else {
                encrypted
            }
        } catch (e: Exception) {
            logE("Audio encryption failed: ${e.message}")
            return data // Fall back to unencrypted on error
        }
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
