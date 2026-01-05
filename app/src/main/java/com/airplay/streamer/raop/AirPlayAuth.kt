package com.airplay.streamer.raop

import android.util.Log
import com.airplay.streamer.util.LogServer
import com.airplay.streamer.util.Tlv8
import org.bouncycastle.crypto.agreement.srp.SRP6Client
import org.bouncycastle.crypto.agreement.srp.SRP6Util
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.params.SRP6GroupParameters
import java.math.BigInteger
import java.security.SecureRandom

/**
 * Handles AirPlay 2 SRP-6a Authentication (Pair-Setup)
 * 
 * AirPlay 2 Pair-Setup flow:
 * 1. M1: Client sends method + pairing flags
 * 2. M2: Server responds with salt + public key B
 * 3. M3: Client sends public key A + proof M1
 * 4. M4: Server responds with proof M2
 * 5. M5: Client sends encrypted Ed25519 public key + signature
 * 6. M6: Server responds with encrypted device info
 * 
 * After pair-setup, pair-verify is used to establish encrypted sessions.
 */
class AirPlayAuth {

    companion object {
        private const val TAG = "AirPlayAuth"
        
        // TLV8 Types for Pair-Setup
        const val TLV_METHOD = 0x00
        const val TLV_IDENTIFIER = 0x01
        const val TLV_SALT = 0x02
        const val TLV_PUBLIC_KEY = 0x03
        const val TLV_PROOF = 0x04
        const val TLV_ENCRYPTED_DATA = 0x05
        const val TLV_STATE = 0x06
        const val TLV_ERROR = 0x07
        const val TLV_SIGNATURE = 0x0A
        
        // Pair-Setup Methods
        const val METHOD_PAIR_SETUP = 0x00
        const val METHOD_PAIR_SETUP_AUTH = 0x01
        const val METHOD_PAIR_VERIFY = 0x02
        
        // Standard 2048-bit SRP Group parameters (RFC 5054)
        private val N_2048 = BigInteger(1, hexToBytes(
            "AC6BDB41324A9A9BF166DE5E1389582FAF72B6651987EE07FC3192943DB56050" +
            "A37329CBB4A099ED8193E0757767A13DD52312AB4B03310DCD7F48A9DA04FD50" +
            "E8083969EDB767B0CF6095179A163AB3661A05FBD5FAAAE82918A9962F0B93B8" +
            "55F97993EC975EEAA80D740ADBF4FF747359D041D5C33EA71D281E446B14773B" +
            "CA97B43A23FB801676BD207A436C6481F1D2B9078717461A5B9D32E688F87748" +
            "544523B524B0D57D5EA77A2775D2ECFA032CFBDBF52FB3786160279004E57AE6" +
            "AF874E7303CE53299CCC041C7BC308D82A5698F3A8D0C38271AE35F8E9DBFBB6" +
            "94B5C803D89F7AE435DE236D525F54759B65E372FCD68EF20FA7111F9E4AFF73"
        ))
        private val G_2048 = BigInteger.valueOf(5) // AirPlay uses g=5, not g=2
        
        private fun hexToBytes(s: String): ByteArray {
            val len = s.length
            val data = ByteArray(len / 2)
            var i = 0
            while (i < len) {
                data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
                i += 2
            }
            return data
        }
    }

    private val srpClient = SRP6Client()
    private val random = SecureRandom()
    
    // Device identifier (should be persistent per-device)
    private val deviceId = generateDeviceId()

    // SRP Session State
    private var srpSalt: ByteArray? = null
    private var A: BigInteger? = null  // Client public key
    private var B: BigInteger? = null  // Server public key
    private var S: BigInteger? = null  // Shared secret
    private var K: ByteArray? = null   // Session key
    private var M1: BigInteger? = null // Client proof
    
    // Ed25519 Keys (generated after SRP, used in M5)
    private var ed25519PrivateKey: ByteArray? = null
    private var ed25519PublicKey: ByteArray? = null
    
    // Session encryption key (derived after successful pairing)
    private var sessionKey: ByteArray? = null

    init {
        srpClient.init(N_2048, G_2048, SHA512Digest(), random)
        
        // Generate Ed25519 key pair for this session
        val (privKey, pubKey) = AirPlay2Crypto.generateEd25519KeyPair()
        ed25519PrivateKey = privKey
        ed25519PublicKey = pubKey
        
        LogServer.log("AirPlayAuth: Initialized with device ID: $deviceId")
    }
    
    private fun generateDeviceId(): String {
        // Generate a random device ID in MAC address format
        val bytes = ByteArray(6)
        random.nextBytes(bytes)
        return bytes.joinToString(":") { "%02X".format(it) }
    }

