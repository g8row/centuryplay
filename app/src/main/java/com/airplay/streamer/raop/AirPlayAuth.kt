package com.airplay.streamer.raop

import com.airplay.streamer.util.LogServer
import com.airplay.streamer.util.Tlv8
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * AirPlay 2 SRP-6a Authentication (Pair-Setup)
 * 
 * Key implementation details from shairport-sync source analysis:
 * - Uses 3072-bit SRP group (RFC 5054) with g=5
 * - k = H(PAD(N) | PAD(g)) - uses PADDED hashing
 * - u = H(PAD(A) | PAD(B)) - uses PADDED hashing
 * - x = H(salt | H(I:P)) - uses natural byte lengths
 * - M = H(H(N) XOR H(g) | H(I) | s | A | B | K) - uses natural byte lengths for s, A, B
 * 
 * For transient pairing (no PIN entry), use PIN "3939" and FLAGS_TRANSIENT (0x10)
 */
class AirPlayAuth {

    companion object {
        private const val TAG = "AirPlayAuth"
        
        // TLV8 Types for Pair-Setup (HomeKit Accessory Protocol)
        const val TLV_METHOD = 0x00
        const val TLV_IDENTIFIER = 0x01
        const val TLV_SALT = 0x02
        const val TLV_PUBLIC_KEY = 0x03
        const val TLV_PROOF = 0x04
        const val TLV_ENCRYPTED_DATA = 0x05
        const val TLV_STATE = 0x06
        const val TLV_ERROR = 0x07
        const val TLV_SIGNATURE = 0x0A
        const val TLV_FLAGS = 0x13
        
        // Pair-Setup Methods
        const val METHOD_PAIR_SETUP = 0x00
        const val METHOD_PAIR_SETUP_AUTH = 0x01
        const val METHOD_PAIR_VERIFY = 0x02
        
        // Pairing Flags
        const val FLAGS_TRANSIENT = 0x10
        
        // 3072-bit SRP Group parameters (RFC 5054, g=5 for Apple)
        private val N_3072 = BigInteger(
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B" +
            "139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485" +
            "B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7EDEE386BFB5A899FA5AE9F24117C4B1F" +
            "E649286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F83655D23" +
            "DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC9804F1746C08CA18217C32" +
            "905E462E36CE3BE39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF69558" +
            "17183995497CEA956AE515D2261898FA051015728E5A8AAAC42DAD33170D04507A33A85521" +
            "ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7ABF5AE8CDB0933D7" +
            "1E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864D87602733EC86A64521F2B1817" +
            "7B200CBBE117577A615D6C770988C0BAD946E208E24FA074E5AB3143DB5BFCE0FD108E4B82" +
            "D120A93AD2CAFFFFFFFFFFFFFFFF", 16
        )
        private val G = BigInteger.valueOf(5)
        private const val N_BYTES = 384  // 3072 bits = 384 bytes
    }

    private val random = SecureRandom()
    private val deviceId = generateDeviceId()

    // SRP Session State
    private var salt: ByteArray? = null
    private var a: BigInteger? = null    // Client private key
    private var A: BigInteger? = null    // Client public key
    private var B: BigInteger? = null    // Server public key
    private var S: BigInteger? = null    // Shared secret
    private var K: ByteArray? = null     // Session key
    private var M1: ByteArray? = null    // Client proof
    private var k: BigInteger? = null    // SRP k multiplier
    
    // Ed25519 Keys (for M5)
    private var ed25519PrivateKey: ByteArray? = null
    private var ed25519PublicKey: ByteArray? = null
    
    // Session encryption key
    private var sessionKey: ByteArray? = null

    init {
        // Pre-calculate k = H(PAD(N) | PAD(g))
        k = calculateK()
        
        // Generate Ed25519 key pair
        val (privKey, pubKey) = AirPlay2Crypto.generateEd25519KeyPair()
        ed25519PrivateKey = privKey
        ed25519PublicKey = pubKey
        
        LogServer.log("AirPlayAuth: Initialized with device ID: $deviceId")
    }
    
    private fun generateDeviceId(): String {
        val bytes = ByteArray(6)
        random.nextBytes(bytes)
        return bytes.joinToString(":") { "%02X".format(it) }
    }
    
    // ========== SHA-512 Helpers ==========
    
    private fun sha512(vararg data: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-512")
        for (d in data) {
            md.update(d)
        }
        return md.digest()
    }
    
    private fun sha512ToInt(vararg data: ByteArray): BigInteger {
        return BigInteger(1, sha512(*data))
    }
    
