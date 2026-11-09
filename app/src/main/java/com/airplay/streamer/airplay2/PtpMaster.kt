package com.airplay.streamer.airplay2

import com.airplay.streamer.util.LogServer
import com.airplay.streamer.util.NtpClock
import java.net.BindException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Minimal PTP (IEEE 1588v2, unicast, two-step) grandmaster for AirPlay 2 senders: we always
 * become the master clock and receivers (HomePod, Sonos, shairport-sync/nqptp) follow us.
 *
 * Port of OwnTone's libairptp (src/libairptp/src/ptp_msg_handle.c and daemon.c, MIT licence,
 * Copyright (c) 2026 OwnTone), which in turn mirrors what iOS sends:
 *  - Sync (two-step, zero timestamp) to the peer's event port 319 every 125 ms, then a
 *    Follow_Up with the precise time and Apple's TLVs to its general port 320;
 *  - Announce (we claim a GPS-class clock so we win the best-master election) and an Apple
 *    Signaling message to port 320 every second;
 *  - Delay_Resp / Pdelay_Resp answers to receivers that measure path delay.
 *
 * The time base is [NtpClock.nowNanos], the same clock as the capture timeline, so the 0xD7
 * time-announce packets of the audio stream map RTP frames onto this PTP time.
 *
 * Ports must be 319/320 on our side too: nqptp drops PTP messages whose source port differs
 * from the destination port (nqptp.c "the sending and receiving ports must be the same").
 * Android normally reserves ports below 1024 for privileged processes; if binding fails we
 * still send from ephemeral ports and log it, so the diagnostics show why a receiver stays
 * silent.
 */
object PtpMaster {
    private const val TAG = "PtpMaster"
    private const val EVENT_PORT = 319
    private const val GENERAL_PORT = 320
    private const val SYNC_INTERVAL_MS = 125L
    private const val ANNOUNCE_EVERY = 8 // ticks: once a second

    private const val MSG_SYNC = 0x0
    private const val MSG_DELAY_REQ = 0x1
    private const val MSG_PDELAY_REQ = 0x2
    private const val MSG_PDELAY_RESP = 0x3
    private const val MSG_FOLLOW_UP = 0x8
    private const val MSG_DELAY_RESP = 0x9
    private const val MSG_PDELAY_RESP_FOLLOW_UP = 0xA
    private const val MSG_ANNOUNCE = 0xB
    private const val MSG_SIGNALING = 0xC

    private const val FLAG_TIMESCALE = 1 shl 3
    private const val FLAG_TWO_STEP = 1 shl 9
    private const val FLAG_UNICAST = 1 shl 10

    private val ORG_IEEE = byteArrayOf(0x00, 0x80.toByte(), 0xC2.toByte())
    private val ORG_APPLE = byteArrayOf(0x00, 0x0D, 0x93.toByte())

    /** Our clock identity (random, like OwnTone; iOS derives it from its MAC address). */
    val clockId: Long = Random.nextLong()

    /** Stable per process, sent as timingPeerInfo.ID (iOS sends a UUID too). */
    val clockUuid: String = java.util.UUID.randomUUID().toString().uppercase()

    private val peers = ConcurrentHashMap<InetAddress, Int>() // address → number of sessions using it
    private val lock = Any()
    private var eventSocket: DatagramSocket? = null
    private var generalSocket: DatagramSocket? = null
    @Volatile private var running = false
    @Volatile private var generation = 0

    private var syncSeq = 0
    private var announceSeq = 0
    private var signalingSeq = 0

    /** Messages received from receivers (diagnostics). */
    val received = AtomicInteger()
    @Volatile var boundStandardPorts = false
        private set

    fun addPeer(address: InetAddress) {
        synchronized(lock) {
            peers.merge(address, 1, Int::plus)
            if (!running) start()
        }
        LogServer.d(TAG, "peer added ${address.hostAddress} (${peers.size} peers)")
        // Like libairptp: announce right away instead of waiting for the next second.
        sendAnnounceAndSignaling()
    }

    fun removePeer(address: InetAddress) {
        synchronized(lock) {
            val left = peers.compute(address) { _, n -> if (n == null || n <= 1) null else n - 1 }
            if (left == null) LogServer.d(TAG, "peer removed ${address.hostAddress}")
            if (peers.isEmpty()) stop()
        }
    }

