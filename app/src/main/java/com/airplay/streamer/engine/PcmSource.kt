package com.airplay.streamer.engine

import android.media.AudioRecord
import java.io.InputStream

/** Blocking source of 44.1 kHz 16-bit little-endian stereo PCM. */
interface PcmSource {
    val description: String
    fun start()
    /** Blocking read; returns bytes read or a negative value when the source is gone. */
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
    fun stop()
}

/** MediaProjection playback capture (or any other AudioRecord). */
class AudioRecordSource(private val record: AudioRecord, override val description: String) : PcmSource {
    override fun start() = record.startRecording()
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        record.read(buffer, offset, length, AudioRecord.READ_BLOCKING)
    override fun stop() {
        runCatching { record.stop() }
        runCatching { record.release() }
    }
}

/** PCM delivered over a pipe (e.g. from the Shizuku capture service running as shell). */
class StreamPcmSource(
    private val input: InputStream,
    override val description: String,
    private val onStop: () -> Unit = {},
) : PcmSource {
    override fun start() {}
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = input.read(buffer, offset, length)
    override fun stop() {
        runCatching { input.close() }
        onStop()
    }
}
