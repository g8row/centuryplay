package com.airplay.streamer.raop

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Simple ALAC (Apple Lossless Audio Codec) encoder for AirPlay 1
 * 
 * For AirPlay 1, we can use uncompressed ALAC frames which are accepted
 * by most receivers. This is a simplified implementation that wraps PCM
 * data in ALAC frame format.
 */
class AlacEncoder {
    companion object {
        private const val FRAMES_PER_PACKET = 352
        private const val CHANNELS = 2
        private const val BITS_PER_SAMPLE = 16
        private const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8
        private const val BYTES_PER_FRAME = CHANNELS * BYTES_PER_SAMPLE
    }

    /**
     * Encode PCM audio data to ALAC format
     * Input: 16-bit stereo PCM, 44100Hz, little-endian
     * Output: ALAC frame suitable for RTP streaming
     */
    fun encode(pcmData: ByteArray): ByteArray {
        // For simplicity, we use uncompressed ALAC mode
        // This is less efficient but widely compatible
        
        val numSamples = pcmData.size / BYTES_PER_FRAME
        
        // ALAC packet header for uncompressed mode
        // Format: 1 byte header + PCM data (converted to big-endian)
        val header = buildAlacHeader(numSamples)
        
        // Convert PCM from little-endian to big-endian (network byte order)
        val bigEndianPcm = convertToBigEndian(pcmData)
        
        return header + bigEndianPcm
    }

    private fun buildAlacHeader(numSamples: Int): ByteArray {
        // ALAC uncompressed frame header
        // Bit layout:
        // [0:2] = 001 (uncompressed)
        // [3:11] = reserved
        // [12] = has size flag (1 if not standard size)
        // [13] = unused high bit
        // [14:15] = unused
        // [16:31] = sample count (if has size flag = 1)
        
        return if (numSamples == FRAMES_PER_PACKET) {
            // Standard size - just 3 bytes header
            byteArrayOf(
                0x20.toByte(), // Uncompressed, no size field
                0x00.toByte(),
                0x00.toByte()
            )
        } else {
            // Non-standard size - include sample count
            val buffer = ByteBuffer.allocate(7)
            buffer.order(ByteOrder.BIG_ENDIAN)
            
            // Header with hasSize flag
            buffer.put(0x24.toByte())
            buffer.putShort(0)
            buffer.putInt(numSamples)
            
            buffer.array()
        }
    }

    private fun convertToBigEndian(pcmData: ByteArray): ByteArray {
        val result = ByteArray(pcmData.size)
        
        // Swap bytes for each 16-bit sample
        for (i in pcmData.indices step 2) {
            if (i + 1 < pcmData.size) {
                result[i] = pcmData[i + 1]
                result[i + 1] = pcmData[i]
            }
        }
        
        return result
    }

    /**
     * Get the expected PCM buffer size for one ALAC packet
     */
    fun getExpectedPcmSize(): Int {
        return FRAMES_PER_PACKET * BYTES_PER_FRAME
    }
}