    // ========== Byte Conversion Helpers ==========
    
    /** Convert BigInteger to bytes with natural length (no leading zeros) */
    private fun intToBytes(x: BigInteger): ByteArray {
        val bytes = x.toByteArray()
        // Remove leading zero if present (BigInteger adds it for positive numbers)
        return if (bytes[0] == 0.toByte() && bytes.size > 1) {
            bytes.copyOfRange(1, bytes.size)
        } else {
            bytes
        }
    }
    
    /** Pad BigInteger to N_BYTES (384 bytes for 3072-bit) */
    private fun padToN(x: BigInteger): ByteArray {
        val bytes = intToBytes(x)
        return if (bytes.size < N_BYTES) {
            ByteArray(N_BYTES - bytes.size) + bytes
        } else {
            bytes
        }
    }
    
    // ========== SRP-6a Calculations ==========
    
    /** k = H(PAD(N) | PAD(g)) */
    private fun calculateK(): BigInteger {
        return sha512ToInt(padToN(N_3072), padToN(G))
    }
    
    /** u = H(PAD(A) | PAD(B)) */
    private fun calculateU(A: BigInteger, B: BigInteger): BigInteger {
        return sha512ToInt(padToN(A), padToN(B))
    }
    
    /**
     * x = H_ns(salt, H(I:P))
     * where H_ns(n, bytes) = H(n_bytes | bytes) using natural byte length of salt
     */
    private fun calculateX(salt: ByteArray, identity: ByteArray, password: ByteArray): BigInteger {
        val innerHash = sha512(identity, ":".toByteArray(), password)
        return sha512ToInt(salt, innerHash)
    }
    
    /**
     * M1 = H(H(N) XOR H(g) | H(I) | s | A | B | K)
     * 
     * IMPORTANT: N, g, s, A, B use NATURAL byte lengths (not padded!)
     * g=5 is just 1 byte, not 384 bytes!
     */
    private fun calculateM1(
        identity: ByteArray,
        salt: ByteArray,
        A: BigInteger,
        B: BigInteger,
        K: ByteArray
    ): ByteArray {
        // H(N) - natural byte length (384 bytes for 3072-bit N)
        val H_N = sha512(intToBytes(N_3072))
        
        // H(g) - natural byte length (1 byte for g=5)
        val H_g = sha512(intToBytes(G))
        
        // H(N) XOR H(g)
        val H_xor = ByteArray(64)
        for (i in 0 until 64) {
            H_xor[i] = (H_N[i].toInt() xor H_g[i].toInt()).toByte()
        }
        
        // H(I)
        val H_I = sha512(identity)
        
        // M1 = H(H_xor | H_I | salt | A | B | K)
        val md = MessageDigest.getInstance("SHA-512")
        md.update(H_xor)
        md.update(H_I)
        md.update(salt)             // Natural byte length (as received)
        md.update(intToBytes(A))    // Natural byte length
        md.update(intToBytes(B))    // Natural byte length
        md.update(K)
        
        return md.digest()
    }
    
    /**
     * M2 = H(A | M1 | K)
     */
    private fun calculateM2(A: BigInteger, M1: ByteArray, K: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-512")
        md.update(intToBytes(A))
        md.update(M1)
        md.update(K)
        return md.digest()
    }
    
    /** Generate client private key a and public key A = g^a mod N */
    private fun generateClientCredentials(): BigInteger {
        a = BigInteger(256, random)  // 256-bit private key
        A = G.modPow(a!!, N_3072)
        LogServer.log("AirPlayAuth: Generated A (${intToBytes(A!!).size} bytes)")
        return A!!
    }

    // ========== Pair-Setup Messages ==========

    /**
     * M1: Start Pair-Setup
     * Sends: State=M1, Method=PairSetup, Flags=Transient
     */
    fun createPairSetupM1(transient: Boolean = true): ByteArray {
        LogServer.log("AirPlayAuth: Creating M1 (pair-setup start, transient=$transient)")
        
        val tlv = Tlv8()
        tlv.add(TLV_STATE, 0x01.toByte())
        tlv.add(TLV_METHOD, METHOD_PAIR_SETUP.toByte())
        if (transient) {
            tlv.add(TLV_FLAGS, FLAGS_TRANSIENT.toByte())
        }
        
        val encoded = tlv.encode()
        LogServer.log("AirPlayAuth: M1 TLV8 (${encoded.size} bytes): ${encoded.joinToString("") { "%02x".format(it) }}")
        return encoded
    }

