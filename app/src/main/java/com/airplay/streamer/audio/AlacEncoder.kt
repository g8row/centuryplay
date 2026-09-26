package com.airplay.streamer.audio

import android.util.Log

/** JNI bindings to Apple's reference ALAC codec (app/src/main/cpp). */
internal object AlacNative {
    val available: Boolean = try {
        System.loadLibrary("centuryplay_native")
        true
    } catch (t: Throwable) {
        Log.w("AlacNative", "native ALAC unavailable, using verbatim frames: ${t.message}")
        false
    }

    @JvmStatic external fun nativeCreateEncoder(sampleRate: Int, channels: Int, frameSize: Int, fast: Boolean): Long
    @JvmStatic external fun nativeEncode(handle: Long, pcm: ByteArray, offset: Int, frames: Int, out: ByteArray): Int
    @JvmStatic external fun nativeDestroyEncoder(handle: Long)
    @JvmStatic external fun nativeDecode(packet: ByteArray, length: Int, frameSize: Int, channels: Int, outPcm: ByteArray): Int
}

/**
 * ALAC encoder for RAOP (16-bit stereo, 44.1 kHz, 352 frames per packet — the
 * `a=fmtp:96 352 0 16 40 10 14 2 255 0 0 44100` stream every AirPlay 1 receiver accepts).
 *
 * Uses Apple's encoder via JNI (~40-50% smaller than PCM on music). If the native library
 * can't load it falls back to "escape" (uncompressed) ALAC frames, which every decoder
 * accepts too. Not thread-safe; one instance per stream.
 */
class AlacEncoder(private val frameSize: Int = FRAMES_PER_PACKET) : AutoCloseable {
    private var handle: Long =
        if (AlacNative.available) AlacNative.nativeCreateEncoder(44100, 2, frameSize, false) else 0L

    val isNative: Boolean get() = handle != 0L

    /**
     * Encode [frames] little-endian interleaved 16-bit stereo frames from [pcm] at [offset]
     * into [out] (must hold at least [maxEncodedSize] bytes). Returns the encoded length.
     */
    fun encode(pcm: ByteArray, offset: Int, frames: Int, out: ByteArray): Int {
        if (handle != 0L) {
            val n = AlacNative.nativeEncode(handle, pcm, offset, frames, out)
            if (n > 0) return n
        }
        return encodeVerbatim(pcm, offset, frames, out, frameSize)
    }

    override fun close() {
        if (handle != 0L) AlacNative.nativeDestroyEncoder(handle)
        handle = 0L
    }

    companion object {
        const val FRAMES_PER_PACKET = 352

        fun maxEncodedSize(frames: Int = FRAMES_PER_PACKET) = frames * 4 + 32

        /**
         * Uncompressed ("escape") ALAC frame, bit-identical to what Apple's encoder emits
         * for incompressible input (ALACEncoder.cpp EncodeStereoEscape):
         * tag ID_CPE(3) instance(4) | 12 zero bits | partial(1) shift(2)=0 escape(1)=1 |
         * [32-bit sample count if partial] | L,R 16-bit samples | ID_END(3), byte aligned.
         */
        fun encodeVerbatim(pcm: ByteArray, offset: Int, frames: Int, out: ByteArray, frameSize: Int = FRAMES_PER_PACKET): Int {
            val w = BitWriter(out)
            val partial = frames != frameSize
            w.write(1, 3) // ID_CPE
            w.write(0, 4) // element instance tag
            w.write(0, 12)
            w.write(((if (partial) 1 else 0) shl 3) or 1, 4)
            if (partial) w.write(frames, 32)
            var i = offset
            val end = offset + frames * 4
            while (i < end) {
                // little-endian input -> 16-bit big-endian bit order
                val sample = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
                w.write(sample and 0xFFFF, 16)
                i += 2
            }
            w.write(7, 3) // ID_END
            return w.finish()
        }
    }

    private class BitWriter(private val buf: ByteArray) {
        private var pos = 0 // bit position
        fun write(value: Int, bits: Int) {
            for (b in bits - 1 downTo 0) {
                val bit = (value ushr b) and 1
                val byteIdx = pos ushr 3
                if (pos and 7 == 0) buf[byteIdx] = 0
                if (bit != 0) buf[byteIdx] = (buf[byteIdx].toInt() or (0x80 ushr (pos and 7))).toByte()
                pos++
            }
        }
        fun finish(): Int = (pos + 7) ushr 3
    }
}
