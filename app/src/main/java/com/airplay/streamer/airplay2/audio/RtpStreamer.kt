package com.airplay.streamer.airplay2.audio

import com.airplay.streamer.airplay2.crypto.Chacha20Cipher
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * RTP Audio Streamer for AirPlay 2
 * 
 * Sends encrypted ALAC audio packets over UDP with proper RTP headers.
 */
class RtpStreamer(
    private val targetHost: String,
    private val dataPort: Int,
    private val controlPort: Int,
    sharedSecret: ByteArray
) {
    companion object {
        const val SAMPLES_PER_FRAME = 352
        const val SAMPLE_RATE = 44100
        const val SSRC = 0x55667788
        const val LATENCY_FRAMES = 77175
    }
    
    private val cipher = Chacha20Cipher(sharedSecret, sharedSecret)
    private var dataSocket: DatagramSocket? = null
    private var controlSocket: DatagramSocket? = null
    
    private var sequenceNumber = Random.nextInt(0, 65536)
    private var rtpTimestamp = 0
    private var encryptionCounter = 0L
    private var anchorPacketSeq = 0
    
    private var targetAddress: InetAddress? = null
    
    /**
     * Initialize the streamer
     */
    fun init() {
        dataSocket = DatagramSocket()
        controlSocket = DatagramSocket()
        targetAddress = InetAddress.getByName(targetHost)
    }
    
    /**
     * Close the streamer
     */
    fun close() {
        dataSocket?.close()
        controlSocket?.close()
        dataSocket = null
        controlSocket = null
    }
    
    /**
     * Set the starting RTP sequence and timestamp (from FLUSH command)
     */
    fun setStarting(seq: Int, timestamp: Int) {
        sequenceNumber = seq
        rtpTimestamp = timestamp
    }
    
    /**
     * Send a sentinel anchor packet to establish timing
     */
    fun sendSentinelAnchor(clockId: Long) {
        val ptpTimeNs = System.nanoTime() + 2_000_000_000L // 2 seconds in future
        sendAnchorPacket(rtpTimestamp, ptpTimeNs, clockId, isSentinel = true)
    }
    
    /**
     * Send an anchor packet (Type 0xD7) to the control port
     */
    fun sendAnchorPacket(rtpTs: Int, ptpTimeNs: Long, clockId: Long, isSentinel: Boolean = false) {
        val packet = ByteBuffer.allocate(28).order(ByteOrder.BIG_ENDIAN)
        
        // Byte 0: 0x80 (V=2) | 0x10 if sentinel
        packet.put((0x80 or (if (isSentinel) 0x10 else 0x00)).toByte())
        
        // Byte 1: Type 215 (0xD7)
        packet.put(0xD7.toByte())
        
        // Bytes 2-3: Sequence number
        packet.putShort((anchorPacketSeq++ and 0xFFFF).toShort())
        
        // Bytes 4-7: frame_1 = frame_2 - LATENCY_FRAMES
        val frame2 = rtpTs
        val frame1 = frame2 - LATENCY_FRAMES
        packet.putInt(frame1)
        
        // Bytes 8-15: remote_packet_time_ns (PTP time in nanoseconds)
        packet.putLong(ptpTimeNs)
        
        // Bytes 16-19: frame_2
        packet.putInt(frame2)
        
        // Bytes 20-27: clock_id
        packet.putLong(clockId)
        
        sendControl(packet.array())
    }
    
    /**
     * Send a single ALAC audio packet
     */
    fun sendAudioPacket(alacPayload: ByteArray, clockId: Long, sendAnchor: Boolean = false) {
        // Build RTP header (12 bytes)
        val rtpHeader = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
        rtpHeader.put(0x80.toByte()) // V=2
        rtpHeader.put(0x60.toByte()) // M=0, PT=96
        rtpHeader.putShort(sequenceNumber.toShort())
        rtpHeader.putInt(rtpTimestamp)
        rtpHeader.putInt(SSRC)
        
        // Build nonce (12 bytes): 4 zero bytes + 8 byte little-endian counter
        val nonce = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        nonce.putInt(0)
        nonce.putLong(encryptionCounter++)
        
        // AAD: RTP header bytes 4-12 (Timestamp + SSRC)
        val aad = rtpHeader.array().copyOfRange(4, 12)
        
        // Encrypt payload
        val encryptedPayload = cipher.encrypt(alacPayload, nonce.array(), aad)
        
        // Build final packet: RTP header + encrypted payload + nonce (last 8 bytes)
        val packet = ByteBuffer.allocate(12 + encryptedPayload.size + 8)
        packet.put(rtpHeader.array())
        packet.put(encryptedPayload)
        packet.put(nonce.array().copyOfRange(4, 12)) // Append 8-byte nonce
        
        sendData(packet.array())
        
        // Update sequence and timestamp
        sequenceNumber = (sequenceNumber + 1) and 0xFFFF
        rtpTimestamp += SAMPLES_PER_FRAME
        
        // Send anchor packet periodically (~every 1 second = 125 packets)
        if (sendAnchor && (sequenceNumber % 125 == 0)) {
            val elapsed = ((sequenceNumber * SAMPLES_PER_FRAME.toLong()) * 1_000_000_000L) / SAMPLE_RATE
            sendAnchorPacket(rtpTimestamp, System.nanoTime() + 2_000_000_000L, clockId)
        }
    }
    
    private fun sendData(data: ByteArray) {
        val packet = DatagramPacket(data, data.size, targetAddress, dataPort)
        dataSocket?.send(packet)
    }
    
    private fun sendControl(data: ByteArray) {
        val packet = DatagramPacket(data, data.size, targetAddress, controlPort)
        controlSocket?.send(packet)
    }
}