    private fun start() {
        val (event, std1) = bind(EVENT_PORT)
        val (general, std2) = bind(GENERAL_PORT)
        eventSocket = event
        generalSocket = general
        boundStandardPorts = std1 && std2
        running = true
        LogServer.d(
            TAG, "started, clock id %016x, %s".format(
                clockId,
                if (boundStandardPorts) "ports 319/320" else "ephemeral ports ${event.localPort}/${general.localPort}: binding 319/320 was refused, receivers like nqptp will ignore us",
            )
        )
        val gen = ++generation
        thread(name = "PtpSender", isDaemon = true) { senderLoop(gen) }
        thread(name = "PtpEvent", isDaemon = true) { receiveLoop(event) }
        thread(name = "PtpGeneral", isDaemon = true) { receiveLoop(general) }
    }

    private fun stop() {
        running = false
        runCatching { eventSocket?.close() }
        runCatching { generalSocket?.close() }
        eventSocket = null
        generalSocket = null
        LogServer.d(TAG, "stopped (received ${received.get()} messages)")
    }

    private fun bind(port: Int): Pair<DatagramSocket, Boolean> = try {
        DatagramSocket(port) to true
    } catch (_: BindException) {
        DatagramSocket() to false
    } catch (_: SocketException) {
        DatagramSocket() to false
    }

    // ------------------------------------------------------------------ sending

    private fun senderLoop(gen: Int) {
        var tick = 0
        while (running && gen == generation) {
            try {
                sendSync()
                if (++tick % ANNOUNCE_EVERY == 0) sendAnnounceAndSignaling()
                Thread.sleep(SYNC_INTERVAL_MS)
            } catch (_: InterruptedException) {
                break
            } catch (e: Exception) {
                if (running) LogServer.d(TAG, "send failed: ${e.message}")
            }
        }
    }

    /** Two-step: Sync with a zero timestamp, then Follow_Up carrying the time the Sync left. */
    private fun sendSync() {
        val seq = syncSeq
        syncSeq = (syncSeq + 1) and 0xFFFF
        val sync = header(MSG_SYNC, 44, FLAG_UNICAST or FLAG_TIMESCALE or FLAG_TWO_STEP, seq, logInterval = -3)
        val sentAt = NtpClock.nowNanos()
        toPeers(eventSocket, EVENT_PORT, sync)

        val followUp = header(MSG_FOLLOW_UP, 96, FLAG_UNICAST or FLAG_TIMESCALE, seq, logInterval = -3)
        putTimestamp(followUp, 34, sentAt)
        // TLV 1: IEEE 802.1AS Follow_Up information (cumulativeScaledRateOffset etc., all zero).
        orgTlv(followUp, 44, 28, ORG_IEEE, byteArrayOf(0, 0, 1))
        // TLV 2: Apple clock ID TLV.
        orgTlv(followUp, 76, 16, ORG_APPLE, byteArrayOf(0, 0, 4))
        putLong(followUp, 86, clockId)
        toPeers(generalSocket, GENERAL_PORT, followUp)
    }

    private fun sendAnnounceAndSignaling() {
        if (!running) return
        val announce = header(MSG_ANNOUNCE, 76, FLAG_UNICAST or FLAG_TIMESCALE, announceSeq, logInterval = 0)
        announceSeq = (announceSeq + 1) and 0xFFFF
        // originTimestamp 0 (as iOS), currentUtcOffset 0, reserved 0
        announce[47] = 128.toByte() // grandmasterPriority1
        putInt(announce, 48, 0x0621436A) // clock class 6 (GPS), accuracy 100 ns, Apple's variance
        announce[52] = 128.toByte() // grandmasterPriority2
        putLong(announce, 53, clockId) // grandmasterIdentity
        // stepsRemoved 0
        announce[63] = 0x20 // timeSource GPS
        putShort(announce, 64, 0x0008) // path trace TLV with our clock ID (iOS does this too)
        putShort(announce, 66, 8)
        putLong(announce, 68, clockId)
        toPeers(generalSocket, GENERAL_PORT, announce)

        val signaling = header(MSG_SIGNALING, 106, FLAG_UNICAST or FLAG_TIMESCALE, signalingSeq, logInterval = -128)
        signalingSeq = (signalingSeq + 1) and 0xFFFF
        signaling[32] = 0x05 // controlField: other message
        // targetPortIdentity (34..43) zero, then two Apple TLVs with a fixed, unexplained value.
        val appleUnknown = byteArrayOf(0x00, 0x00, 0x03, 0x01)
        orgTlv(signaling, 44, 22, ORG_APPLE, byteArrayOf(0, 0, 1))
        System.arraycopy(appleUnknown, 0, signaling, 54, 4)
        orgTlv(signaling, 70, 32, ORG_APPLE, byteArrayOf(0, 0, 5))
        System.arraycopy(appleUnknown, 0, signaling, 80, 4)
        toPeers(generalSocket, GENERAL_PORT, signaling)
    }

