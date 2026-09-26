package com.airplay.streamer.util

/**
 * Monotonic wall clock for RAOP/AirPlay timing.
 *
 * Anchored to System.currentTimeMillis() once, then advanced with System.nanoTime(),
 * so NTP timing replies and sync packets never jump when the phone adjusts its wall
 * clock mid-stream. Timing responders and sync senders must all use this same clock.
 */
object NtpClock {
    private const val NTP_EPOCH_OFFSET = 2208988800L // seconds between 1900 and 1970

    private val anchorWallNanos = System.currentTimeMillis() * 1_000_000L
    private val anchorNanos = System.nanoTime()

    fun nowMs(): Long = nowNanos() / 1_000_000L

    /** Unix-epoch nanoseconds on the monotonic timeline. */
    fun nowNanos(): Long = anchorWallNanos + (System.nanoTime() - anchorNanos)

    /** Convert a System.nanoTime() value (e.g. an AudioTimestamp) onto this clock. */
    fun fromMonotonic(monoNanos: Long): Long = anchorWallNanos + (monoNanos - anchorNanos)

    /** NTP (seconds, fraction) for a millisecond timestamp from [nowMs]. */
    fun toNtp(ms: Long): Pair<Long, Long> = nanosToNtp(ms * 1_000_000L)

    /** NTP (seconds, fraction) for a nanosecond timestamp from [nowNanos]. */
    fun nanosToNtp(nanos: Long): Pair<Long, Long> {
        val sec = nanos / 1_000_000_000L + NTP_EPOCH_OFFSET
        val frac = ((nanos % 1_000_000_000L) * 4294967296.0 / 1e9).toLong()
        return sec to frac
    }

    fun ntpNow(): Pair<Long, Long> = nanosToNtp(nowNanos())

    fun writeNtp(b: ByteArray, o: Int, sec: Long, frac: Long) {
        b[o] = (sec shr 24).toByte()
        b[o + 1] = (sec shr 16).toByte()
        b[o + 2] = (sec shr 8).toByte()
        b[o + 3] = sec.toByte()
        b[o + 4] = (frac shr 24).toByte()
        b[o + 5] = (frac shr 16).toByte()
        b[o + 6] = (frac shr 8).toByte()
        b[o + 7] = frac.toByte()
    }

    fun writeNtpNanos(b: ByteArray, o: Int, nanos: Long) {
        val (s, f) = nanosToNtp(nanos)
        writeNtp(b, o, s, f)
    }
}
