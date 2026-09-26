package com.airplay.streamer.airplay2

import com.airplay.streamer.util.LogServer
import com.airplay.streamer.util.Tlv8
import org.bouncycastle.math.ec.rfc7748.X25519
import org.bouncycastle.math.ec.rfc8032.Ed25519
import java.security.SecureRandom
import java.util.UUID

/** Long-term HAP pairing with one receiver (what pyatv calls HapCredentials). */
data class HapCredentials(
    val clientId: ByteArray,
    val clientSecret: ByteArray, // Ed25519 seed (32 bytes)
    val serverId: ByteArray,
    val serverPublic: ByteArray, // Ed25519 public key (32 bytes)
) {
    fun serialize(): String = listOf(clientId, clientSecret, serverId, serverPublic).joinToString(":") { hex(it) }

    companion object {
        fun parse(s: String?): HapCredentials? {
            val parts = s?.split(":") ?: return null
            if (parts.size != 4) return null
            return runCatching {
                HapCredentials(unhex(parts[0]), unhex(parts[1]), unhex(parts[2]), unhex(parts[3]))
            }.getOrNull()
        }

        private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
        private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}

/** Where long-term pairings are kept (per receiver identity). */
interface HapCredentialStore {
    fun load(identity: String): HapCredentials?
    fun save(identity: String, credentials: HapCredentials)
    fun clear(identity: String)
}

class PairingException(message: String, val wrongPin: Boolean = false) : Exception(message)

/**
 * Normal (non-transient) HAP Pair-Setup with a PIN or password, then Pair-Verify, mirroring
 * pyatv `auth/hap_srp.py` (SRPAuthHandler) and `protocols/airplay/auth/hap.py`
 * (AirPlayHapPairSetupProcedure / AirPlayHapPairVerifyProcedure).
 *
 * Used for receivers that don't accept transient pairing: Apple TVs (PIN shown on the TV
 * after /pair-pin-start), HomePods/Macs protected by a password, and to let a Mac remember us
 * instead of asking every time.
 */
class HapPairing(private val connection: AirPlay2Connection) {

    private val random = SecureRandom()
    private var salt: ByteArray? = null
    private var serverSrpPublic: ByteArray? = null

    /** Step 1: shows the PIN on screen (Apple TV) and runs M1/M2. */
    fun startSetup() {
        // Macs/Apple TVs hold this request while their prompt is on screen.
        connection.setReadTimeout(60_000)
        connection.post("/pair-pin-start", HEADERS)
        connection.setReadTimeout(10_000)
        val m1 = Tlv8().apply {
            add(TLV_METHOD, byteArrayOf(0x00))
            add(TLV_SEQNO, byteArrayOf(0x01))
        }.encode()
        val resp = connection.post("/pair-setup", HEADERS, m1)
        if (resp.statusCode != 200) throw PairingException("pair-setup M1 rejected: HTTP ${resp.statusCode}")
        val m2 = Tlv8.decode(resp.body)
        m2[TLV_ERROR]?.let { throw PairingException("pair-setup M2 error ${it.firstOrNull()}") }
        salt = m2[TLV_SALT] ?: throw PairingException("M2 missing salt")
        serverSrpPublic = m2[TLV_PUBLIC_KEY] ?: throw PairingException("M2 missing public key")
    }

    /** Steps M3–M6 with the PIN/password; returns credentials to store. */
    fun finishSetup(pin: String): HapCredentials {
        val srp = Srp6a("Pair-Setup", pin)
        srp.process(salt ?: error("startSetup first"), serverSrpPublic!!)
        val m3 = Tlv8().apply {
            add(TLV_SEQNO, byteArrayOf(0x03))
            add(TLV_PUBLIC_KEY, srp.publicKey())
            add(TLV_PROOF, srp.proof())
        }.encode()
        val m4 = Tlv8.decode(connection.post("/pair-setup", HEADERS, m3).body)
        m4[TLV_ERROR]?.let { throw PairingException("wrong code", wrongPin = true) }
        val k = srp.sharedKey()

        // M5: our long-term Ed25519 identity, signed, encrypted with the setup session key.
        val seed = ByteArray(32).also { random.nextBytes(it) }
        val ltpk = ByteArray(32).also { Ed25519.generatePublicKey(seed, 0, it, 0) }
        val clientId = UUID.randomUUID().toString().toByteArray(Charsets.UTF_8)
        val controllerX = Hkdf.expand("Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info", k)
        val sessionKey = Hkdf.expand("Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info", k)
        val signature = sign(seed, controllerX + clientId + ltpk)
        val inner = Tlv8().apply {
            add(TLV_IDENTIFIER, clientId)
            add(TLV_PUBLIC_KEY, ltpk)
            add(TLV_SIGNATURE, signature)
        }.encode()
        val cipher = Chacha20Poly1305(sessionKey, sessionKey)
        val m5 = Tlv8().apply {
            add(TLV_SEQNO, byteArrayOf(0x05))
            add(TLV_ENCRYPTED_DATA, cipher.encrypt(inner, nonce = "PS-Msg05".toByteArray()))
        }.encode()
        val m6 = Tlv8.decode(connection.post("/pair-setup", HEADERS, m5).body)
        m6[TLV_ERROR]?.let { throw PairingException("pair-setup M6 error ${it.firstOrNull()}") }
        val encrypted = m6[TLV_ENCRYPTED_DATA] ?: throw PairingException("M6 missing data")
        val server = Tlv8.decode(cipher.decrypt(encrypted, nonce = "PS-Msg06".toByteArray()))
        val serverId = server[TLV_IDENTIFIER] ?: throw PairingException("M6 missing identifier")
        val serverLtpk = server[TLV_PUBLIC_KEY] ?: throw PairingException("M6 missing public key")
        LogServer.d(TAG, "Pair-Setup complete; paired with ${String(serverId)}")
        return HapCredentials(clientId, seed, serverId, serverLtpk)
    }

    /**
     * Pair-Verify with stored credentials. Enables control-channel encryption and returns the
     * X25519 shared secret (base for event/audio keys, like the transient SRP key).
     */
    fun verify(creds: HapCredentials): ByteArray {
        val ephemeral = ByteArray(32).also { random.nextBytes(it) }
        val ourPublic = ByteArray(32).also { X25519.scalarMultBase(ephemeral, 0, it, 0) }
        val v1 = Tlv8().apply {
            add(TLV_SEQNO, byteArrayOf(0x01))
            add(TLV_PUBLIC_KEY, ourPublic)
        }.encode()
        val r1 = connection.post("/pair-verify", HEADERS, v1)
        if (r1.statusCode != 200) throw PairingException("pair-verify rejected: HTTP ${r1.statusCode}")
        val m2 = Tlv8.decode(r1.body)
        m2[TLV_ERROR]?.let { throw PairingException("pair-verify M2 error ${it.firstOrNull()}") }
        val serverPublic = m2[TLV_PUBLIC_KEY] ?: throw PairingException("verify M2 missing key")
        val encrypted = m2[TLV_ENCRYPTED_DATA] ?: throw PairingException("verify M2 missing data")

        val shared = ByteArray(32)
        if (!X25519.calculateAgreement(ephemeral, 0, serverPublic, 0, shared, 0)) throw PairingException("bad server key")
        val sessionKey = Hkdf.expand("Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info", shared)
        val cipher = Chacha20Poly1305(sessionKey, sessionKey)
        val inner = Tlv8.decode(cipher.decrypt(encrypted, nonce = "PV-Msg02".toByteArray()))
        val id = inner[TLV_IDENTIFIER] ?: throw PairingException("verify: no identifier")
        val sig = inner[TLV_SIGNATURE] ?: throw PairingException("verify: no signature")
        if (!id.contentEquals(creds.serverId)) throw PairingException("verify: different device")
        val signed = serverPublic + id + ourPublic
        if (!Ed25519.verify(sig, 0, creds.serverPublic, 0, signed, 0, signed.size)) {
            throw PairingException("verify: bad device signature")
        }

        val ourSig = sign(creds.clientSecret, ourPublic + creds.clientId + serverPublic)
        val m3inner = Tlv8().apply {
            add(TLV_IDENTIFIER, creds.clientId)
            add(TLV_SIGNATURE, ourSig)
        }.encode()
        val v3 = Tlv8().apply {
            add(TLV_SEQNO, byteArrayOf(0x03))
            add(TLV_ENCRYPTED_DATA, cipher.encrypt(m3inner, nonce = "PV-Msg03".toByteArray()))
        }.encode()
        val r3 = connection.post("/pair-verify", HEADERS, v3)
        Tlv8.decode(r3.body)[TLV_ERROR]?.let { throw PairingException("pair-verify M4 error ${it.firstOrNull()}") }

        connection.enableEncryption(
            Hkdf.expand("Control-Salt", "Control-Write-Encryption-Key", shared),
            Hkdf.expand("Control-Salt", "Control-Read-Encryption-Key", shared),
        )
        LogServer.d(TAG, "Pair-Verify complete; control channel encrypted")
        return shared
    }

    private fun sign(seed: ByteArray, message: ByteArray): ByteArray =
        ByteArray(64).also { Ed25519.sign(seed, 0, message, 0, message.size, it, 0) }

    companion object {
        private const val TAG = "HapPairing"
        private const val TLV_METHOD = 0x00
        private const val TLV_IDENTIFIER = 0x01
        private const val TLV_SALT = 0x02
        private const val TLV_PUBLIC_KEY = 0x03
        private const val TLV_PROOF = 0x04
        private const val TLV_ENCRYPTED_DATA = 0x05
        private const val TLV_SEQNO = 0x06
        private const val TLV_ERROR = 0x07
        private const val TLV_SIGNATURE = 0x0A

        // pyatv protocols/airplay/auth/hap.py _AIRPLAY_HEADERS (HKP 3 = system pairing)
        private val HEADERS = mapOf(
            "User-Agent" to "AirPlay/320.20",
            "Connection" to "keep-alive",
            "X-Apple-HKP" to "3",
            "Content-Type" to "application/octet-stream",
        )
    }
}