    /**
     * Parse M2 and Generate M3
     * 
     * M2 contains: State=M2, Salt, PublicKey (B)
     * M3 sends: State=M3, PublicKey (A), Proof (M1)
     */
    fun parseM2AndGenerateM3(m2Data: ByteArray, pin: String): ByteArray {
        LogServer.log("AirPlayAuth: Parsing M2 (${m2Data.size} bytes), PIN=$pin")
        
        val map = Tlv8.decode(m2Data)
        
        // Check for errors
        map[TLV_ERROR]?.let { error ->
            val errorCode = error[0].toInt()
            throw Exception("Server error in M2: $errorCode")
        }
        
        // Parse state (should be 0x02)
        val state = map[TLV_STATE]?.firstOrNull()?.toInt() ?: 0
        if (state != 0x02) {
            LogServer.log("AirPlayAuth: Unexpected state in M2: $state")
        }
        
        // Extract salt and server public key B
        salt = map[TLV_SALT] ?: throw Exception("Missing Salt in M2")
        val publicKeyB = map[TLV_PUBLIC_KEY] ?: throw Exception("Missing Public Key B in M2")
        
        LogServer.log("AirPlayAuth: M2 salt=${salt!!.size} bytes, B=${publicKeyB.size} bytes")
        
        B = BigInteger(1, publicKeyB)
        
        // Validate B
        if (B == BigInteger.ZERO || B!!.mod(N_3072) == BigInteger.ZERO) {
            throw Exception("Invalid server public key B")
        }
        
        // Identity is "Pair-Setup" (Apple convention)
        val identity = "Pair-Setup".toByteArray()
        val password = pin.toByteArray()
        
        // Generate client credentials A
        generateClientCredentials()
        
        // Calculate u = H(PAD(A) | PAD(B))
        val u = calculateU(A!!, B!!)
        if (u == BigInteger.ZERO) {
            throw Exception("Invalid u value")
        }
        LogServer.log("AirPlayAuth: u = ${u.toString(16).take(20)}...")
        
        // Calculate x = H(salt | H(I:P))
        val x = calculateX(salt!!, identity, password)
        LogServer.log("AirPlayAuth: x = ${x.toString(16).take(20)}...")
        
        // S = (B - k * g^x) ^ (a + u * x) mod N
        val gx = G.modPow(x, N_3072)
        val kgx = k!!.multiply(gx).mod(N_3072)
        val base = B!!.subtract(kgx).mod(N_3072)
        val exp = a!!.add(u.multiply(x))
        S = base.modPow(exp, N_3072)
        LogServer.log("AirPlayAuth: S = ${S!!.toString(16).take(20)}...")
        
        // K = H(S) - session key
        K = sha512(intToBytes(S!!))
        LogServer.log("AirPlayAuth: K = ${K!!.toHex().take(32)}...")
        
        // M1 = client proof
        M1 = calculateM1(identity, salt!!, A!!, B!!, K!!)
        LogServer.log("AirPlayAuth: M1 = ${M1!!.toHex().take(32)}...")
        
        // Build M3 response
        val aBytes = intToBytes(A!!)
        
        val tlv = Tlv8()
        tlv.add(TLV_STATE, 0x03.toByte())
        tlv.add(TLV_PUBLIC_KEY, aBytes)
        tlv.add(TLV_PROOF, M1!!)
        
        return tlv.encode()
    }
    
    /**
     * Parse M4 and Generate M5
     * 
     * M4 contains: State=M4, Proof (M2)
     * M5 sends: State=M5, EncryptedData
     */
    fun parseM4AndGenerateM5(m4Data: ByteArray): ByteArray {
        LogServer.log("AirPlayAuth: Parsing M4 (${m4Data.size} bytes)")
        
        val map = Tlv8.decode(m4Data)
        
        // Check for errors
        map[TLV_ERROR]?.let { error ->
            val errorCode = error[0].toInt()
            throw Exception("Server error in M4: $errorCode (Auth failed)")
        }
        
        val serverProof = map[TLV_PROOF] ?: throw Exception("Missing Server Proof in M4")
        
        // Verify server proof M2
        val expectedM2 = calculateM2(A!!, M1!!, K!!)
        
        if (!serverProof.contentEquals(expectedM2)) {
            LogServer.log("AirPlayAuth: Server proof verification FAILED")
            throw Exception("Server proof verification failed")
        }
        
        LogServer.log("AirPlayAuth: Server proof verified, creating M5")
        
        // Derive encryption key
        val encryptKey = AirPlay2Crypto.hkdf(
            salt = "Pair-Setup-Encrypt-Salt".toByteArray(),
            ikm = K!!,
            info = "Pair-Setup-Encrypt-Info".toByteArray(),
            length = 32
        )
        
        // Build data to sign
        val deviceIdBytes = deviceId.toByteArray()
        val dataToSign = ed25519PublicKey!! + deviceIdBytes
        val signature = AirPlay2Crypto.ed25519Sign(ed25519PrivateKey!!, dataToSign)
        
        // Build inner TLV
        val innerTlv = Tlv8()
        innerTlv.add(TLV_IDENTIFIER, deviceIdBytes)
        innerTlv.add(TLV_PUBLIC_KEY, ed25519PublicKey!!)
        innerTlv.add(TLV_SIGNATURE, signature)
        val innerData = innerTlv.encode()
        
        // Encrypt with ChaCha20-Poly1305
        val nonce = "PS-Msg05".toByteArray().copyOf(12)
        val encrypted = AirPlay2Crypto.chaCha20Poly1305EncryptWithNonce(encryptKey, nonce, innerData)
        
        // Build M5
        val tlv = Tlv8()
        tlv.add(TLV_STATE, 0x05.toByte())
        tlv.add(TLV_ENCRYPTED_DATA, encrypted)
        
        return tlv.encode()
    }
    