    private fun toPeers(socket: DatagramSocket?, port: Int, msg: ByteArray) {
        val s = socket ?: return
        for (address in peers.keys) {
            runCatching { s.send(DatagramPacket(msg, msg.size, address, port)) }
        }
    }

    // ------------------------------------------------------------------ receiving

    private fun receiveLoop(socket: DatagramSocket) {
        val buf = ByteArray(512)
        val pkt = DatagramPacket(buf, buf.size)
        while (running && !socket.isClosed) {
            try {
                socket.receive(pkt)
                val at = NtpClock.nowNanos()
                if (pkt.length < 34) continue
                if (received.incrementAndGet() == 1) {
                    LogServer.d(TAG, "first message from ${pkt.address.hostAddress}:${pkt.port} type 0x%x".format(buf[0].toInt() and 0x0F))
                }
                when (buf[0].toInt() and 0x0F) {
                    MSG_DELAY_REQ -> answer(MSG_DELAY_RESP, FLAG_UNICAST or FLAG_TIMESCALE or FLAG_TWO_STEP, buf, at, pkt.address, GENERAL_PORT, generalSocket)
                    MSG_PDELAY_REQ -> {
                        answer(MSG_PDELAY_RESP, FLAG_UNICAST or FLAG_TIMESCALE or FLAG_TWO_STEP, buf, at, pkt.address, EVENT_PORT, eventSocket)
                        answer(MSG_PDELAY_RESP_FOLLOW_UP, FLAG_UNICAST or FLAG_TIMESCALE, buf, NtpClock.nowNanos(), pkt.address, GENERAL_PORT, generalSocket)
                    }
                    // Announce/Sync from others: we claim the best clock, so ignore (as libairptp).
                    else -> Unit
                }
            } catch (_: Exception) {
                if (!running || socket.isClosed) break
            }
        }
    }

    /** Delay_Resp / Pdelay_Resp(_Follow_Up): our timestamp + the requester's port identity. */
    private fun answer(type: Int, flags: Int, req: ByteArray, at: Long, to: InetAddress, port: Int, socket: DatagramSocket?) {
        val seq = ((req[30].toInt() and 0xFF) shl 8) or (req[31].toInt() and 0xFF)
        val msg = header(type, 54, flags, seq, logInterval = -3)
        putTimestamp(msg, 34, at)
        System.arraycopy(req, 20, msg, 44, 10) // requestingPortIdentity
        runCatching { socket?.send(DatagramPacket(msg, msg.size, to, port)) }
    }

    // ------------------------------------------------------------------ encoding

    /** 34-byte PTPv2 header as libairptp header_init(). */
    private fun header(type: Int, length: Int, flags: Int, seq: Int, logInterval: Int): ByteArray {
        val b = ByteArray(length)
        b[0] = (type or 0x10).toByte() // transportSpecific 1, which nqptp expects
        b[1] = 0x02 // PTPv2
        putShort(b, 2, length)
        b[4] = 0 // domain
        putShort(b, 6, flags)
        // correctionField (8..15) and reserved (16..19) zero
        putLong(b, 20, clockId) // sourcePortIdentity: clock ID + port number 0x8005, as iOS
        b[28] = 0x80.toByte(); b[29] = 0x05
        putShort(b, 30, seq)
        b[32] = 0x00
        b[33] = logInterval.toByte()
        return b
    }

    /** Organization extension TLV (type 3) header: type, length, org code, subtype. */
    private fun orgTlv(b: ByteArray, at: Int, valueLength: Int, org: ByteArray, subtype: ByteArray) {
        putShort(b, at, 0x0003)
        putShort(b, at + 2, valueLength)
        System.arraycopy(org, 0, b, at + 4, 3)
        System.arraycopy(subtype, 0, b, at + 7, 3)
    }

    /** PTP timestamp: 48-bit seconds + 32-bit nanoseconds. */
    private fun putTimestamp(b: ByteArray, at: Int, nanos: Long) {
        val seconds = nanos / 1_000_000_000L
        putShort(b, at, ((seconds ushr 32) and 0xFFFF).toInt())
        putInt(b, at + 2, (seconds and 0xFFFFFFFFL).toInt())
        putInt(b, at + 6, (nanos % 1_000_000_000L).toInt())
    }

    private fun putShort(b: ByteArray, at: Int, v: Int) {
        b[at] = (v ushr 8).toByte(); b[at + 1] = v.toByte()
    }

    private fun putInt(b: ByteArray, at: Int, v: Int) {
        for (i in 0 until 4) b[at + i] = (v ushr (24 - 8 * i)).toByte()
    }

    private fun putLong(b: ByteArray, at: Int, v: Long) {
        for (i in 0 until 8) b[at + i] = (v ushr (56 - 8 * i)).toByte()
    }
}
