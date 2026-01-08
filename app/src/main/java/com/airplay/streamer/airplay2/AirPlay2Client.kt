package com.airplay.streamer.airplay2

import com.airplay.streamer.airplay2.audio.RtpStreamer
import com.airplay.streamer.airplay2.protocol.RtspClient
import com.airplay.streamer.airplay2.protocol.TransientPairing
import com.airplay.streamer.airplay2.timing.PtpMasterClock
import com.dd.plist.BinaryPropertyListWriter
import com.dd.plist.NSDictionary
import com.dd.plist.NSArray
import com.dd.plist.NSData
import com.dd.plist.PropertyListParser
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * AirPlay 2 Client
 * 
 * Main entry point for AirPlay 2 streaming with transient pairing.
 * 
 * Usage:
 * ```kotlin
 * val client = AirPlay2Client("192.168.1.100")
 * client.connect()
 * if (client.pair()) {
 *     client.setupStreaming()
 *     client.streamAudio(audioFrames)
 * }
 * client.disconnect()
 * ```
 */
class AirPlay2Client(
    private val host: String,
    private val port: Int = 7000
) {
    private val rtspClient = RtspClient(host, port)
    private val pairing = TransientPairing(rtspClient)
    private var ptpClock: PtpMasterClock? = null
    private var rtpStreamer: RtpStreamer? = null
    
    private var sessionUuid: String? = null
    private var eventPort: Int = 0
    private var controlPort: Int = 0
    private var dataPort: Int = 0
    private var audioSharedSecret: ByteArray? = null
    private var sentinelSent = false
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    /**
     * Connect to the AirPlay receiver
     */
    fun connect() {
        rtspClient.connect()
    }
    
    /**
     * Disconnect and cleanup
     */
    fun disconnect() {
        ptpClock?.stop()
        rtpStreamer?.close()
        rtspClient.disconnect()
        scope.cancel()
    }
    
    /**
     * Perform transient pairing
     */
    suspend fun pair(): Boolean {
        return pairing.pair()
    }
    
    /**
     * Setup streaming (event channel + audio stream)
     */
    suspend fun setupStreaming(): Boolean {
        // Step 1: Setup event channel (PTP timing)
        eventPort = setupEventChannel()
        
        // Step 2: Start PTP master clock
        ptpClock = PtpMasterClock(host).apply {
            start(scope)
        }
        
        // Wait for clock to establish
        delay(500)
        ptpClock?.sendAnnounceBurst()
        delay(500)
        
        // Step 3: Setup audio stream
        val (ctrl, data, secret) = setupAudioStream()
        controlPort = ctrl
        dataPort = data
        audioSharedSecret = secret
        
        // Match Python: Send another Announce Burst and wait for NQPTP to "lock"
        // This is critical for the receiver to accept the stream
        ptpClock?.sendAnnounceBurst(count = 5, delayMs = 50)
        delay(1500)
        
        // Step 4: Send RECORD
        record()
        
        // Step 4.5: Send FLUSH to set initial RTP timestamp state
        flush()
        
        // Step 4.6: Unmute
        setVolume(0.0f)
        
        // Step 5: Initialize RTP streamer
        // Ensure we start with specific seq/ts to match FLUSH
        rtpStreamer = RtpStreamer(host, dataPort, controlPort, audioSharedSecret!!).apply {
            init()
            setStarting(0, 0) // Match FLUSH
        }
        
        sentinelSent = false
        
        return true
    }
    
    /**
     * Setup event channel and get event port
     */
    private fun setupEventChannel(): Int {
        sessionUuid = UUID.randomUUID().toString().uppercase()
        
        val setupBody = NSDictionary().apply {
            put("deviceID", "AA:BB:CC:DD:EE:FF")
            put("sessionUUID", sessionUuid)
            put("timingProtocol", "PTP")
            put("timingPeerInfo", NSDictionary().apply {
                put("Addresses", arrayOf(rtspClient.getLocalAddress() ?: getLocalIp()))
                put("ID", "AA:BB:CC:DD:EE:FF")
            })
            put("groupUUID", sessionUuid)
            put("groupContainsGroupLeader", false)
            put("isMultiSelectAirPlay", true)
            put("macAddress", "AA:BB:CC:DD:EE:FF")
            put("model", "iPhone14,3")
            put("name", "centuryplay")
            put("osBuildVersion", "20F66")
            put("osName", "iPhone OS")
            put("osVersion", "16.5")
            put("senderSupportsRelay", false)
            put("sourceVersion", "690.7.1")
            put("statsCollectionEnabled", false)
        }
        
        val baos = ByteArrayOutputStream()
        BinaryPropertyListWriter.write(baos, setupBody)
        val bodyBytes = baos.toByteArray()
        
        val response = rtspClient.sendRtsp(
            "SETUP",
            "rtsp://$host/$sessionUuid",
            bodyBytes,
            "application/x-apple-binary-plist"
        )
        
        if (response.statusCode != 200) {
            throw Exception("SETUP failed: ${response.statusCode}")
        }
        
        val plist = PropertyListParser.parse(ByteArrayInputStream(response.body)) as NSDictionary
        return plist["eventPort"]?.toString()?.toInt() ?: 0
    }
    
    /**
     * Setup audio stream and get ports/secret
     */
    private fun setupAudioStream(): Triple<Int, Int, ByteArray> {
        val sharedSecret = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        
        val setupBody = NSDictionary().apply {
            put("streams", arrayOf(
                NSDictionary().apply {
                    put("type", 96)
                    put("audioFormat", 0x100000)
                    put("audioMode", "default")
                    put("ct", 1)
                    put("spf", 352)
                    put("shk", NSData(sharedSecret))
                    put("isMedia", true)
                    put("latencyMax", 88200)
                    put("latencyMin", 11025)
                    put("supportsDynamicStreamID", true)
                    put("streamConnectionID", rtspClient.cseq.toLong())
                }
            ))
        }
        
        val baos = ByteArrayOutputStream()
        BinaryPropertyListWriter.write(baos, setupBody)
        val bodyBytes = baos.toByteArray()
        
        val response = rtspClient.sendRtsp(
            "SETUP",
            "rtsp://$host/$sessionUuid",
            bodyBytes,
            "application/x-apple-binary-plist"
        )
        
        if (response.statusCode != 200) {
            throw Exception("Audio SETUP failed: ${response.statusCode}")
        }
        
        val plist = PropertyListParser.parse(ByteArrayInputStream(response.body)) as NSDictionary
        val streams = plist["streams"] as? NSArray
        val stream = (streams?.array?.firstOrNull() as? NSDictionary) 
            ?: throw Exception("No stream in response")
        
        val ctrlPort = stream["controlPort"]?.toString()?.toInt() ?: 0
        val dataPortValue = stream["dataPort"]?.toString()?.toInt() ?: 0
        // Use the local sharedSecret, NOT what's in the response
        
        return Triple(ctrlPort, dataPortValue, sharedSecret)
    }
    
    // ... (record unchanged) ...

    /**
     * Send FLUSH command with RTP-Info
     */
    private fun flush() {
        // RTP-Info: seq=0;rtptime=0
        val headers = mapOf(
            "Range" to "npt=0-",
            "RTP-Info" to "seq=0;rtptime=0"
        )
        rtspClient.sendRtsp("FLUSH", "rtsp://$host/$sessionUuid", extraHeaders = headers)
    }

    /**
     * Send SET_PARAMETER to set volume
     */
    private fun setVolume(vol: Float) {
        val content = "volume: $vol\r\n".toByteArray()
        rtspClient.sendRtsp(
            "SET_PARAMETER", 
            "rtsp://$host/$sessionUuid", 
            body = content,
            contentType = "text/parameters"
        )
    }
    
    /**
     * Send RECORD command
     */
    private fun record() {
        val response = rtspClient.sendRtsp(
            "RECORD",
            "rtsp://$host/$sessionUuid"
        )
        if (response.statusCode != 200) {
            throw Exception("RECORD failed: ${response.statusCode}")
        }
    }
    
    /**
     * Stream audio frames (batch mode with pacing for static files)
     */
    fun streamAudio(alacFrames: List<ByteArray>) {
        val streamer = rtpStreamer ?: throw IllegalStateException("Streamer not initialized")
        val clockId = ptpClock?.clockId ?: 0L
        
        // Send sentinel anchor
        streamer.sendSentinelAnchor(clockId)
        sentinelSent = true
        
        // Send frames
        for ((index, frame) in alacFrames.withIndex()) {
            streamer.sendAudioPacket(frame, clockId, sendAnchor = (index % 125 == 0))
            
            // Pacing: ~8ms per frame, batch 10 frames before sleeping
            if (index % 10 == 0) {
                Thread.sleep(80)
            }
        }
    }

    /**
     * Send raw PCM audio data (real-time mode)
     * Encodes to ALAC and sends immediately.
     */
    fun sendAudioData(pcmData: ByteArray) {
        val streamer = rtpStreamer ?: return // Ignore if not ready
        val clockId = ptpClock?.clockId ?: 0L
        
        // Send sentinel anchor if not sent yet
        if (!sentinelSent) {
            streamer.sendSentinelAnchor(clockId)
            sentinelSent = true
        }
        
        // Encode PCM to ALAC frames
        val frames = com.airplay.streamer.airplay2.audio.AlacEncoder.encodePcm(pcmData)
        
        // Send frames immediately (source provides pacing)
        for (frame in frames) {
            streamer.sendAudioPacket(frame, clockId, sendAnchor = true)
        }
    }
    
    /**
     * Get local IP address
     */
    private fun getLocalIp(): String {
        return com.airplay.streamer.airplay2.util.NetworkUtils.getLocalIpAddress()
    }
}
