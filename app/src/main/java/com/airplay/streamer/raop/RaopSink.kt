package com.airplay.streamer.raop

import com.airplay.streamer.engine.AudioPacket
import com.airplay.streamer.engine.AudioSink
import com.airplay.streamer.engine.SinkError
import com.airplay.streamer.engine.SinkException
import com.airplay.streamer.engine.StreamContext
import com.airplay.streamer.engine.TrackMetadata
import com.airplay.streamer.util.LogServer
import com.airplay.streamer.util.NtpClock
import java.math.BigInteger
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * AirPlay 1 (RAOP) receiver: RTSP control + RTP audio/control/timing over UDP.
 *
 * - Audio: ALAC (Apple's encoder via JNI) by default, L16 when the receiver lacks ALAC
 *   (TXT `cn`) or the user prefers PCM. AES-128-CBC with an RSA-wrapped key only when the
 *   receiver doesn't accept unencrypted audio (TXT `et` without 0).
 * - Timing: the receiver syncs its clock to ours via NTP-style requests on our timing port;
 *   sync packets (0xD4) map RTP time to [NtpClock] once a second, derived from the
 *   capture timeline so drift between the audio HAL and system clock is tracked.
 * - Reliability: lost packets are resent on request (control packets 0x55 → 0x56).
 * - Metadata: track info (DMAP), artwork and progress when the receiver advertises `md`.
 *
 * Protocol references: pyatv protocols/raop, shairport-sync rtsp.c/rtp.c, OwnTone raop.c.
 */
class RaopSink(
    private val host: String,
    private val port: Int,
    override val displayName: String,
    private val txt: Map<String, String>,
    private val preferPcm: Boolean = false,
    password: String? = null,
) : AudioSink {

    override val key: String = "raop:$host:$port"
    override var listener: AudioSink.Listener? = null
    @Volatile override var offsetMs: Int = 0

    // Shared DACP identity so receivers' remotes can reach our DacpServer.
    private val clientInstance = com.airplay.streamer.service.DacpServer.dacpId
    private val activeRemote = com.airplay.streamer.service.DacpServer.activeRemote
    private val sessionPath = Random.nextLong(0, Long.MAX_VALUE).toString()

    private val rtsp = RtspConnection(host, port, USER_AGENT) {
        mapOf("Client-Instance" to clientInstance, "DACP-ID" to clientInstance, "Active-Remote" to activeRemote)
    }.also { it.password = password }

    private lateinit var context: StreamContext
    private val address: InetAddress = InetAddress.getByName(host)

    private var audioSocket: DatagramSocket? = null
    private var controlSocket: DatagramSocket? = null
    private var timingSocket: DatagramSocket? = null
    private var serverAudioPort = 0
    private var serverControlPort = 0
    private var session: String? = null

    @Volatile private var running = false
    @Volatile private var intentionalStop = false

    // RTP state (capture thread)
    private val rtpBase = Random.nextLong(0, 0xFFFFFFFFL)
    private val ssrc = Random.nextInt()
    private var seq = Random.nextInt(0, 0xFFFF)
    private var firstPacket = true
    private var startFrame = -1L

    private val backlog = arrayOfNulls<ByteArray>(BACKLOG)

    // Encryption
    private val encryptionTypes = RaopCapabilities.encryptionTypes(txt).ifEmpty { setOf(0, 1) }
    private val useRsa = 0 !in encryptionTypes && 1 in encryptionTypes
    private val mfiAuthUpfront = 4 in encryptionTypes && (txt["am"] ?: "").startsWith("AirPort")
    private var aesKey: ByteArray? = null
    private var aesIv: ByteArray? = null
    private var aesCipher: Cipher? = null

    private val codecs = txt["cn"]?.split(",")?.mapNotNull { it.trim().toIntOrNull() }?.toSet()
    val useAlac: Boolean = !(preferPcm && (codecs == null || 0 in codecs)) && (codecs == null || 1 in codecs)

    private val metadataTypes = txt["md"]?.split(",")?.mapNotNull { it.trim().toIntOrNull() }?.toSet() ?: emptySet()
    private var lastMetadata: TrackMetadata? = null
    private var lastArtworkHash = 0

    // Stats
    private val packetsSent = AtomicLong()
    private val packetsResent = AtomicLong()
    private val bytesSent = AtomicLong()
    private var receiverLatency: Int? = null

    override fun connect(context: StreamContext) {
        this.context = context
        try {
            rtsp.open()
            // OPTIONS doubles as a password probe (401 here means the speaker is protected).
            val options = rtsp.request(
                "OPTIONS", "*", mapOf("Apple-Challenge" to Base64.getEncoder().withoutPadding().encodeToString(Random.nextBytes(16)))
            )
            checkAuth(options)
            if (mfiAuthUpfront) authSetup()

            audioSocket = DatagramSocket()
            controlSocket = DatagramSocket()
            timingSocket = DatagramSocket()
            startTimingResponder()

            var announce = rtsp.request("ANNOUNCE", uri(), mapOf("Content-Type" to "application/sdp"), buildSdp().toByteArray(Charsets.UTF_8))
            if (announce.code == 403 && 4 in encryptionTypes && !mfiAuthUpfront) {
                // Some et=4 receivers (Denon, per OwnTone raop.c) refuse ANNOUNCE until auth-setup.
                authSetup()
                announce = rtsp.request("ANNOUNCE", uri(), mapOf("Content-Type" to "application/sdp"), buildSdp().toByteArray(Charsets.UTF_8))
            }
            checkAuth(announce)
            expectOk(announce, "ANNOUNCE")

            val setup = rtsp.request(
                "SETUP", uri(),
                mapOf("Transport" to "RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;control_port=${controlSocket!!.localPort};timing_port=${timingSocket!!.localPort}")
            )
            expectOk(setup, "SETUP")
            session = setup.header("Session")?.substringBefore(";")?.trim()
            setup.header("Transport")?.split(";")?.forEach { part ->
                val p = part.trim()
                when {
                    p.startsWith("server_port=") -> serverAudioPort = p.substringAfter("=").toIntOrNull() ?: 0
                    p.startsWith("control_port=") -> serverControlPort = p.substringAfter("=").toIntOrNull() ?: 0
                }
            }
            if (serverAudioPort == 0) throw SinkException(SinkError.PROTOCOL, "SETUP returned no audio port")
            setup.header("Audio-Latency")?.toIntOrNull()?.let { receiverLatency = it }

            // The first packet we will send is the next one the capture thread produces.
            val nowFrame = if (context.timeline.isStarted) context.timeline.frameAt(NtpClock.nowNanos()) else 0L
            val firstFrame = (nowFrame / AudioPacket.FRAMES + 1) * AudioPacket.FRAMES
            val record = rtsp.request(
                "RECORD", uri(),
                sessionHeaders() + mapOf("Range" to "npt=0-", "RTP-Info" to "seq=$seq;rtptime=${rtp(firstFrame)}")
            )
            expectOk(record, "RECORD")
            record.header("Audio-Latency")?.toIntOrNull()?.let { receiverLatency = it }

            running = true
            startControlListener()
            startSyncSender()
            startHealthMonitor()
            LogServer.d(TAG, "$displayName: streaming ${if (useAlac) "ALAC" else "L16"}" +
                "${if (useRsa) " (AES)" else ""}, receiver latency=${receiverLatency ?: "?"}, metadata=$metadataTypes")
        } catch (e: SinkException) {
            closeQuietly()
            throw e
        } catch (e: ConnectException) {
            closeQuietly()
            throw SinkException(SinkError.UNREACHABLE, "Can't reach $displayName", e)
        } catch (e: NoRouteToHostException) {
            closeQuietly()
            throw SinkException(SinkError.UNREACHABLE, "Can't reach $displayName", e)
        } catch (e: SocketTimeoutException) {
            closeQuietly()
            throw SinkException(SinkError.UNREACHABLE, "$displayName is not responding", e)
        } catch (e: Exception) {
            closeQuietly()
            throw SinkException(SinkError.PROTOCOL, "Connection to $displayName failed: ${e.message}", e)
        }
    }

    private fun checkAuth(resp: RtspResponse) {
        if (resp.code == 401) {
            throw if (rtsp.password == null) SinkException(SinkError.AUTH_REQUIRED, "$displayName needs a password")
            else SinkException(SinkError.AUTH_FAILED, "Wrong password for $displayName")
        }
    }

    private fun expectOk(resp: RtspResponse, what: String) {
        when (resp.code) {
            200 -> return
            453 -> throw SinkException(SinkError.BUSY, "$displayName is busy (another device is playing)")
            401 -> checkAuth(resp)
        }
        throw SinkException(SinkError.PROTOCOL, "$what rejected: ${resp.code} ${resp.reason}")
    }

    /** MFiSAP auth-setup as pyatv support/rtsp.py: 0x01 + static Curve25519 key, unverified. */
    private fun authSetup() {
        val body = byteArrayOf(0x01) + AUTH_SETUP_PUBLIC_KEY
        val resp = rtsp.request("POST", "/auth-setup", mapOf("Content-Type" to "application/octet-stream"), body, protocol = "HTTP/1.1")
        LogServer.d(TAG, "auth-setup -> ${resp.code}")
    }

    private fun uri() = "rtsp://${rtsp.localIp}/$sessionPath"
    private fun sessionHeaders() = session?.let { mapOf("Session" to it) } ?: emptyMap()
    private fun rtp(frame: Long): Long = (rtpBase + frame) and 0xFFFFFFFFL

    private fun buildSdp(): String {
        val sb = StringBuilder()
        sb.append("v=0\r\n")
        sb.append("o=iTunes $sessionPath 0 IN IP4 ${rtsp.localIp}\r\n")
        sb.append("s=iTunes\r\n")
        sb.append("c=IN IP4 $host\r\n")
        sb.append("t=0 0\r\n")
        sb.append("m=audio 0 RTP/AVP 96\r\n")
        if (useAlac) {
            sb.append("a=rtpmap:96 AppleLossless\r\n")
            sb.append("a=fmtp:96 ${AudioPacket.FRAMES} 0 16 40 10 14 2 255 0 0 44100\r\n")
        } else {
            sb.append("a=rtpmap:96 L16/44100/2\r\n")
        }
        if (useRsa) {
            val key = ByteArray(16).also { SECURE_RANDOM.nextBytes(it) }
            val iv = ByteArray(16).also { SECURE_RANDOM.nextBytes(it) }
            aesKey = key
            aesIv = iv
            aesCipher = Cipher.getInstance("AES/CBC/NoPadding")
            val b64 = Base64.getEncoder().withoutPadding()
            sb.append("a=rsaaeskey:${b64.encodeToString(rsaEncrypt(key))}\r\n")
            sb.append("a=aesiv:${b64.encodeToString(iv)}\r\n")
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ audio (capture thread)

    override fun send(packet: AudioPacket) {
        if (!running) return
        if (startFrame < 0) startFrame = packet.frameIndex
        val payload = if (useAlac) context.alacOf(packet) else packet.bigEndianPcm()
        val body = if (useRsa) encrypt(payload) else payload
        val rtpPacket = ByteArray(12 + body.size)
        rtpPacket[0] = 0x80.toByte()
        rtpPacket[1] = if (firstPacket) 0xE0.toByte() else 0x60
        putShort(rtpPacket, 2, seq)
        putInt(rtpPacket, 4, rtp(packet.frameIndex))
        putInt(rtpPacket, 8, ssrc.toLong())
        System.arraycopy(body, 0, rtpPacket, 12, body.size)
        firstPacket = false
        backlog[seq and (BACKLOG - 1)] = rtpPacket
        try {
            audioSocket?.send(DatagramPacket(rtpPacket, rtpPacket.size, address, serverAudioPort))
            packetsSent.incrementAndGet()
            bytesSent.addAndGet(rtpPacket.size.toLong())
        } catch (e: Exception) {
            if (running) LogServer.d(TAG, "audio send failed: ${e.message}")
        }
        seq = (seq + 1) and 0xFFFF
    }

    private fun encrypt(data: ByteArray): ByteArray {
        val cipher = aesCipher ?: return data
        val whole = data.size / 16 * 16
        if (whole == 0) return data
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(aesIv))
        val out = data.copyOf()
        cipher.doFinal(data, 0, whole, out, 0)
        return out
    }

    private fun rsaEncrypt(key: ByteArray): ByteArray {
        val modulus = BigInteger(1, Base64.getDecoder().decode(RSA_MODULUS))
        val exponent = BigInteger(1, Base64.getDecoder().decode("AQAB"))
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent))
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        return cipher.doFinal(key)
    }

    // ------------------------------------------------------------------ UDP helpers

    private fun startTimingResponder() {
        val socket = timingSocket ?: return
        thread(name = "RaopTiming-$displayName", isDaemon = true) {
            val buf = ByteArray(128)
            val pkt = DatagramPacket(buf, buf.size)
            while (!socket.isClosed) {
                try {
                    socket.receive(pkt)
                    val recvNanos = NtpClock.nowNanos()
                    if (pkt.length < 32 || (buf[1].toInt() and 0x7F) != 0x52) continue
                    val resp = ByteArray(32)
                    resp[0] = 0x80.toByte()
                    resp[1] = 0xD3.toByte()
                    resp[2] = 0x00; resp[3] = 0x07
                    System.arraycopy(buf, 24, resp, 8, 8) // originate = their transmit time
                    NtpClock.writeNtpNanos(resp, 16, recvNanos)
                    NtpClock.writeNtpNanos(resp, 24, NtpClock.nowNanos())
                    socket.send(DatagramPacket(resp, resp.size, pkt.address, pkt.port))
                } catch (_: Exception) {
                    if (socket.isClosed) break
                }
            }
        }
    }

    private fun startControlListener() {
        val socket = controlSocket ?: return
        thread(name = "RaopControl-$displayName", isDaemon = true) {
            val buf = ByteArray(256)
            val pkt = DatagramPacket(buf, buf.size)
            while (!socket.isClosed) {
                try {
                    socket.receive(pkt)
                    if (pkt.length >= 8 && (buf[1].toInt() and 0x7F) == 0x55) {
                        val first = ((buf[4].toInt() and 0xFF) shl 8) or (buf[5].toInt() and 0xFF)
                        val count = ((buf[6].toInt() and 0xFF) shl 8) or (buf[7].toInt() and 0xFF)
                        resend(socket, pkt.address, pkt.port, first, count.coerceAtMost(BACKLOG))
                    }
                } catch (_: Exception) {
                    if (socket.isClosed) break
                }
            }
        }
    }

    private fun resend(socket: DatagramSocket, addr: InetAddress, port: Int, first: Int, count: Int) {
        for (i in 0 until count) {
            val s = (first + i) and 0xFFFF
            val original = backlog[s and (BACKLOG - 1)] ?: continue
            val originalSeq = ((original[2].toInt() and 0xFF) shl 8) or (original[3].toInt() and 0xFF)
            if (originalSeq != s) continue // overwritten by a newer packet
            val resp = ByteArray(4 + original.size)
            resp[0] = 0x80.toByte()
            resp[1] = 0xD6.toByte()
            putShort(resp, 2, s)
            System.arraycopy(original, 0, resp, 4, original.size)
            resp[5] = 0x60 // clear the marker bit in the embedded RTP header
            runCatching { socket.send(DatagramPacket(resp, resp.size, addr, port)) }
            packetsResent.incrementAndGet()
        }
    }

    private fun startSyncSender() {
        val socket = controlSocket ?: return
        if (serverControlPort == 0) return
        thread(name = "RaopSync-$displayName", isDaemon = true) {
            var first = true
            while (running && !socket.isClosed) {
                try {
                    if (context.timeline.isStarted) {
                        socket.send(DatagramPacket(syncPacket(first), 20, address, serverControlPort))
                        first = false
                    }
                    Thread.sleep(if (first) 20 else SYNC_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                } catch (_: Exception) {
                    if (socket.isClosed) break
                }
            }
        }
    }

    /**
     * 0xD4 sync: "rtptime[16] - latency plays at ntp[8]", where the receiver takes
     * latency = rtptime[16] - rtptime[4] + 11025 for flags 0x0007 (shairport-sync rtp.c,
     * iTunes behaviour). We put the frame being captured *now* in [16] so that
     * frame F plays exactly `latencyFrames` after it was captured.
     */
    private fun syncPacket(first: Boolean): ByteArray {
        val now = NtpClock.nowNanos()
        // Shifting the mapping by the offset makes this speaker play that much later/earlier.
        val current = context.timeline.frameAt(now) - offsetMs.toLong() * context.sampleRate / 1000
        val p = ByteArray(20)
        p[0] = if (first) 0x90.toByte() else 0x80.toByte()
        p[1] = 0xD4.toByte()
        p[2] = 0x00; p[3] = 0x07
        putInt(p, 4, rtp(current - (context.latencyFrames - ITUNES_EXTRA_LATENCY)))
        NtpClock.writeNtpNanos(p, 8, now)
        putInt(p, 16, rtp(current))
        return p
    }

    private fun startHealthMonitor() {
        thread(name = "RaopHealth-$displayName", isDaemon = true) {
            while (running) {
                try {
                    Thread.sleep(HEALTH_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
                if (!running) break
                if (rtsp.probeClosed()) {
                    if (running && !intentionalStop) {
                        LogServer.d(TAG, "$displayName closed the connection")
                        running = false
                        closeQuietly()
                        listener?.onSinkDisconnected(this, SinkError.DISCONNECTED, "$displayName disconnected")
                    }
                    break
                }
            }
        }
    }

    // ------------------------------------------------------------------ control

    override fun setVolume(volume: Float) {
        if (!running) return
        val db = volumeToDb(volume)
        val body = "volume: %.6f\r\n".format(java.util.Locale.US, db).toByteArray(Charsets.US_ASCII)
        runCatching {
            rtsp.request("SET_PARAMETER", uri(), sessionHeaders() + mapOf("Content-Type" to "text/parameters"), body, quiet = true)
        }.onFailure { LogServer.d(TAG, "volume failed: ${it.message}") }
    }

    override fun setMetadata(metadata: TrackMetadata) {
        if (!running || metadataTypes.isEmpty()) return
        LogServer.d(TAG, "$displayName: metadata '${metadata.title}' / '${metadata.artist}' art=${metadata.artwork?.size ?: 0}B")
        val now = context.timeline.frameAt(NtpClock.nowNanos()) - context.latencyFrames
        val rtpNow = rtp(now.coerceAtLeast(0))
        val rtpInfo = mapOf("RTP-Info" to "rtptime=$rtpNow")
        try {
            if (0 in metadataTypes && !metadata.sameTrack(lastMetadata)) {
                rtsp.request(
                    "SET_PARAMETER", uri(),
                    sessionHeaders() + rtpInfo + mapOf("Content-Type" to "application/x-dmap-tagged"),
                    Dmap.trackInfo(metadata.title, metadata.artist, metadata.album), quiet = true,
                )
            }
            val art = metadata.artwork
            if (1 in metadataTypes && art != null && art.contentHashCode() != lastArtworkHash) {
                lastArtworkHash = art.contentHashCode()
                rtsp.request(
                    "SET_PARAMETER", uri(),
                    sessionHeaders() + rtpInfo + mapOf("Content-Type" to "image/jpeg"), art, quiet = true,
                )
            }
            if (2 in metadataTypes && metadata.durationMs > 0) {
                val rate = context.sampleRate
                val start = rtp(now - metadata.positionMs * rate / 1000)
                val end = rtp(now - metadata.positionMs * rate / 1000 + metadata.durationMs * rate / 1000)
                rtsp.request(
                    "SET_PARAMETER", uri(),
                    sessionHeaders() + mapOf("Content-Type" to "text/parameters"),
                    "progress: $start/$rtpNow/$end\r\n".toByteArray(Charsets.US_ASCII), quiet = true,
                )
            }
            lastMetadata = metadata
        } catch (e: Exception) {
            LogServer.d(TAG, "metadata failed: ${e.message}")
        }
    }

    override fun disconnect() {
        intentionalStop = true
        val wasRunning = running
        running = false
        if (wasRunning) {
            runCatching {
                rtsp.setReadTimeout(2000)
                rtsp.request("TEARDOWN", uri(), sessionHeaders())
            }
        }
        closeQuietly()
    }

    private fun closeQuietly() {
        running = false
        rtsp.close()
        runCatching { audioSocket?.close() }
        runCatching { controlSocket?.close() }
        runCatching { timingSocket?.close() }
    }

    override fun stats(): String {
        val codec = if (useAlac) (if (context.alacEncoder.isNative) "ALAC" else "ALAC(raw)") else "PCM"
        return "$codec · ${packetsSent.get()} pkts · ${packetsResent.get()} resent"
    }

    companion object {
        private const val TAG = "RaopSink"
        private const val USER_AGENT = "iTunes/12.12 (Macintosh; OS X 13.0)"
        private const val BACKLOG = 1024 // power of two; ~8 s of audio
        private const val SYNC_INTERVAL_MS = 1000L
        private const val HEALTH_INTERVAL_MS = 2000L
        private const val ITUNES_EXTRA_LATENCY = 11025L
        private val SECURE_RANDOM = SecureRandom()

        /** Linear 0..1 to the RAOP -30..0 dB range (-144 = mute), as iTunes/pyatv. */
        fun volumeToDb(volume: Float): Float = if (volume <= 0.001f) -144f else -30f + 30f * volume.coerceIn(0f, 1f)

        // Apple's AirPort Express public key (shairport-sync super_secret_key).
        private const val RSA_MODULUS =
            "59dE8qLieItsH1WgjrcFRKj6eUWqi+bGLOX1HL3U3GhC/j0Qg90u3sG/1CUtwC" +
                "5vOYvfDmFI6oSFXi5ELabWJmT2dKHzBJKa3k9ok+8t9ucRqMd6DZHJ2YCCLlDR" +
                "KSKv6kDqnw4UwPdpOMXziC/AMj3Z/lUVX1G7WSHCAWKf1zNS1eLvqr+boEjXuB" +
                "OitnZ/bDzPHrTOZz0Dew0uowxf/+sG+NCK3eQJVxqcaJ/vEHKIVd2M+5qL71yJ" +
                "Q+87X6oV3eaYvt3zWZYD6z5vYTcrtij2VZ9Zmni/UAaHqn9JdsBWLUEpVviYnh" +
                "imNVvYFZeCXg/IdTQ+x4IRdiXNv5hEew=="

        // Static Curve25519 key for MFiSAP auth-setup (pyatv support/rtsp.py, OwnTone raop.c).
        private val AUTH_SETUP_PUBLIC_KEY = byteArrayOf(
            0x59, 0x02, 0xed.toByte(), 0xe9.toByte(), 0x0d, 0x4e, 0xf2.toByte(), 0xbd.toByte(),
            0x4c, 0xb6.toByte(), 0x8a.toByte(), 0x63, 0x30, 0x03, 0x82.toByte(), 0x07,
            0xa9.toByte(), 0x4d, 0xbd.toByte(), 0x50, 0xd8.toByte(), 0xaa.toByte(), 0x46, 0x5b,
            0x5d, 0x8c.toByte(), 0x01, 0x2a, 0x0c, 0x7e, 0x1d, 0x4e
        )

        private fun randomHex(bytes: Int) = Random.nextBytes(bytes).joinToString("") { "%02X".format(it) }

        internal fun putShort(b: ByteArray, o: Int, v: Int) {
            b[o] = (v shr 8).toByte(); b[o + 1] = v.toByte()
        }

        internal fun putInt(b: ByteArray, o: Int, v: Long) {
            b[o] = (v shr 24).toByte(); b[o + 1] = (v shr 16).toByte(); b[o + 2] = (v shr 8).toByte(); b[o + 3] = v.toByte()
        }
    }
}
