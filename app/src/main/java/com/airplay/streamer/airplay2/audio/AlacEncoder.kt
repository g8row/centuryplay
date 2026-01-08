package com.airplay.streamer.airplay2.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ALAC (Apple Lossless Audio Codec) Encoder for AirPlay 2
 * 
 * This is a simplified "uncompressed" ALAC encoder that wraps raw PCM
 * in ALAC frame headers. 
 * 
 * STRICT MODE: Matches python-airplay2 implementation 1-to-1.
 * Uses a bit-stream format (23-bit header + PCM samples).
 */
object AlacEncoder {
    
    /**
     * Audio configuration
     */
    const val SAMPLE_RATE = 44100
    const val CHANNELS = 2
    const val BITS_PER_SAMPLE = 16
    const val SAMPLES_PER_FRAME = 352
    const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8
    const val BYTES_PER_FRAME = SAMPLES_PER_FRAME * CHANNELS * BYTES_PER_SAMPLE
    
    /**
     * Helper class for writing bits to a byte array
     */
    private class BitBuffer {
        private val bytes = mutableListOf<Byte>()
        private var currentByte = 0
        private var bitCount = 0
        
        fun write(value: Int, numBits: Int) {
            for (i in numBits - 1 downTo 0) {
                val bit = (value shr i) and 1
                currentByte = (currentByte shl 1) or bit
                bitCount++
                
                if (bitCount == 8) {
                    bytes.add(currentByte.toByte())
                    currentByte = 0
                    bitCount = 0
                }
            }
        }
        
        fun toByteArray(): ByteArray {
            val result = bytes.toByteArray()
            // Pad remaining bits with zeros at LSB end to form complete byte
            if (bitCount > 0) {
                return result + ((currentByte shl (8 - bitCount)).toByte())
            }
            return result
        }
    }
    
    /**
     * Wrap PCM data in ALAC "uncompressed" frame format.
     * 
     * Uses strict bit-packing to match AirPlay 2 expectations:
     * - Header: 23 bits
     * - PCM: 16-bit samples (Big Endian sequence)
     * 
     * @param pcmData Raw PCM data (16-bit stereo interleaved, little-endian)
     * @return ALAC frame ready for RTP transmission
     */
    fun encodeFrame(pcmData: ByteArray): ByteArray {
        require(pcmData.size == BYTES_PER_FRAME) {
            "PCM data must be $BYTES_PER_FRAME bytes, got ${pcmData.size}"
        }
        
        val bits = BitBuffer()
        
        // --- Header (23 bits) ---
        // Format from Python reference:
        // [channels:3] = 1 (stereo) -> 001
        // [unknown:4] = 0 -> 0000
        // [unknown:12] = 0 -> 000000000000
        // [has_size:1] = 0 -> 0
        // [unknown:2] = 0 -> 00
        // [no_compress:1] = 1 -> 1
        
        bits.write(1, 3)  // channels=1 (Stereo)
        bits.write(0, 4)  // unknown
        bits.write(0, 12) // unknown
        bits.write(0, 1)  // has_size=0
        bits.write(0, 2)  // unknown
        bits.write(1, 1)  // no_compression=1
        
        // --- PCM Data ---
        // Input is Little Endian bytes.
        // Output must be Big Endian 16-bit samples written as bits.
        
        for (i in 0 until pcmData.size step 2) {
            // Read 16-bit LE sample
            val low = pcmData[i].toInt() and 0xFF
            val high = pcmData[i + 1].toInt() and 0xFF
            val sample = (high shl 8) or low
            
            // Write 16 bits (MSB first)
            bits.write(sample, 16)
        }
        
        return bits.toByteArray()
    }
    
    /**
     * Generate a sine wave PCM frame for testing.
     */
    fun generateSineFrame(frequency: Double = 440.0, frameIndex: Int = 0): ByteArray {
        val buffer = ByteBuffer.allocate(BYTES_PER_FRAME).order(ByteOrder.LITTLE_ENDIAN)
        
        val samplesOffset = frameIndex * SAMPLES_PER_FRAME
        
        for (i in 0 until SAMPLES_PER_FRAME) {
            val t = (samplesOffset + i).toDouble() / SAMPLE_RATE
            val sample = (Math.sin(2.0 * Math.PI * frequency * t) * 32767 * 0.5).toInt().toShort()
            
            // Left channel
            buffer.putShort(sample)
            // Right channel
            buffer.putShort(sample)
        }
        
        return buffer.array()
    }
    
    /**
     * Generate test audio frames
     */
    fun generateTestFrames(durationSeconds: Double, frequency: Double = 440.0): List<ByteArray> {
        val totalSamples = (durationSeconds * SAMPLE_RATE).toInt()
        val totalFrames = (totalSamples + SAMPLES_PER_FRAME - 1) / SAMPLES_PER_FRAME
        
        return (0 until totalFrames).map { frameIndex ->
            val pcm = generateSineFrame(frequency, frameIndex)
            encodeFrame(pcm)
        }
    }
    
    /**
     * Encode raw PCM audio data into ALAC frames.
     */
    fun encodePcm(pcmData: ByteArray): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
        var offset = 0
        
        while (offset + BYTES_PER_FRAME <= pcmData.size) {
            val framePcm = pcmData.copyOfRange(offset, offset + BYTES_PER_FRAME)
            frames.add(encodeFrame(framePcm))
            offset += BYTES_PER_FRAME
        }
        
        // Handle remaining samples (pad with silence)
        if (offset < pcmData.size) {
            val remaining = pcmData.copyOfRange(offset, pcmData.size)
            val padded = remaining + ByteArray(BYTES_PER_FRAME - remaining.size)
            frames.add(encodeFrame(padded))
        }
        
        return frames
    }
}
