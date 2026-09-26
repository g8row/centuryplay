package com.airplay.streamer.airplay2

import com.airplay.streamer.util.NtpClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import kotlin.concurrent.thread

/**
 * NTP-style timing responder for AirPlay realtime streaming. The receiver sends timing
 * requests (0xD2) to this UDP port (advertised in SETUP) and we answer (0xD3) with the
 * originate timestamp echoed and our receive/transmit times on [NtpClock]. Same wire
 * behaviour as pyatv's `protocols/raop/protocols.TimingServer`.
 *
 * Uses a high (unprivileged) UDP port — no root, no PTP.
 */
class NtpTimingServer {

    private var socket: DatagramSocket? = null

    val port: Int
        get() = socket?.localPort ?: 0

    fun start() {
        if (socket != null) return
        val s = DatagramSocket()
        socket = s
        thread(name = "NtpTimingServer", isDaemon = true) {
            val buffer = ByteArray(128)
            val packet = DatagramPacket(buffer, buffer.size)
            while (!s.isClosed) {
                try {
                    s.receive(packet)
                    val received = NtpClock.nowNanos()
                    if (packet.length < 32) continue
                    val resp = ByteArray(32)
                    resp[0] = 0x80.toByte()
                    resp[1] = 0xD3.toByte()
                    resp[2] = 0x00; resp[3] = 0x07
                    System.arraycopy(buffer, 24, resp, 8, 8) // originate = their transmit time
                    NtpClock.writeNtpNanos(resp, 16, received)
                    NtpClock.writeNtpNanos(resp, 24, NtpClock.nowNanos())
                    s.send(DatagramPacket(resp, resp.size, packet.address, packet.port))
                } catch (_: Exception) {
                    if (s.isClosed) break
                }
            }
        }
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
    }
}
