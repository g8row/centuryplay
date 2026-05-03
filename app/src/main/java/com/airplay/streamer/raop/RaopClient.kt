package com.airplay.streamer.raop

import android.util.Log
import com.airplay.streamer.util.LogServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
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
    private val port: Int,
    private val deviceFeatures: Map<String, String> = emptyMap()
) {
    companion object {
        private const val TAG = "RaopClient"
        private const val USER_AGENT = "Music/1.5.6 (Macintosh; OS X 15.7.3) AppleWebKit/621.3.11.11.3"
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
    private var rtspInput: InputStream? = null
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
    private var localIp: String = "0.0.0.0"
    private var serverPort: Int = 0
    private var serverControlPort: Int = 0  // Server's control port for sync packets
    private var serverTimingPort: Int = 0   // Server's timing port

    private val isConnected = AtomicBoolean(false)
    private val isStreaming = AtomicBoolean(false)

    private var rtpSequence: Int = Random.nextInt(0xFFFF)
    private var rtpTimestamp: Long = Random.nextLong(0xFFFFFFFFL)
    private val ssrc: Int = Random.nextInt()
    private val alacEncoder = AlacEncoder()

    // Apple's RSA Public Key for AirPlay (2048-bit) - from shairport-sync's super_secret_key
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
    private var fairPlaySetupValid = false

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

            // Connection 1: fp-setup × 2 + OPTIONS, then close — matches Apple Music's behaviour.
            rtspSocket = Socket(host, port).apply { soTimeout = 5000 }
            localIp = rtspSocket!!.localAddress.hostAddress ?: "0.0.0.0"
            logD("RTSP socket connected, localIp=$localIp")
            rtspInput = rtspSocket!!.getInputStream()

            logD("Connection 1: fp-setup...")
            doFpSetup(mode = 0x00, requireValidPhase2 = false)

            logD("Testing OPTIONS...")
            val optionsResult = testOptions()
            logD("OPTIONS result: $optionsResult")

            rtspSocket?.close()
            cSeq.set(0)

            // Connection 2: fp-setup × 2 + ANNOUNCE → SETUP → RECORD
            rtspSocket = Socket(host, port).apply { soTimeout = 10000 }
            rtspInput = rtspSocket!!.getInputStream()
            logD("Reopened RTSP socket for ANNOUNCE")

            logD("Connection 2: fp-setup...")
            fairPlaySetupValid = doFpSetup(mode = 0x03, requireValidPhase2 = true)

            // Create UDP sockets for audio/control/timing
            audioSocket = DatagramSocket()
            controlSocket = DatagramSocket()
            timingSocket = DatagramSocket()
            logD("UDP sockets: audio=${audioSocket?.localPort}, ctrl=${controlSocket?.localPort}, time=${timingSocket?.localPort}")
            
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
     * FairPlay SAPv2 stub handshake (POST /fp-setup × 2).
     * AirScreen requires this exchange before it accepts ANNOUNCE.
     */
    private fun doFpSetup(mode: Int, requireValidPhase2: Boolean): Boolean {
        return try {
            // Phase 1 - match macOS Music's FairPlay SAPv2 probe:
            // FPLY 02 01 01 00 00 00 00 04 02 00 <mode> bb
            val phase1 = byteArrayOf(
                0x46, 0x50, 0x4c, 0x59,  // FPLY magic
                0x02, 0x01, 0x01, 0x00,  // version=2, type=1(setup), seq=1
                0x00, 0x00, 0x00, 0x04,  // payload length
                0x02, 0x00, mode.toByte(), 0xbb.toByte()
            )
            val headers1 = mutableMapOf(
                "Content-Type" to "application/octet-stream",
                "Content-Length" to phase1.size.toString()
            )
            sendRtspRequestDirect("POST", "/fp-setup", headers1, phase1)
            
            val resp1 = parseRtspResponse()
            logD("fp-setup phase1 response: ${resp1?.first}")
            val len1 = resp1?.second?.get("Content-Length")?.toIntOrNull() ?: 0
            discardResponseBody(len1)
            if (resp1?.first != 200 || len1 != 142) {
                logD("fp-setup phase1 invalid: code=${resp1?.first}, length=$len1")
                return false
            }

            // Phase 2 - 164-byte FPLY v2 key message stub. Bytes 12..163 are
            // normally FairPlay output; random bytes let us test whether this
            // receiver only requires the transport exchange before ANNOUNCE.
            val phase2 = ByteArray(164).also { b ->
                Random.nextBytes(b)
                b[0] = 0x46; b[1] = 0x50; b[2] = 0x4c; b[3] = 0x59  // FPLY
                b[4] = 0x02  // version 2
                b[5] = 0x01  // type = setup
                b[6] = 0x03  // seq = 3
                b[7] = 0x00
                b[8] = 0x00; b[9] = 0x00; b[10] = 0x00; b[11] = 0x98.toByte()
                b[12] = mode.toByte()
            }
            val headers2 = mutableMapOf(
                "Content-Type" to "application/octet-stream",
                "Content-Length" to phase2.size.toString()
            )
            sendRtspRequestDirect("POST", "/fp-setup", headers2, phase2)
            
            val resp2 = parseRtspResponse()
            logD("fp-setup phase2 response: ${resp2?.first}")
            val len2 = resp2?.second?.get("Content-Length")?.toIntOrNull() ?: 0
            discardResponseBody(len2)
            val valid = resp2?.first == 200 && len2 == 32
            if (!valid) {
                logD("fp-setup phase2 invalid: code=${resp2?.first}, length=$len2")
                if (requireValidPhase2) {
                    logD("FairPlay phase2 requires Apple's proprietary key message; dummy body is not enough")
                }
            }
            valid
        } catch (e: Exception) {
            logD("fp-setup skipped or failed: ${e.message}")
            false
        }
    }

    /**
     * Send RTSP request directly to OutputStream to avoid mixed stream issues
     */
    private fun sendRtspRequestDirect(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        sessionId: String? = null
    ) {
        val sb = StringBuilder()
        // Determine the correct URL
        val requestUrl = if (url.startsWith("/") || url == "*") url else url
        
        sb.append("$method $requestUrl RTSP/1.0\r\n")
        sb.append("CSeq: ${cSeq.incrementAndGet()}\r\n")
        if (!headers.containsKey("User-Agent")) {
            sb.append("User-Agent: $USER_AGENT\r\n")
        }
        if (!headers.containsKey("Client-Instance")) {
            sb.append("Client-Instance: $clientInstance\r\n")
        }
        if (!headers.containsKey("DACP-ID")) {
            sb.append("DACP-ID: $dacpId\r\n")
        }
        if (!headers.containsKey("Active-Remote")) {
            sb.append("Active-Remote: $activeRemote\r\n")
        }

        if (sessionId != null) {
            sb.append("Session: $sessionId\r\n")
        }

        headers.forEach { (key, value) ->
            sb.append("$key: $value\r\n")
        }
        sb.append("\r\n")
        
        val headerStr = sb.toString()
        if (method != "RECORD") {
            logD("$method request:\n$headerStr")
            if (body != null && (method == "POST" || method == "ANNOUNCE")) {
                logD("$method body (${body.size} bytes): ${hexDump(body, maxBytes = 48)}")
            }
        }

        try {
            val out = rtspSocket?.getOutputStream() ?: return
            out.write(headerStr.toByteArray(Charsets.ISO_8859_1))
            if (body != null) {
                out.write(body)
            }
            out.flush()
        } catch (e: Exception) {
            logE("Failed to send RTSP request: ${e.message}")
        }
    }

    private fun parseRtspResponse(): Pair<Int, Map<String, String>>? {
        val headers = mutableMapOf<String, String>()
        try {
            val input = rtspInput ?: return null
            val headerBytes = readUntilHeaderEnd(input)
            if (headerBytes.isEmpty()) {
                logE("parseRtspResponse: statusLine is null (connection closed?)")
                return null
            }
            val headerText = headerBytes.toString(Charsets.ISO_8859_1)
            val lines = headerText.split("\r\n")
            val statusLine = lines.firstOrNull { it.isNotEmpty() } ?: return null
            logD("parseRtspResponse: statusLine = $statusLine")
            val statusCode = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: return null

            for (line in lines.drop(1)) {
                if (line.isEmpty()) break

                val colonIndex = line.indexOf(':')
                if (colonIndex > 0) {
                    val key = line.substring(0, colonIndex).trim()
                    val value = line.substring(colonIndex + 1).trim()
                    headers[key] = value
                }
            }
            return statusCode to headers
        } catch (e: Exception) {
            logE("parseRtspResponse error: ${e.message}")
            return null
        }
    }

    private fun discardResponseBody(length: Int) {
        if (length <= 0) return
        try {
            val input = rtspInput ?: return
            val body = ByteArray(length)
            var remaining = length
            var offset = 0
            while (remaining > 0) {
                val n = input.read(body, offset, remaining)
                if (n < 0) break
                offset += n
                remaining -= n
            }
            logD("RTSP response body (${length - remaining}/$length bytes): ${hexDump(body, maxBytes = 48)}")
        } catch (e: Exception) {
            logE("Error discarding response body: ${e.message}")
        }
    }

    private fun readUntilHeaderEnd(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) break
            out.write(b)
            last4 = ((last4 shl 8) or b) and 0xFFFFFFFF.toInt()
            if (last4 == 0x0D0A0D0A) break
        }
        return out.toByteArray()
    }

    private fun hexDump(data: ByteArray, maxBytes: Int = data.size): String {
        val shown = data.take(maxBytes).joinToString(" ") { "%02x".format(it) }
        return if (data.size > maxBytes) "$shown ..." else shown
    }

    private fun testOptions(): Boolean {
        val challenge = generateAppleChallenge()
        val headers = mapOf(
            "User-Agent" to "iTunes/10.6 (Windows; N)",
            "Apple-Challenge" to challenge
        )
        sendRtspRequestDirect("OPTIONS", "*", headers)
        val response = parseRtspResponse()
        logD("Diagnostic OPTIONS response: code=${response?.first}")
        return response != null
    }

    private fun announce(): Boolean {
        if (useFairPlayStub && !fairPlaySetupValid) {
            logE("FairPlay setup failed; refusing to ANNOUNCE dummy fpaeskey to avoid receiver hang")
            return false
        }
        generateKeys()
        val rsaAesKey = if (useEncryption) encryptRsaAesKey() ?: return false else null
        val aesIvBase64 = if (useEncryption) Base64.getEncoder().encodeToString(aesIv) else null
        val sdp = buildSdp(localIp, rsaAesKey, aesIvBase64)

        val headers = mapOf(
            "Content-Type" to "application/sdp",
            "Content-Length" to sdp.toByteArray(Charsets.ISO_8859_1).size.toString()
        )
        sendRtspRequestDirect("ANNOUNCE", "rtsp://$localIp/$localSessionId", headers, sdp.toByteArray(Charsets.ISO_8859_1))

        val response = parseRtspResponse()
        return response?.first == 200
    }

    private fun setup(): Boolean {
        val localControlPort = controlSocket?.localPort ?: return false
        val localTimingPort = timingSocket?.localPort ?: return false
        
        startTimingResponder()

        val headers = mapOf(
            "Transport" to "RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;control_port=$localControlPort;timing_port=$localTimingPort"
        )
        sendRtspRequestDirect("SETUP", "rtsp://$localIp/$localSessionId", headers, sessionId = serverSessionId)

        val response = parseRtspResponse()
        if (response != null && response.first == 200) {
            val transportHeader = response.second["Transport"] ?: return false
            parseTransportHeader(transportHeader)
            
            val sessionVal = response.second["Session"]
            if (sessionVal != null) {
                serverSessionId = sessionVal.split(";")[0].trim()
            }
            return true
        }
        return false
    }

    private fun record(): Boolean {
        val headers = mapOf(
            "Range" to "npt=0-",
            "RTP-Info" to "seq=$rtpSequence;rtptime=$rtpTimestamp"
        )
        sendRtspRequestDirect("RECORD", "rtsp://$localIp/$localSessionId", headers, sessionId = serverSessionId)
        val response = parseRtspResponse()
        if (response?.first == 200) {
            isStreaming.set(true)
            startSyncSender()
            startHealthMonitor()
            return true
        }
        return false
    }

    suspend fun streamAudio(pcmData: ByteArray) = withContext(Dispatchers.IO) {
        if (!isStreaming.get() || audioSocket == null) return@withContext
        val packetSize = if (useFairPlayStub) alacEncoder.getExpectedPcmSize() else 1408
        
        synchronized(audioBuffer) {
            audioBuffer.write(pcmData)
        }

        val bufferBytes = synchronized(audioBuffer) { audioBuffer.toByteArray() }
        if (bufferBytes.size >= packetSize) {
            var offset = 0
            while (offset + packetSize <= bufferBytes.size) {
                val chunk = bufferBytes.copyOfRange(offset, offset + packetSize)
                val payloadData = if (useFairPlayStub) {
                    alacEncoder.encode(chunk)
                } else {
                    val beData = swapEndianness(chunk)
                    if (useEncryption) encryptAudio(beData) else beData
                }
                
                val rtpPacket = buildRtpPacket(payloadData)
                val address = InetAddress.getByName(host)
                val packet = DatagramPacket(rtpPacket, rtpPacket.size, address, serverPort)
                audioSocket?.send(packet)

                rtpSequence = (rtpSequence + 1) and 0xFFFF
                rtpTimestamp += FRAMES_PER_PACKET
                offset += packetSize
            }
            synchronized(audioBuffer) {
                audioBuffer.reset()
                if (offset < bufferBytes.size) {
                    audioBuffer.write(bufferBytes.copyOfRange(offset, bufferBytes.size))
                }
            }
        }
    }

    suspend fun setVolume(volume: Float): Boolean = withContext(Dispatchers.IO) {
        if (!isConnected.get()) return@withContext false
        val dbVolume = if (volume <= 0f) -144f else (volume * 30f - 30f)
        val volumeStr = "volume: $dbVolume\r\n"
        val headers = mapOf(
            "Content-Type" to "text/parameters",
            "Content-Length" to volumeStr.length.toString()
        )
        sendRtspRequestDirect("SET_PARAMETER", "rtsp://$localIp/$localSessionId", headers, volumeStr.toByteArray(Charsets.ISO_8859_1))
        parseRtspResponse()?.first == 200
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        logD("disconnect() called")
        val wasConnected = isConnected.getAndSet(false)
        isStreaming.set(false)
        stopHealthMonitor()
        stopSyncSender()
        stopTimingResponder()
        
        if (wasConnected) {
            try {
                rtspSocket?.soTimeout = 2000
                sendRtspRequestDirect("TEARDOWN", "rtsp://$localIp/$localSessionId", emptyMap(), sessionId = serverSessionId)
                parseRtspResponse()
            } catch (e: Exception) {}
        }
        
        try { rtspInput?.close() } catch (e: Exception) {}
        try { rtspSocket?.close() } catch (e: Exception) {}
        try { audioSocket?.close() } catch (e: Exception) {}
        try { controlSocket?.close() } catch (e: Exception) {}
        try { timingSocket?.close() } catch (e: Exception) {}
        
        rtspSocket = null
        rtspInput = null
        audioSocket = null
        controlSocket = null
        timingSocket = null
        serverSessionId = null
        logD("Teardown complete")
        callback?.onDisconnected()
    }

    private val audioBuffer = java.io.ByteArrayOutputStream()
    private fun generateHexId(bytes: Int): String {
        val data = ByteArray(bytes)
        Random.nextBytes(data)
        return data.joinToString("") { "%02X".format(it) }
    }
    
    private fun generateAppleChallenge(): String {
        val bytes = ByteArray(16)
        Random.nextBytes(bytes)
        return Base64.getEncoder().encodeToString(bytes)
    }

    private fun generateKeys() {
        aesKey = ByteArray(16); aesIv = ByteArray(16)
        Random.nextBytes(aesKey!!); Random.nextBytes(aesIv!!)
        try {
            aesCipher = Cipher.getInstance("AES/CBC/NoPadding")
            aesCipher?.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(aesIv))
        } catch (e: Exception) { aesCipher = null }
    }

    private fun encryptRsaAesKey(): String? {
        try {
            val modulus = BigInteger(1, Base64.getDecoder().decode(RSA_MODULUS))
            val exponent = BigInteger(1, Base64.getDecoder().decode(RSA_EXPONENT))
            val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent))
            val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding")
            cipher.init(Cipher.ENCRYPT_MODE, publicKey)
            return Base64.getEncoder().encodeToString(cipher.doFinal(aesKey))
        } catch (e: Exception) { return null }
    }

    private fun parseTransportHeader(transport: String) {
        transport.split(";").forEach { part ->
            val p = part.trim()
            if (p.startsWith("server_port=")) serverPort = p.substringAfter("=").toIntOrNull() ?: 0
            if (p.startsWith("control_port=")) serverControlPort = p.substringAfter("=").toIntOrNull() ?: 0
            if (p.startsWith("timing_port=")) serverTimingPort = p.substringAfter("=").toIntOrNull() ?: 0
        }
    }

    private val supportedEncryptionTypes: Set<Int> =
        RaopCapabilities.encryptionTypes(deviceFeatures).ifEmpty { setOf(0, 1) }
    private val useFairPlayStub = RaopCapabilities.requiresUnsupportedFairPlay(deviceFeatures)
    private var useEncryption = 1 in supportedEncryptionTypes

    private fun buildSdp(localIp: String, rsaAesKey: String?, aesIvBase64: String?): String {
        val base = "v=0\r\no=iTunes $localSessionId 0 IN IP4 $localIp\r\ns=iTunes\r\nc=IN IP4 $host\r\nt=0 0\r\nm=audio 0 RTP/AVP 96"
        return if (useFairPlayStub) {
            val dummyFpAesKey = buildDummyFpAesKey()
            val dummyAesIv = Base64.getEncoder().encodeToString(aesIv ?: ByteArray(16))
            logD("FairPlay et=5 receiver; announcing ALAC with dummy fpaeskey")
            "$base\r\na=rtpmap:96 AppleLossless\r\na=fmtp:96 352 0 16 40 10 14 2 255 0 0 44100\r\na=fpaeskey:$dummyFpAesKey\r\na=aesiv:$dummyAesIv\r\n"
        } else if (useEncryption) {
            "$base\r\na=rtpmap:96 L16/44100/2\r\na=rsaaeskey:$rsaAesKey\r\na=aesiv:$aesIvBase64\r\n"
        } else {
            logD("No RSA support on receiver; announcing unencrypted L16")
            "$base\r\na=rtpmap:96 L16/44100/2\r\n"
        }
    }

    private fun buildDummyFpAesKey(): String {
        val data = ByteArray(72)
        Random.nextBytes(data)
        data[0] = 0x46; data[1] = 0x50; data[2] = 0x4c; data[3] = 0x59
        data[4] = 0x01; data[5] = 0x02; data[6] = 0x01; data[7] = 0x00
        data[8] = 0x00; data[9] = 0x00; data[10] = 0x00; data[11] = 0x3c
        data[12] = 0x00; data[13] = 0x00; data[14] = 0x00; data[15] = 0x00
        return Base64.getEncoder().encodeToString(data)
    }

    private fun buildRtpPacket(data: ByteArray): ByteArray {
        val h = ByteArray(12)
        h[0] = 0x80.toByte(); h[1] = 0x60.toByte()
        h[2] = (rtpSequence shr 8).toByte(); h[3] = rtpSequence.toByte()
        h[4] = (rtpTimestamp shr 24).toByte(); h[5] = (rtpTimestamp shr 16).toByte()
        h[6] = (rtpTimestamp shr 8).toByte(); h[7] = rtpTimestamp.toByte()
        h[8] = (ssrc shr 24).toByte(); h[9] = (ssrc shr 16).toByte()
        h[10] = (ssrc shr 8).toByte(); h[11] = ssrc.toByte()
        return h + data
    }

    private fun swapEndianness(data: ByteArray): ByteArray {
        val r = ByteArray(data.size)
        for (i in 0 until data.size step 2) {
            if (i + 1 < data.size) { r[i] = data[i + 1]; r[i + 1] = data[i] }
        }
        return r
    }

    private fun encryptAudio(data: ByteArray): ByteArray {
        val key = aesKey ?: return data
        val iv = aesIv ?: return data
        return try {
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val b = 16; val n = data.size / b; val es = n * b
            if (es == 0) return data
            val enc = cipher.doFinal(data.copyOfRange(0, es))
            if (data.size > es) enc + data.copyOfRange(es, data.size) else enc
        } catch (e: Exception) { data }
    }

    private fun startTimingResponder() {
        val socket = timingSocket ?: return
        isTimingRunning.set(true)
        Thread {
            val buffer = ByteArray(128); val packet = DatagramPacket(buffer, buffer.size)
            try {
                while (isTimingRunning.get() && !socket.isClosed) {
                    socket.receive(packet)
                    if (packet.length >= 32) {
                        val req = packet.data; val resp = ByteArray(packet.length)
                        System.arraycopy(req, 0, resp, 0, 8)
                        resp[1] = (0x53 or 0x80).toByte()
                        System.arraycopy(req, 24, resp, 8, 8)
                        val now = System.currentTimeMillis()
                        val ntpSec = (now / 1000) + 2208988800L
                        val ntpFrac = ((now % 1000) * 4294967296.0 / 1000.0).toLong()
                        writeNtpTimestamp(resp, 16, ntpSec, ntpFrac)
                        writeNtpTimestamp(resp, 24, ntpSec, ntpFrac)
                        socket.send(DatagramPacket(resp, packet.length, packet.address, packet.port))
                    }
                }
            } catch (e: Exception) {}
        }.start()
    }

    private fun writeNtpTimestamp(b: ByteArray, o: Int, s: Long, f: Long) {
        b[o] = (s shr 24).toByte(); b[o+1] = (s shr 16).toByte(); b[o+2] = (s shr 8).toByte(); b[o+3] = s.toByte()
        b[o+4] = (f shr 24).toByte(); b[o+5] = (f shr 16).toByte(); b[o+6] = (f shr 8).toByte(); b[o+7] = f.toByte()
    }

    private fun startSyncSender() {
        val socket = controlSocket ?: return
        if (serverControlPort == 0) return
        isSyncRunning.set(true)
        val latencyMs = 2500L; val latencySamples = (latencyMs * SAMPLE_RATE / 1000)
        val startRtp = rtpTimestamp; val startTime = System.currentTimeMillis()
        Thread {
            try {
                val addr = InetAddress.getByName(host); var lastSync = 0L
                while (isSyncRunning.get() && !socket.isClosed) {
                    val now = System.currentTimeMillis()
                    if (now - lastSync >= 300) {
                        val elapsedMs = now - startTime
                        val curPlayRtp = startRtp + (elapsedMs * SAMPLE_RATE / 1000)
                        val playTime = now + latencyMs
                        val syncPacket = buildSyncPacket(curPlayRtp, playTime, latencySamples)
                        socket.send(DatagramPacket(syncPacket, syncPacket.size, addr, serverControlPort))
                        syncSequence++; lastSync = now
                    }
                    Thread.sleep(50)
                }
            } catch (e: Exception) {}
        }.start()
    }

    private fun buildSyncPacket(rtp: Long, time: Long, lat: Long): ByteArray {
        val p = ByteArray(20)
        p[0] = if (syncSequence == 0) 0x90.toByte() else 0x80.toByte()
        p[1] = 0xd4.toByte(); p[2] = (syncSequence shr 8).toByte(); p[3] = syncSequence.toByte()
        p[4] = (rtp shr 24).toByte(); p[5] = (rtp shr 16).toByte(); p[6] = (rtp shr 8).toByte(); p[7] = rtp.toByte()
        val s = (time / 1000) + 2208988800L; val f = ((time % 1000) * 4294967296.0 / 1000.0).toLong()
        writeNtpTimestamp(p, 8, s, f)
        val n = rtp + lat
        p[16] = (n shr 24).toByte(); p[17] = (n shr 16).toByte(); p[18] = (n shr 8).toByte(); p[19] = n.toByte()
        return p
    }

    private fun stopSyncSender() = isSyncRunning.set(false)
    private fun stopTimingResponder() = isTimingRunning.set(false)

    private fun startHealthMonitor() {
        isHealthMonitorRunning.set(true)
        Thread {
            try {
                while (isHealthMonitorRunning.get() && isConnected.get()) {
                    Thread.sleep(HEALTH_CHECK_INTERVAL_MS)
                    val s = rtspSocket
                    if (s == null || s.isClosed || !s.isConnected) { handleServerDisconnect(); break }
                    try {
                        s.soTimeout = 100
                        if (s.getInputStream().read() == -1) { handleServerDisconnect(); break }
                    } catch (e: java.net.SocketTimeoutException) {
                    } catch (e: Exception) { handleServerDisconnect(); break }
                    finally { try { s.soTimeout = 10000 } catch (e: Exception) {} }
                }
            } catch (e: Exception) {}
        }.start()
    }

    private fun stopHealthMonitor() = isHealthMonitorRunning.set(false)

    private fun handleServerDisconnect() {
        if (!isConnected.get()) return
        isConnected.set(false); isStreaming.set(false)
        stopHealthMonitor(); stopSyncSender(); stopTimingResponder()
        try { rtspSocket?.close() } catch (e: Exception) {}
        callback?.onDisconnected()
    }

    private fun logD(m: String) { Log.d(TAG, m); LogServer.d(TAG, m) }
    private fun logE(m: String) { Log.e(TAG, m); LogServer.e(TAG, m) }
}
