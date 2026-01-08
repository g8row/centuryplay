package com.airplay.streamer.airplay2.timing

import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

/**
 * PTP Master Clock for AirPlay 2
 * 
 * Sends PTP Announce, Sync, and Follow_Up messages to NQPTP on ports 319/320.
 * NQPTP is passive and monitors PTP messages from our IP to establish timing.
 */
class PtpMasterClock(
    private val targetHost: String
) {
    companion object {
        const val PTP_EVENT_PORT = 319
        const val PTP_GENERAL_PORT = 320
        const val SYNC_INTERVAL_MS = 125L // 8 messages per second
        
        // PTP Message Types
        const val MSG_SYNC = 0x00
        const val MSG_FOLLOW_UP = 0x08
        const val MSG_ANNOUNCE = 0x0B
    }
    
    val clockId: Long = SecureRandom().nextLong()
    
    private var socket319: DatagramSocket? = null
    private var socket320: DatagramSocket? = null
    private var running = false
    private var job: Job? = null
    
    private var syncSequenceId = 0
    private var announceSequenceId = 0
    
    /**
     * Start the PTP master clock
     */
    fun start(scope: CoroutineScope) {
        if (running) return
        
        try {
            socket319 = DatagramSocket(PTP_EVENT_PORT).apply { reuseAddress = true }
            socket320 = DatagramSocket(PTP_GENERAL_PORT).apply { reuseAddress = true }
        } catch (e: Exception) {
            com.airplay.streamer.util.LogServer.log("PtpMasterClock: Failed to bind to 319/320, using ephemeral ports")
            socket319 = DatagramSocket().apply { reuseAddress = true }
            socket320 = DatagramSocket().apply { reuseAddress = true }
        }
        running = true
        
        job = scope.launch(Dispatchers.IO) {
            runPtpLoop()
        }
    }
    
    /**
     * Stop the PTP master clock
     */
    fun stop() {
        running = false
        job?.cancel()
        socket319?.close()
        socket320?.close()
        socket319 = null
        socket320 = null
    }
    
    /**
     * Send a burst of ANNOUNCE messages to wake up NQPTP
     */
    suspend fun sendAnnounceBurst(count: Int = 5, delayMs: Long = 50) {
        val targetAddr = InetAddress.getByName(targetHost)
        repeat(count) { i ->
            val announce = buildAnnounceMessage(1000 + i)
            sendTo320(announce, targetAddr)
            if (i < count - 1) {
                delay(delayMs)
            }
        }
    }
    
    /**
     * Main PTP message loop
     */
    private suspend fun runPtpLoop() {
        val targetAddr = InetAddress.getByName(targetHost)
        
        while (running) {
            val nowNs = System.nanoTime()
            
            // Send ANNOUNCE every ~1 second (every 8th message)
            if (syncSequenceId % 8 == 0) {
                val announce = buildAnnounceMessage(announceSequenceId++)
                sendTo320(announce, targetAddr)
            }
            
            // Send SYNC
            val sync = buildSyncMessage(syncSequenceId)
            sendTo319(sync, targetAddr)
            
            // Send FOLLOW_UP with current time
            val followUp = buildFollowUpMessage(syncSequenceId, nowNs)
            sendTo320(followUp, targetAddr)
            
            syncSequenceId++
            
            delay(SYNC_INTERVAL_MS)
        }
    }
    
    /**
     * Build PTP common header (34 bytes)
     */
    private fun buildPtpHeader(msgType: Int, length: Int, seqId: Int, flags: Int = 0x0008): ByteArray {
        val header = ByteBuffer.allocate(34).order(ByteOrder.BIG_ENDIAN)
        
        header.put((0x10 or msgType).toByte()) // transportSpecific (0x1 for 802.1AS) | messageType
        header.put(0x02.toByte()) // versionPTP = 2
        header.putShort(length.toShort()) // messageLength
        header.put(0x00.toByte()) // domainNumber (0 for gPTP)
        header.put(0x00.toByte()) // reserved
        header.putShort(flags.toShort()) // flags
        header.putLong(0L) // correctionField
        header.putInt(0) // reserved
        header.putLong(clockId) // clockIdentity
        header.putShort(1) // sourcePortID
        header.putShort(seqId.toShort()) // sequenceId
        header.put(0x05.toByte()) // controlField
        header.put(0x00.toByte()) // logMessagePeriod
        
        return header.array()
    }
    
    /**
     * Build PTP Announce message (64 bytes)
     */
    private fun buildAnnounceMessage(seqId: Int): ByteArray {
        val header = buildPtpHeader(MSG_ANNOUNCE, 64, seqId, 0x0008)
        header[32] = 0x05.toByte() // controlField: Other
        header[33] = 0x00.toByte() // logMessageInterval: 0 = 1 second
        
        val msg = ByteBuffer.allocate(64).order(ByteOrder.BIG_ENDIAN)
        msg.put(header)
        
        // originTimestamp (10 bytes) - zeros
        msg.position(44)
        msg.putShort(37) // currentUtcOffset (TAI-UTC offset)
        msg.put(0x00.toByte()) // reserved
        msg.put(248.toByte()) // grandmasterPriority1 (Apple profile)
        msg.putInt(0xF8FEFFFF.toInt()) // clockQuality (class=248, accuracy=0xFE, variance=0xFFFF)
        msg.put(248.toByte()) // grandmasterPriority2
        
        // grandmasterIdentity (8 bytes) at offset 53
        msg.position(53)
        msg.putLong(clockId)
        
        // stepsRemoved (2 bytes) at offset 61
        msg.position(61)
        msg.putShort(0)
        
        // timeSource (1 byte) at offset 63
        msg.put(0xA0.toByte()) // Internal Oscillator
        
        return msg.array()
    }
    
    /**
     * Build PTP Sync message (44 bytes)
     */
    private fun buildSyncMessage(seqId: Int): ByteArray {
        // Sync uses flags=0x0208 (twoStepFlag + ptpTimescale)
        val header = buildPtpHeader(MSG_SYNC, 44, seqId, 0x0208)
        header[32] = 0x00.toByte() // controlField: Sync
        header[33] = 0xFD.toByte() // logMessageInterval: -3 = 125ms (signed byte)
        
        val msg = ByteBuffer.allocate(44).order(ByteOrder.BIG_ENDIAN)
        msg.put(header)
        // originTimestamp (10 bytes) - zeros for two-step clock
        
        return msg.array()
    }
    
    /**
     * Build PTP Follow_Up message (76 bytes)
     */
    private fun buildFollowUpMessage(seqId: Int, originTimestampNs: Long): ByteArray {
        // Follow_Up uses flags=0x0008 (ptpTimescale only)
        val header = buildPtpHeader(MSG_FOLLOW_UP, 76, seqId, 0x0008)
        header[32] = 0x02.toByte() // controlField: Follow_Up
        header[33] = 0xFD.toByte() // logMessageInterval: -3 = 125ms
        
        val msg = ByteBuffer.allocate(76).order(ByteOrder.BIG_ENDIAN)
        msg.put(header)
        
        // preciseOriginTimestamp (10 bytes)
        val seconds = originTimestampNs / 1_000_000_000L
        val nanoseconds = (originTimestampNs % 1_000_000_000L).toInt()
        
        msg.position(34)
        msg.putShort(((seconds shr 32) and 0xFFFF).toShort()) // secondsHi
        msg.putInt((seconds and 0xFFFFFFFFL).toInt()) // secondsLo
        msg.putInt(nanoseconds) // nanoseconds
        
        // TLV: Organization Extension (Apple)
        msg.position(44)
        msg.putShort(0x0003) // tlvType: ORGANIZATION_EXTENSION
        msg.putShort(28) // lengthField
        
        // Apple organization ID (00:17:F2)
        msg.put(byteArrayOf(0x00, 0x17, 0xF2.toByte()))
        // Organization subtype (00:00:01)
        msg.put(byteArrayOf(0x00, 0x00, 0x01))
        
        // lastGmPhaseChange (10 bytes) - zeros
        msg.position(64)
        msg.putLong(clockId) // lastGmClockIdentity
        msg.putShort(0) // gmTimeBaseIndicator
        msg.putShort(0) // scaledLastGmFreqChange
        
        return msg.array()
    }
    
    private fun sendTo319(data: ByteArray, address: InetAddress) {
        socket319?.send(DatagramPacket(data, data.size, address, PTP_EVENT_PORT))
    }
    
    private fun sendTo320(data: ByteArray, address: InetAddress) {
        socket320?.send(DatagramPacket(data, data.size, address, PTP_GENERAL_PORT))
    }
}