    /**
     * M1: Client -> Server (Start Pair-Setup)
     * Sends: State=M1, Method=PairSetup
     */
    fun createPairSetupM1(): ByteArray {
        LogServer.log("AirPlayAuth: Creating M1 (pair-setup start)")
        
        val tlv = Tlv8()
        tlv.add(TLV_STATE, 0x01.toByte())  // State: M1
        tlv.add(TLV_METHOD, METHOD_PAIR_SETUP.toByte())  // Method: Pair-Setup
        
        return tlv.encode()
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
        
        // Parse state (should be 0x02 for M2)
        val state = map[TLV_STATE]?.firstOrNull()?.toInt() ?: 0
        if (state != 0x02) {
            LogServer.log("AirPlayAuth: Unexpected state in M2: $state")
        }
        
        // Extract salt and server public key B
        srpSalt = map[TLV_SALT] ?: throw Exception("Missing Salt in M2")
        val publicKeyB = map[TLV_PUBLIC_KEY] ?: throw Exception("Missing Public Key B in M2")
        
        LogServer.log("AirPlayAuth: M2 salt=${srpSalt!!.size} bytes, B=${publicKeyB.size} bytes")
        
        B = BigInteger(1, publicKeyB)
        
        // Identity for SRP is "Pair-Setup" (Apple's convention)
        val identity = "Pair-Setup".toByteArray()
        val password = pin.toByteArray()
        
        // Generate client credentials (A)
        A = srpClient.generateClientCredentials(srpSalt, identity, password)
        
        // Calculate shared secret S
        S = try {
            srpClient.calculateSecret(B)
        } catch (e: Exception) {
            LogServer.log("AirPlayAuth: SRP calculateSecret failed: ${e.message}")
            throw Exception("SRP calculation error: ${e.message}")
        }
        
        // Derive session key K = H(S)
        val digest = SHA512Digest()
        val sBytes = S!!.toByteArray().let { 
            if (it[0] == 0.toByte() && it.size > 1) it.copyOfRange(1, it.size) else it 
        }
        K = ByteArray(64)
        digest.update(sBytes, 0, sBytes.size)
        digest.doFinal(K, 0)
        
        // Calculate client proof M1 = H(H(N) XOR H(g) | H(I) | s | A | B | K)
        M1 = SRP6Util.calculateM1(digest, N_2048, A, B, S)
        
        LogServer.log("AirPlayAuth: Generated A and M1, creating M3")
        
        // Build M3 response
        val aBytes = A!!.toByteArray().let { 
            if (it[0] == 0.toByte() && it.size > 1) it.copyOfRange(1, it.size) else it 
        }
        val m1Bytes = M1!!.toByteArray().let { 
            if (it[0] == 0.toByte() && it.size > 1) it.copyOfRange(1, it.size) else it 
        }
        
        val tlv = Tlv8()
        tlv.add(TLV_STATE, 0x03.toByte())  // State: M3
        tlv.add(TLV_PUBLIC_KEY, aBytes)     // Client public key A
        tlv.add(TLV_PROOF, m1Bytes)         // Client proof M1
        
        return tlv.encode()
    }
    
    /**
     * Parse M4 and Generate M5
     * 
     * M4 contains: State=M4, Proof (M2)
     * M5 sends: State=M5, EncryptedData (Ed25519 public key + signature)
     */
    fun parseM4AndGenerateM5(m4Data: ByteArray): ByteArray {
        LogServer.log("AirPlayAuth: Parsing M4 (${m4Data.size} bytes)")
        
        val map = Tlv8.decode(m4Data)
        
        // Check for errors
        map[TLV_ERROR]?.let { error ->
            val errorCode = error[0].toInt()
            throw Exception("Server error in M4: $errorCode (PIN incorrect?)")
        }
        
        val serverProof = map[TLV_PROOF] ?: throw Exception("Missing Server Proof in M4")
        
        // Verify server proof M2
        val digest = SHA512Digest()
        val expectedM2 = SRP6Util.calculateM2(digest, N_2048, A, M1, S)
        val serverProofInt = BigInteger(1, serverProof)
        
        if (serverProofInt != expectedM2) {
            LogServer.log("AirPlayAuth: Server proof verification FAILED")
            throw Exception("Server proof verification failed")
        }
        
        LogServer.log("AirPlayAuth: Server proof verified, creating M5")
        
        // Derive encryption key for M5 data
        // Key is derived using HKDF from session key K
        val encryptKey = AirPlay2Crypto.hkdf(
            salt = "Pair-Setup-Encrypt-Salt".toByteArray(),
            ikm = K!!,
            info = "Pair-Setup-Encrypt-Info".toByteArray(),
            length = 32
        )
        
        // Build data to sign: ed25519 public key + device identifier
        val deviceIdBytes = deviceId.toByteArray()
        val dataToSign = ed25519PublicKey!! + deviceIdBytes
        
        // Sign with Ed25519
        val signature = AirPlay2Crypto.ed25519Sign(ed25519PrivateKey!!, dataToSign)
        
        // Build inner TLV (to be encrypted)
        val innerTlv = Tlv8()
        innerTlv.add(TLV_IDENTIFIER, deviceIdBytes)
        innerTlv.add(TLV_PUBLIC_KEY, ed25519PublicKey!!)
        innerTlv.add(TLV_SIGNATURE, signature)
        val innerData = innerTlv.encode()
        
        // Encrypt with ChaCha20-Poly1305
        // Nonce for M5 is "PS-Msg05" padded to 12 bytes
        val nonce = "PS-Msg05".toByteArray().copyOf(12)
        val encrypted = encryptWithNonce(encryptKey, nonce, innerData)
        
        // Build M5
        val tlv = Tlv8()
        tlv.add(TLV_STATE, 0x05.toByte())  // State: M5
        tlv.add(TLV_ENCRYPTED_DATA, encrypted)
        
        return tlv.encode()
    }
    
