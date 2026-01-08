package com.airplay.streamer.raop

import android.util.Log
import com.airplay.streamer.discovery.AirPlayDevice
import com.airplay.streamer.util.BinaryPlist
import com.airplay.streamer.util.LogServer
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sin

/**
 * AirPlay 2 Client - Complete Implementation
 * 
 * Protocol flow:
 * 1. POST /pair-pin-start (optional for transient)
 * 2. POST /pair-setup M1 -> M2 (SRP start)
 * 3. POST /pair-setup M3 -> M4 (SRP exchange)
 * 4. Enable HAP session encryption on control channel
 * 5. SETUP rtsp://host/uuid (event channel with PTP timing)
 * 6. SETUP rtsp://host/uuid (audio stream with shk)
 * 7. RECORD rtsp://host/uuid
 * 8. Send encrypted ALAC audio over UDP
 */
class AirPlay2Client(private val device: AirPlayDevice) {

    companion object {
        private const val TAG = "AirPlay2Client"
        private const val USER_AGENT = "AirPlay/320.20"
        
        // Audio parameters
        private const val SAMPLE_RATE = 44100
        private const val SAMPLES_PER_FRAME = 352
        private const val CHANNELS = 2
        private const val BITS_PER_SAMPLE = 16
    }

    // Connection state
    private var socket: Socket? = null
    private var outputStream: BufferedOutputStream? = null
    private var inputStream: BufferedInputStream? = null
    private val isConnected = AtomicBoolean(false)
    private val isPaired = AtomicBoolean(false)
    private val isStreaming = AtomicBoolean(false)
    
    // RTSP sequence number
    private var cseq = 0
    
    // Session identifiers
    private var sessionUuid: String? = null
    
    // Auth helper for SRP-6a
    private val auth = AirPlayAuth()
    
    // HAP session encryption (for control channel after pairing)
    private var hapSession: HapSession? = null
    
    // Audio streaming state
    private var audioSocket: DatagramSocket? = null
    private var audioDataPort: Int = 0
    private var audioControlPort: Int = 0
    private var audioSharedKey: ByteArray? = null
    
    // Streaming counters
    private var sequenceNumber: Int = 0
    private var timestamp: Long = 0
    
    // Local IP for timing peer info
    private var localIp: String? = null

    // ==================== Connection Management ====================

    fun connect() {
        if (isConnected.get()) return
        
        Log.d(TAG, "Connecting to ${device.host}:${device.port} (AirPlay 2)...")
        LogServer.log("AirPlay 2: Connecting to ${device.host}:${device.port}")
        
        try {
            socket = Socket(device.host, device.port).apply {
                soTimeout = 10000
                tcpNoDelay = true
            }
            outputStream = BufferedOutputStream(socket!!.getOutputStream())
            inputStream = BufferedInputStream(socket!!.getInputStream())
            localIp = socket!!.localAddress.hostAddress
            isConnected.set(true)
            Log.d(TAG, "Connected. Local IP: $localIp")
            LogServer.log("AirPlay 2: Connected. Local IP: $localIp")
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed: ${e.message}")
            LogServer.log("AirPlay 2: Connection failed: ${e.message}")
            disconnect()
            throw e
        }
    }

    fun disconnect() {
        isStreaming.set(false)
        try {
            audioSocket?.close()
            socket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing sockets: ${e.message}")
        }
        audioSocket = null
        socket = null
        outputStream = null
        inputStream = null
        hapSession = null
        isConnected.set(false)
        isPaired.set(false)
        LogServer.log("AirPlay 2: Disconnected")
    }
    
    fun isConnected(): Boolean = isConnected.get()
    fun isPaired(): Boolean = isPaired.get()
    fun isStreaming(): Boolean = isStreaming.get()

    // ==================== Pairing (SRP-6a Transient) ====================

