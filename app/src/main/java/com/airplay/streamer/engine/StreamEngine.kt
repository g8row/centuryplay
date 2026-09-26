package com.airplay.streamer.engine

import android.os.Process
import com.airplay.streamer.util.LogServer
import com.airplay.streamer.util.NtpClock
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/**
 * Captures PCM from a [PcmSource] on a dedicated audio-priority thread, cuts it into
 * 352-frame packets and hands every packet to all attached [AudioSink]s.
 *
 * All sinks share one frame timeline ([CaptureTimeline]) and one latency, and every
 * receiver syncs to the same NtpClock, so several speakers play in sync (multi-room).
 */
class StreamEngine(latencyMs: Int) {
    val timeline = CaptureTimeline(SAMPLE_RATE)
    val context = StreamContext(timeline, latencyMs.toLong() * SAMPLE_RATE / 1000, SAMPLE_RATE)

    private val sinks = CopyOnWriteArrayList<AudioSink>()
    @Volatile private var running = false
    private var thread: Thread? = null
    private var source: PcmSource? = null

    /** Peak level of the most recent packet, 0..1 (for a UI level meter). */
    @Volatile var level: Float = 0f
        private set

    /** Milliseconds of continuous digital silence so far. */
    @Volatile var silenceMs: Long = 0
        private set

    @Volatile var packetsCaptured: Long = 0
        private set
    @Volatile var gapsFilled: Long = 0
        private set

    var onSourceEnded: (() -> Unit)? = null

    val attachedSinks: List<AudioSink> get() = sinks.toList()

    fun addSink(sink: AudioSink) {
        sinks.addIfAbsent(sink)
    }

    fun removeSink(sink: AudioSink) {
        sinks.remove(sink)
    }

    val isRunning: Boolean get() = running

    fun start(pcmSource: PcmSource) {
        check(!running) { "already running" }
        source = pcmSource
        running = true
        thread = Thread({ captureLoop(pcmSource) }, "AudioCapture").apply { start() }
    }

    fun stop() {
        running = false
        source?.stop()
        thread?.join(1000)
        thread = null
        source = null
    }

    private fun captureLoop(src: PcmSource) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        LogServer.log("Capture started (${src.description})")
        try {
            src.start()
        } catch (e: Exception) {
            LogServer.log("Capture source failed to start: ${e.message}")
            running = false
            onSourceEnded?.invoke()
            return
        }
        var buffer = ByteArray(AudioPacket.BYTES)
        var filled = 0
        var frameIndex = 0L
        while (running) {
            val n = try {
                src.read(buffer, filled, AudioPacket.BYTES - filled)
            } catch (e: Exception) {
                if (running) LogServer.log("Capture read failed: ${e.message}")
                -1
            }
            if (n < 0) {
                if (running) {
                    LogServer.log("Capture source ended ($n)")
                    running = false
                    onSourceEnded?.invoke()
                }
                break
            }
            filled += n
            if (filled < AudioPacket.BYTES) continue
            filled = 0

            val now = NtpClock.nowNanos()
            // A stall in the source (e.g. the capture pipeline restarting) leaves a hole in
            // time. Fill it with silence so RTP time keeps matching real time.
            val lag = timeline.lagFrames(frameIndex + AudioPacket.FRAMES, now)
            if (timeline.isStarted && lag > GAP_THRESHOLD_FRAMES) {
                val missing = (lag / AudioPacket.FRAMES).toInt().coerceAtMost(MAX_GAP_PACKETS)
                repeat(missing) {
                    dispatch(AudioPacket(ByteArray(AudioPacket.BYTES), frameIndex, true))
                    frameIndex += AudioPacket.FRAMES
                }
                gapsFilled += missing
                LogServer.log("Capture stall: filled $missing silent packets")
            }
            timeline.onFramesCaptured(frameIndex + AudioPacket.FRAMES, now)

            val peak = peakOf(buffer)
            level = peak / 32768f
            silenceMs = if (peak == 0) silenceMs + PACKET_MS else 0
            dispatch(AudioPacket(buffer, frameIndex, peak == 0))
            frameIndex += AudioPacket.FRAMES
            packetsCaptured++
            buffer = ByteArray(AudioPacket.BYTES) // sinks may keep the old one for resends
        }
        LogServer.log("Capture stopped")
    }

    private fun dispatch(packet: AudioPacket) {
        for (sink in sinks) {
            try {
                sink.send(packet)
            } catch (e: Exception) {
                LogServer.log("Sink ${sink.displayName} send failed: ${e.message}")
            }
        }
    }

    private fun peakOf(pcm: ByteArray): Int {
        var peak = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val s = abs(((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt())
            if (s > peak) peak = s
            i += 2
        }
        return peak
    }

    companion object {
        const val SAMPLE_RATE = 44100
        private const val PACKET_MS = 1000L * AudioPacket.FRAMES / SAMPLE_RATE
        private const val GAP_THRESHOLD_FRAMES = SAMPLE_RATE / 5L // 200 ms
        private const val MAX_GAP_PACKETS = SAMPLE_RATE * 2 / AudioPacket.FRAMES
    }
}