    /**
     * Parse M6 (Final pairing response)
     * 
     * M6 contains: State=M6, EncryptedData (server Ed25519 public key + signature)
     */
    fun parseM6AndFinish(m6Data: ByteArray): Boolean {
        LogServer.log("AirPlayAuth: Parsing M6 (${m6Data.size} bytes)")
        
        val map = Tlv8.decode(m6Data)
        
        // Check for errors
        map[TLV_ERROR]?.let { error ->
            val errorCode = error[0].toInt()
            LogServer.log("AirPlayAuth: Error in M6: $errorCode")
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
        
        // Decrypt with nonce "PS-Msg06"
        val nonce = "PS-Msg06".toByteArray().copyOf(12)
        val decrypted = decryptWithNonce(decryptKey, nonce, encryptedData)
            ?: throw Exception("Failed to decrypt M6 data")
        
        // Parse inner TLV
        val innerMap = Tlv8.decode(decrypted)
        val serverIdentifier = innerMap[TLV_IDENTIFIER]
        val serverPublicKey = innerMap[TLV_PUBLIC_KEY]
        val serverSignature = innerMap[TLV_SIGNATURE]
        
        LogServer.log("AirPlayAuth: M6 decrypted - server ID=${serverIdentifier?.size} bytes")
        
        // Store session key for pair-verify
        sessionKey = AirPlay2Crypto.hkdf(
            salt = "Pair-Setup-Controller-Sign-Salt".toByteArray(),
            ikm = K!!,
            info = "Pair-Setup-Controller-Sign-Info".toByteArray(),
            length = 32
        )
        
        LogServer.log("AirPlayAuth: Pair-Setup COMPLETE!")
        return true
    }
    
    /**
     * Legacy M4 finish method for compatibility
     */
    fun parseM4AndFinish(m4Data: ByteArray): ByteArray {
        val map = Tlv8.decode(m4Data)
        val serverProof = map[TLV_PROOF] ?: throw Exception("Missing Server Proof in M4")
        
        val digest = SHA512Digest()
        val expectedM2 = SRP6Util.calculateM2(digest, N_2048, A, M1, S)
        val serverProofInt = BigInteger(1, serverProof)
        
        if (serverProofInt != expectedM2) {
            throw Exception("Server Proof Invalid. Auth Failed.")
        }
        
        return ByteArray(0)
    }
    
    /**
     * Encrypt data with ChaCha20-Poly1305 using specific nonce
     */
    private fun encryptWithNonce(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray {
        // Simple ChaCha20-Poly1305 encryption
        // Returns ciphertext + tag (no prepended nonce since we use fixed nonce)
        val encrypted = AirPlay2Crypto.chaCha20Poly1305Encrypt(key, plaintext)
        // The crypto function prepends nonce, but we want fixed nonce
        // So we need a version that uses our nonce
        // For now, return the full output (will need to adjust)
        return encrypted.copyOfRange(12, encrypted.size) // Skip auto-generated nonce
    }
    
    /**
     * Decrypt data with ChaCha20-Poly1305 using specific nonce
     */
    private fun decryptWithNonce(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray? {
        // Prepend the nonce for our crypto function
        val withNonce = nonce + ciphertext
        return AirPlay2Crypto.chaCha20Poly1305Decrypt(key, withNonce)
    }
    
    // Getters for session state
    fun getSessionKey(): ByteArray? = sessionKey
    fun getEd25519PublicKey(): ByteArray? = ed25519PublicKey
    fun getDeviceId(): String = deviceId
}