    /**
     * Perform transient pairing (no PIN entry required).
     * PIN is always "3939" for transient mode.
     */
    fun pair(transient: Boolean = true, pin: String = "3939") {
        if (!isConnected.get()) connect()
        
        val actualPin = if (transient) "3939" else pin
        Log.d(TAG, "Starting Pair-Setup (transient=$transient)")
        LogServer.log("AirPlay 2: Starting Pair-Setup (transient=$transient)")
        
        // Step 1: pair-pin-start (optional but helps)
        sendPairPinStart()
        
        // Step 2: M1 -> M2 (SRP start)
        val m1Data = auth.createPairSetupM1(transient)
        val m2Response = sendRtspRequest("POST", "/pair-setup", m1Data, "application/octet-stream")
        
        if (m2Response.code != 200) {
            throw Exception("Pair-Setup M1 failed: ${m2Response.code}")
        }
        LogServer.log("AirPlay 2: M1 OK, received M2 (${m2Response.body.size} bytes)")
        
        // Step 3: M3 -> M4 (SRP exchange)
        val m3Data = auth.parseM2AndGenerateM3(m2Response.body, actualPin)
        val m4Response = sendRtspRequest("POST", "/pair-setup", m3Data, "application/octet-stream")
        
        if (m4Response.code != 200) {
            throw Exception("Pair-Setup M3 failed: ${m4Response.code}")
        }
        LogServer.log("AirPlay 2: M3 OK, received M4 (${m4Response.body.size} bytes)")
        
        // Step 4: Verify server proof and finish
        auth.parseM4AndFinish(m4Response.body)
        
        // Step 5: Enable HAP session encryption
        enableHapEncryption()
        
        isPaired.set(true)
        Log.d(TAG, "Pair-Setup Complete!")
        LogServer.log("AirPlay 2: Pair-Setup Complete! HAP encryption enabled.")
    }
    
    private fun sendPairPinStart() {
        try {
            val response = sendRtspRequest(
                "POST", "/pair-pin-start", 
                ByteArray(0), 
                "application/x-apple-binary-plist"
            )
            LogServer.log("AirPlay 2: pair-pin-start: ${response.code}")
        } catch (e: Exception) {
            LogServer.log("AirPlay 2: pair-pin-start failed (non-critical): ${e.message}")
        }
    }
    
    private fun enableHapEncryption() {
        val sessionKey = auth.getSessionKey() 
            ?: throw Exception("No session key available")
        
        // Derive Control channel encryption keys using HKDF
        val outputKey = AirPlay2Crypto.hkdfExpand(
            "Control-Salt",
            "Control-Write-Encryption-Key",
            sessionKey
        )
        val inputKey = AirPlay2Crypto.hkdfExpand(
            "Control-Salt",
            "Control-Read-Encryption-Key",
            sessionKey
        )
        
        LogServer.log("AirPlay 2: Output Key: ${outputKey.toHex().take(32)}...")
        LogServer.log("AirPlay 2: Input Key: ${inputKey.toHex().take(32)}...")
        
        hapSession = HapSession().apply {
            enable(outputKey, inputKey)
        }
    }

    // ==================== Audio Stream Setup ====================

    /**
     * Setup audio streaming after pairing.
     * Performs two SETUP calls: event channel, then audio stream.
     */
    fun setupAudioStream(): Int {
        if (!isPaired.get()) throw Exception("Not paired")
        
        LogServer.log("AirPlay 2: Setting up audio stream...")
        
        // Generate session UUID
        sessionUuid = UUID.randomUUID().toString().uppercase()
        
        // Step 1: Setup event channel (PTP timing)
        val eventPort = setupEventChannel()
        LogServer.log("AirPlay 2: Event port: $eventPort")
        
        // Step 2: Setup audio stream
        val (ctrlPort, dataPort) = setupAudioDataChannel()
        audioControlPort = ctrlPort
        audioDataPort = dataPort
        LogServer.log("AirPlay 2: Control port: $ctrlPort, Data port: $dataPort")
        
        // Step 3: Start the stream with RECORD
        record()
        
        // Step 4: Send SETRATEANCHORTIME to trigger playback
        // This tells the receiver to start playing at rate=1 with the current timestamp as anchor
        sendSetRateAnchorTime(rate = 1, rtpTime = timestamp.toInt())
        
        // Create UDP socket for audio
        audioSocket = DatagramSocket()
        
        LogServer.log("AirPlay 2: Audio stream ready! Sending to ${device.host}:$audioDataPort")
        return audioDataPort
    }
    
