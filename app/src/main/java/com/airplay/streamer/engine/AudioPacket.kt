package com.airplay.streamer.engine

import com.airplay.streamer.audio.AlacEncoder

/**
 * One RTP packet worth of audio: [FRAMES] frames of 16-bit little-endian stereo PCM,
 * starting at stream frame [frameIndex]. Encoded forms are computed once on first use and
 * shared by every sink (sinks are driven sequentially from the capture thread).
 */
class AudioPacket(val pcm: ByteArray, val frameIndex: Long, val silent: Boolean) {
    private var alac: ByteArray? = null
    private var pcmBigEndian: ByteArray? = null

    fun alac(encoder: AlacEncoder, scratch: ByteArray): ByteArray =
        alac ?: run {
            val n = encoder.encode(pcm, 0, FRAMES, scratch)
            scratch.copyOf(n).also { alac = it }
        }

    fun bigEndianPcm(): ByteArray = pcmBigEndian ?: run {
        val out = ByteArray(pcm.size)
        var i = 0
        while (i + 1 < pcm.size) {
            out[i] = pcm[i + 1]
            out[i + 1] = pcm[i]
            i += 2
        }
        out.also { pcmBigEndian = it }
    }

    companion object {
        const val FRAMES = 352
        const val BYTES = FRAMES * 4
    }
}
