package com.airplay.streamer.airplay2

import com.airplay.streamer.util.LogServer
import com.airplay.streamer.util.Tlv8

/**
 * HAP transient Pair-Setup (PIN 3939), matching pyatv's
 * `protocols/airplay/auth/hap_transient.py`.
 *
 * Transient pairing only runs Pair-Setup M1–M4 (no Pair-Verify, no device
 * signatures), so it needs only SRP-6a + HKDF — no Ed25519/X25519. The resulting
 * SRP shared key is used to derive the control-channel encryption keys.
 *
 * On success the connection has encryption enabled and [sharedKey] holds the
 * 64-byte SRP key K (used later to derive event- and audio-channel keys).
 */
class HapTransientPairing(private val connection: AirPlay2Connection) {

    lateinit var sharedKey: ByteArray
        private set

    /** How long /pair-pin-start blocked — Macs hold it while their Accept prompt is shown. */
    var pinStartMillis: Long = 0
        private set

    /** Run the transient pairing exchange and enable control-channel encryption. */
    fun pair() {
        // Macs (and Apple TVs set to "ask") show an Accept prompt while this request is
        // pending, so give the user time to answer before timing out.
        connection.setReadTimeout(ACCEPT_TIMEOUT_MS)
        // M1 / pin-start
        val t0 = System.currentTimeMillis()
        connection.post("/pair-pin-start", HKP_HEADERS)
        pinStartMillis = System.currentTimeMillis() - t0

        // M1: start transient pair-setup
        val m1 = Tlv8().apply {
            add(TLV_METHOD, byteArrayOf(0x00))
            add(TLV_SEQNO, byteArrayOf(0x01))
            add(TLV_FLAGS, byteArrayOf(FLAG_TRANSIENT))
        }.encode()
        val m2Resp = connection.post("/pair-setup", HKP_HEADERS, m1)
        if (m2Resp.statusCode != 200) {
            // e.g. 403 from a Mac whose "Allow AirPlay for" is limited to the current user.
            throw IllegalStateException("pair-setup M1 rejected with HTTP ${m2Resp.statusCode} (receiver access control?)")
        }
        val m2 = Tlv8.decode(m2Resp.body)
        m2[TLV_ERROR]?.let {
            throw IllegalStateException("pair-setup M2 error: ${it.joinToString("") { b -> "%02x".format(b) }}")
        }

        val salt = m2[TLV_SALT] ?: throw IllegalStateException("M2 missing salt")
        val serverPublic = m2[TLV_PUBLIC_KEY] ?: throw IllegalStateException("M2 missing public key")

        // SRP: derive A, M1 proof, shared key K
        val srp = Srp6a(USERNAME, PIN)
        srp.process(salt, serverPublic)

        // M3: send client public key + proof
        val m3 = Tlv8().apply {
            add(TLV_SEQNO, byteArrayOf(0x03))
            add(TLV_PUBLIC_KEY, srp.publicKey())
            add(TLV_PROOF, srp.proof())
        }.encode()
        val m4Resp = connection.post("/pair-setup", HKP_HEADERS, m3)
        if (m4Resp.statusCode != 200) {
            throw IllegalStateException("pair-setup M3 rejected with HTTP ${m4Resp.statusCode}")
        }
        val m4 = Tlv8.decode(m4Resp.body)
        m4[TLV_ERROR]?.let {
            throw IllegalStateException("pair-setup M4 error: ${it.joinToString("") { b -> "%02x".format(b) }}")
        }

        sharedKey = srp.sharedKey()
        connection.setReadTimeout(DEFAULT_TIMEOUT_MS)

        // Derive control-channel keys and enable encryption from here on.
        val outKey = Hkdf.expand(CONTROL_SALT, CONTROL_WRITE_INFO, sharedKey)
        val inKey = Hkdf.expand(CONTROL_SALT, CONTROL_READ_INFO, sharedKey)
        connection.enableEncryption(outKey, inKey)
        LogServer.d(TAG, "Transient pairing complete; control channel encrypted")
    }

    companion object {
        private const val TAG = "HapTransientPairing"

        private const val ACCEPT_TIMEOUT_MS = 60_000
        private const val DEFAULT_TIMEOUT_MS = 10_000
        private const val USERNAME = "Pair-Setup"
        private const val PIN = "3939"

        // HAP TLV8 tags (see pyatv auth/hap_tlv8.py)
        private const val TLV_METHOD = 0x00
        private const val TLV_SALT = 0x02
        private const val TLV_PUBLIC_KEY = 0x03
        private const val TLV_PROOF = 0x04
        private const val TLV_ERROR = 0x07
        private const val TLV_SEQNO = 0x06
        private const val TLV_FLAGS = 0x13
        private const val FLAG_TRANSIENT: Byte = 0x10

        private const val CONTROL_SALT = "Control-Salt"
        private const val CONTROL_WRITE_INFO = "Control-Write-Encryption-Key"
        private const val CONTROL_READ_INFO = "Control-Read-Encryption-Key"

        private val HKP_HEADERS = mapOf(
            "User-Agent" to "AirPlay/550.10",
            "Connection" to "keep-alive",
            "X-Apple-HKP" to "4",
            "Content-Type" to "application/octet-stream",
        )
    }
}