    private fun setupEventChannel(): Int {
        val ip = localIp ?: "0.0.0.0"
        
        val setupBody = mapOf(
            "deviceID" to "AA:BB:CC:DD:EE:FF",
            "sessionUUID" to sessionUuid!!,
            "timingProtocol" to "PTP",
            "timingPeerInfo" to mapOf(
                "Addresses" to listOf(ip),
                "ID" to "AA:BB:CC:DD:EE:FF"
            ),
            "groupUUID" to sessionUuid!!,
            "groupContainsGroupLeader" to false,
            "isMultiSelectAirPlay" to true,
            "macAddress" to "AA:BB:CC:DD:EE:FF",
            "model" to "Android",
            "name" to "centuryplay",
            "osBuildVersion" to "1.0",
            "osName" to "Android",
            "osVersion" to "14",
            "senderSupportsRelay" to false,
            "sourceVersion" to "1.0.0",
            "statsCollectionEnabled" to false
        )
        
        val body = BinaryPlist.encode(setupBody)
        
        val response = sendRtspRequest(
            "SETUP", "rtsp://${device.host}/$sessionUuid",
            body, "application/x-apple-binary-plist"
        )
        
        if (response.code != 200) {
            throw Exception("SETUP event channel failed: ${response.code}")
        }
        
        // Parse response for event port
        val respMap = BinaryPlist.decode(response.body)
        val eventPort = (respMap?.get("eventPort") as? Number)?.toInt() ?: 0
        
        return eventPort
    }
    
    private fun setupAudioDataChannel(): Pair<Int, Int> {
        // Generate shared key for audio encryption (32 bytes)
        val shk = ByteArray(32)
        SecureRandom().nextBytes(shk)
        audioSharedKey = shk
        
        val setupBody = mapOf(
            "streams" to listOf(
                mapOf(
                    "audioFormat" to 0x800L,     // AAC-ELD (but we send PCM)
                    "audioMode" to "default",
                    "controlPort" to 0L,
                    "ct" to 1L,                  // Raw PCM (ct=1)
                    "isMedia" to true,
                    "latencyMax" to 88200L,
                    "latencyMin" to 11025L,
                    "shk" to shk,               // Shared key for audio encryption
                    "spf" to SAMPLES_PER_FRAME.toLong(),
                    "sr" to SAMPLE_RATE.toLong(),
                    "type" to 0x60L,            // Audio stream type (96)
                    "supportsDynamicStreamID" to false,
                    "streamConnectionID" to cseq.toLong()
                )
            )
        )
        
        val body = BinaryPlist.encode(setupBody)
        
        val response = sendRtspRequest(
            "SETUP", "rtsp://${device.host}/$sessionUuid",
            body, "application/x-apple-binary-plist"
        )
        
        if (response.code != 200) {
            throw Exception("SETUP audio stream failed: ${response.code}")
        }
        
        // Parse response for ports
        val respMap = BinaryPlist.decode(response.body)
        val streams = respMap?.get("streams") as? List<*>
        val stream = streams?.firstOrNull() as? Map<*, *>
        
        val ctrlPort = (stream?.get("controlPort") as? Number)?.toInt() ?: 0
        val dataPort = (stream?.get("dataPort") as? Number)?.toInt() ?: 0
        
        return Pair(ctrlPort, dataPort)
    }
    
    private fun record() {
        val response = sendRtspRequest(
            "RECORD", "rtsp://${device.host}/$sessionUuid",
            ByteArray(0), "application/octet-stream"
        )
        
        if (response.code != 200) {
            throw Exception("RECORD failed: ${response.code}")
        }
        
        LogServer.log("AirPlay 2: RECORD OK - Stream started!")
        isStreaming.set(true)
    }
    