    /**
     * Parse M4 and finish (for transient pairing, no M5/M6 needed)
     */
    fun parseM4AndFinish(m4Data: ByteArray): ByteArray {
        LogServer.log("AirPlayAuth: Parsing M4 for transient finish (${m4Data.size} bytes)")
        
        val map = Tlv8.decode(m4Data)
        
        // Check for errors
        map[TLV_ERROR]?.let { error ->
            val errorCode = error[0].toInt()
            throw Exception("Server error in M4: $errorCode (Auth failed)")
        }
        
        val serverProof = map[TLV_PROOF] ?: throw Exception("Missing Server Proof in M4")
        
        // Verify server proof M2
        val expectedM2 = calculateM2(A!!, M1!!, K!!)
        
        if (!serverProof.contentEquals(expectedM2)) {
            throw Exception("Server Proof Invalid. Auth Failed.")
        }
        
        LogServer.log("AirPlayAuth: Transient pairing complete - server proof verified!")
        
        // Store session key
        sessionKey = K
        
        return ByteArray(0)
    }
    
    /**
     * Parse M6 (Final pairing response)
     */
    fun parseM6AndFinish(m6Data: ByteArray): Boolean {
        LogServer.log("AirPlayAuth: Parsing M6 (${m6Data.size} bytes)")
        
        val map = Tlv8.decode(m6Data)
        
        map[TLV_ERROR]?.let { error ->
            val errorCode = error[0].toInt()
            throw Exception("Server error in M6: $errorCode")
        }
        
        val encryptedData = map[TLV_ENCRYPTED_DATA] 
            ?: throw Exception("Missing encrypted data in M6")
        
        // Derive decryption key
        val decryptKey = AirPlay2Crypto.hkdf(
            salt = "Pair-Setup-Encrypt-Salt".toByteArray(),
            ikm = K!!,
            info = "Pair-Setup-Encrypt-Info".toByteArray(),
            length = 32
        )
        
        // Decrypt
        val nonce = "PS-Msg06".toByteArray().copyOf(12)
        val decrypted = AirPlay2Crypto.chaCha20Poly1305DecryptWithNonce(decryptKey, nonce, encryptedData)
            ?: throw Exception("Failed to decrypt M6 data")
        
        // Parse inner TLV
        val innerMap = Tlv8.decode(decrypted)
        val serverIdentifier = innerMap[TLV_IDENTIFIER]
        val serverPublicKey = innerMap[TLV_PUBLIC_KEY]
        val serverSignature = innerMap[TLV_SIGNATURE]
        
        LogServer.log("AirPlayAuth: M6 decrypted - server ID=${serverIdentifier?.size} bytes")
        
        // Store session key
        sessionKey = AirPlay2Crypto.hkdf(
            salt = "Pair-Setup-Controller-Sign-Salt".toByteArray(),
            ikm = K!!,
            info = "Pair-Setup-Controller-Sign-Info".toByteArray(),
            length = 32
        )
        
        LogServer.log("AirPlayAuth: Pair-Setup COMPLETE!")
        return true
    }
    
    // ========== Getters ==========
    
    fun getSessionKey(): ByteArray? = sessionKey
    fun getEd25519PublicKey(): ByteArray? = ed25519PublicKey
    fun getDeviceId(): String = deviceId
}

// Extension function for ByteArray to hex
private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