    /**
     * Send SETRATEANCHORTIME to trigger playback.
     * 
     * This command tells the receiver when to start playing audio.
     * - rate: 1 = play, 0 = pause
     * - rtpTime: The RTP timestamp anchor point
     * - networkTimeSecs/Frac: Optional network time (PTP)
     */
    private fun sendSetRateAnchorTime(rate: Int, rtpTime: Int) {
        // Get current time in nanoseconds (for PTP anchor)
        val nowNanos = System.nanoTime()
        val networkTimeSecs = nowNanos / 1_000_000_000L
        val networkTimeFrac = ((nowNanos % 1_000_000_000L) * (1L shl 32) / 1_000_000_000L) shl 32
        
        val body = mapOf(
            "rate" to rate.toLong(),
            "rtpTime" to rtpTime.toLong(),
            "networkTimeSecs" to networkTimeSecs,
            "networkTimeFrac" to networkTimeFrac
        )
        
        val plistBody = BinaryPlist.encode(body)
        
        val response = sendRtspRequest(
            "SETRATEANCHORTIME", "rtsp://${device.host}/$sessionUuid",
            plistBody, "application/x-apple-binary-plist"
        )
        
        if (response.code != 200) {
            LogServer.log("AirPlay 2: SETRATEANCHORTIME returned ${response.code} (may be OK)")
        } else {
            LogServer.log("AirPlay 2: SETRATEANCHORTIME OK - Playback triggered!")
        }
    }

    // ==================== Audio Streaming ====================

    /**
     * Encode PCM samples to ALAC uncompressed frame.
     * 
     * ALAC header (24 bits = 3 bytes):
     * - bits 21-23: channels = 001 (stereo pair)
     * - bits 5-20: zeros (skip bits)
     * - bit 1: isnotcompressed = 1
     * - bit 0: padding = 0
     * 
     * Value: 0x200002
     */
    private fun encodeAlacFrame(samples: ShortArray): ByteArray {
        // Header: 0x200002 as 3 bytes (big-endian, take last 3 bytes of 32-bit int)
        val headerBits = 0x200002
        val header = byteArrayOf(
            ((headerBits shr 16) and 0xFF).toByte(),
            ((headerBits shr 8) and 0xFF).toByte(),
            (headerBits and 0xFF).toByte()
        )
        
        // Samples as big-endian 16-bit, interleaved L/R
        val sampleData = ByteBuffer.allocate(samples.size * 2)
            .order(ByteOrder.BIG_ENDIAN)
        for (sample in samples) {
            sampleData.putShort(sample)
        }
        
        return header + sampleData.array()
    }
    
    /**
     * Build and send an encrypted audio packet.
     * 
     * Packet format:
     * [RTP header: 2][seq: 2][timestamp: 4][ssrc: 4][encrypted_alac + tag: 16][nonce: 8]
     * 
     * AAD = timestamp + ssrc (8 bytes)
     * Nonce = sequence number as 8-byte big-endian, front-padded to 12 bytes
     */
    fun sendAudioPacket(pcmSamples: ShortArray) {
        val sock = audioSocket ?: return
        val key = audioSharedKey ?: return
        if (audioDataPort == 0) return
        
        // Encode as ALAC uncompressed
        val alacData = encodeAlacFrame(pcmSamples)
        
        // RTP header: version=2, padding=0, extension=0, csrc_count=0, marker=0, payload_type=96
        val rtpHeader = byteArrayOf(0x80.toByte(), 0x60.toByte())
        
        // Sequence number (big-endian 16-bit)
        val seqBytes = ByteBuffer.allocate(2)
            .order(ByteOrder.BIG_ENDIAN)
            .putShort((sequenceNumber and 0xFFFF).toShort())
            .array()
        
        // Timestamp (big-endian 32-bit)
        val tsBytes = ByteBuffer.allocate(4)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt((timestamp and 0xFFFFFFFFL).toInt())
            .array()
        
        // SSRC (big-endian 32-bit, fixed value)
        val ssrc = ByteBuffer.allocate(4)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(0x00000001)
            .array()
        
        // AAD = timestamp + ssrc
        val aad = tsBytes + ssrc
        
        // Nonce: sequence number as 8-byte big-endian, front-padded to 12 bytes
        val nonce8 = ByteBuffer.allocate(8)
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(sequenceNumber.toLong())
            .array()
        val nonce12 = ByteArray(4) + nonce8
        
        // Encrypt ALAC data with ChaCha20-Poly1305
        val encrypted = AirPlay2Crypto.chaCha20Poly1305EncryptWithNonce(key, nonce12, alacData, aad)
        
        // Build final packet
        val packet = rtpHeader + seqBytes + aad + encrypted + nonce8
        
        // Send over UDP
        try {
            val address = InetAddress.getByName(device.host)
            val dgram = DatagramPacket(packet, packet.size, address, audioDataPort)
            sock.send(dgram)
        } catch (e: Exception) {
            LogServer.log("AirPlay 2: Audio send error: ${e.message}")
        }
        
        // Update counters
        sequenceNumber++
        timestamp += SAMPLES_PER_FRAME
    }
    
    /**
     * Stream audio data from AudioPlaybackCapture.
     * Input: PCM data as ByteArray (16-bit little-endian stereo)
     */
    fun streamAudio(audioData: ByteArray) {
        if (!isStreaming.get()) return
        
        // Convert ByteArray to ShortArray (little-endian PCM from Android)
        val samples = ShortArray(audioData.size / 2)
        val buffer = ByteBuffer.wrap(audioData).order(ByteOrder.LITTLE_ENDIAN)
        for (i in samples.indices) {
            samples[i] = buffer.short
        }
        
        // Send in chunks of SAMPLES_PER_FRAME * 2 (stereo)
        val samplesPerPacket = SAMPLES_PER_FRAME * CHANNELS
        var offset = 0
        
        while (offset + samplesPerPacket <= samples.size) {
            val chunk = samples.copyOfRange(offset, offset + samplesPerPacket)
            sendAudioPacket(chunk)
            offset += samplesPerPacket
        }
    }
    
    /**
     * Generate and stream a test sine wave (440Hz, for testing).
     */
    fun streamTestTone(durationMs: Long = 5000) {
        if (!isStreaming.get()) {
            LogServer.log("AirPlay 2: Not streaming, cannot send test tone")
            return
        }
        
        LogServer.log("AirPlay 2: Streaming ${durationMs}ms test tone (440Hz)...")
        
        val frequency = 440.0 // Hz (A4 note)
        val framesPerSecond = SAMPLE_RATE / SAMPLES_PER_FRAME
        val totalFrames = ((durationMs / 1000.0) * framesPerSecond).toInt()
        val frameDuration = 1000.0 / framesPerSecond
        
        var sampleIndex = 0L
        val startTime = System.currentTimeMillis()
        
        for (frame in 0 until totalFrames) {
            // Generate PCM samples (stereo, interleaved)
            val samples = ShortArray(SAMPLES_PER_FRAME * CHANNELS)
            for (i in 0 until SAMPLES_PER_FRAME) {
                val t = (sampleIndex + i).toDouble() / SAMPLE_RATE
                val value = (29000 * sin(2 * Math.PI * frequency * t)).toInt().toShort()
                samples[i * 2] = value     // Left
                samples[i * 2 + 1] = value // Right
            }
            sampleIndex += SAMPLES_PER_FRAME
            
            sendAudioPacket(samples)
            
            // Pace to real-time
            val expectedTime = startTime + ((frame + 1) * frameDuration).toLong()
            val now = System.currentTimeMillis()
            if (now < expectedTime) {
                Thread.sleep(expectedTime - now)
            }
            
            // Progress log every second
            if (frame % framesPerSecond == 0) {
                LogServer.log("AirPlay 2: Sent ${frame / framesPerSecond + 1}s...")
            }
        }
        
        LogServer.log("AirPlay 2: Test tone complete! Sent $totalFrames frames")
    }

    // ==================== RTSP Communication ====================

    data class RtspResponse(val code: Int, val headers: Map<String, String>, val body: ByteArray)

    private fun sendRtspRequest(
        method: String, 
        path: String, 
        body: ByteArray, 
        contentType: String = "application/octet-stream"
    ): RtspResponse {
        val out = outputStream ?: throw Exception("Not connected")
        cseq++
        
        // Build RTSP request
        val request = StringBuilder()
        request.append("$method $path RTSP/1.0\r\n")
        request.append("CSeq: $cseq\r\n")
        request.append("Host: ${device.host}:${device.port}\r\n")
        request.append("User-Agent: $USER_AGENT\r\n")
        request.append("Connection: keep-alive\r\n")
        request.append("X-Apple-HKP: 4\r\n")
        request.append("Content-Type: $contentType\r\n")
        request.append("Content-Length: ${body.size}\r\n")
        request.append("\r\n")
        
        var requestBytes = request.toString().toByteArray(StandardCharsets.UTF_8) + body
        
        LogServer.log("AirPlay 2: >>> $method $path (${body.size} bytes)")
        
        // Encrypt if HAP session is enabled
        val hap = hapSession
        if (hap != null && hap.isEnabled) {
            requestBytes = hap.encrypt(requestBytes)
            LogServer.log("AirPlay 2: Encrypted: ${requestBytes.size} bytes")
        }
        
        out.write(requestBytes)
        out.flush()
        
        return readRtspResponse()
    }
    
    private fun readRtspResponse(): RtspResponse {
        val stream = inputStream ?: throw Exception("Not connected")
        val hap = hapSession
        
        // Read raw response
        val buffer = ByteArray(4096)
        var decryptedResponse = ByteArray(0)
        
        while (true) {
            val read = stream.read(buffer)
            if (read == -1) throw Exception("Connection closed")
            val chunk = buffer.sliceArray(0 until read)
            
            // Decrypt chunk if HAP session is enabled
            // Note: HapSession buffers incomplete frames internally
            if (hap != null && hap.isEnabled) {
                decryptedResponse += hap.decrypt(chunk)
            } else {
                decryptedResponse += chunk
            }
            
            // Check if we have complete response
            val headerEnd = findHeaderEnd(decryptedResponse)
            if (headerEnd >= 0) {
                val headerPart = String(decryptedResponse.sliceArray(0 until headerEnd), StandardCharsets.UTF_8)
                val contentLength = parseContentLength(headerPart)
                val bodyStart = headerEnd + 4
                
                if (decryptedResponse.size >= bodyStart + contentLength) {
                    break
                }
            }
        }
        
        val response = decryptedResponse
        
        // Parse response
        val headerEnd = findHeaderEnd(response)
        val headerLines = String(response.sliceArray(0 until headerEnd), StandardCharsets.UTF_8)
            .split("\r\n")
        
        val statusLine = headerLines.firstOrNull() ?: throw Exception("Invalid response")
        val statusParts = statusLine.split(" ")
        val code = statusParts.getOrNull(1)?.toIntOrNull() ?: throw Exception("Invalid status: $statusLine")
        
        val headers = mutableMapOf<String, String>()
        for (line in headerLines.drop(1)) {
            val colonIdx = line.indexOf(':')
            if (colonIdx > 0) {
                val key = line.substring(0, colonIdx).trim()
                val value = line.substring(colonIdx + 1).trim()
                headers[key] = value
            }
        }
        
        val body = response.sliceArray(headerEnd + 4 until response.size)
        
        LogServer.log("AirPlay 2: <<< $code (${body.size} bytes)")
        
        return RtspResponse(code, headers, body)
    }
    
    private fun findHeaderEnd(data: ByteArray): Int {
        for (i in 0 until data.size - 3) {
            if (data[i] == '\r'.code.toByte() &&
                data[i + 1] == '\n'.code.toByte() &&
                data[i + 2] == '\r'.code.toByte() &&
                data[i + 3] == '\n'.code.toByte()) {
                return i
            }
        }
        return -1
    }
    
    private fun parseContentLength(headers: String): Int {
        for (line in headers.split("\r\n")) {
            if (line.lowercase().startsWith("content-length:")) {
                return line.substringAfter(':').trim().toIntOrNull() ?: 0
            }
        }
        return 0
    }
}

// Extension function
private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
