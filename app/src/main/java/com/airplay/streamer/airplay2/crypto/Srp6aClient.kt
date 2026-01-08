package com.airplay.streamer.airplay2.crypto

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * SRP-6a Client Implementation for AirPlay 2 Transient Pairing
 * 
 * Uses 3072-bit group from RFC 5054 with g=5 (Apple-specific).
 * For transient pairing, the PIN is always "3939".
 */
class Srp6aClient(
    private val identity: ByteArray,
    private val password: ByteArray
) {
    companion object {
        // SRP-6a Parameters - 3072-bit group from RFC 5054
        private val N_HEX = """
            FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B
            139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485
            B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7EDEE386BFB5A899FA5AE9F24117C4B1F
            E649286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F83655D23
            DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC9804F1746C08CA18217C32
            905E462E36CE3BE39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF69558
            17183995497CEA956AE515D2261898FA051015728E5A8AAAC42DAD33170D04507A33A85521
            ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7ABF5AE8CDB0933D7
            1E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864D87602733EC86A64521F2B1817
            7B200CBBE117577A615D6C770988C0BAD946E208E24FA074E5AB3143DB5BFCE0FD108E4B82
            D120A93AD2CAFFFFFFFFFFFFFFFF
        """.trimIndent().replace("\n", "").replace(" ", "")
        
        val N: BigInteger = BigInteger(N_HEX, 16)
        val g: BigInteger = BigInteger.valueOf(5) // Apple uses g=5
        const val N_BYTES = 384 // 3072 bits = 384 bytes
        
        /** Transient pairing PIN */
        const val TRANSIENT_PIN = "3939"
        const val IDENTITY = "Pair-Setup"
        
        private fun sha512(data: ByteArray): ByteArray {
            return MessageDigest.getInstance("SHA-512").digest(data)
        }
        
        private fun sha512(vararg parts: ByteArray): ByteArray {
            val md = MessageDigest.getInstance("SHA-512")
            parts.forEach { md.update(it) }
            return md.digest()
        }
        
        /** Convert BigInteger to bytes with natural length (no leading zeros) */
        private fun BigInteger.toNaturalBytes(): ByteArray {
            val bytes = this.toByteArray()
            return if (bytes[0] == 0.toByte() && bytes.size > 1) {
                bytes.copyOfRange(1, bytes.size)
            } else {
                bytes
            }
        }
        
        /** Pad BigInteger to N's byte length (384 bytes) */
        private fun BigInteger.padToN(): ByteArray {
            val bytes = this.toNaturalBytes()
            return if (bytes.size < N_BYTES) {
                ByteArray(N_BYTES - bytes.size) + bytes
            } else {
                bytes
            }
        }
        
        /** k = H(PAD(N) | PAD(g)) - RFC 5054 */
        private fun calculateK(): BigInteger {
            val hash = sha512(N.padToN(), g.padToN())
            return BigInteger(1, hash)
        }
        
        /**
         * Create a client for transient pairing with default identity and PIN
         */
        fun forTransientPairing(): Srp6aClient {
            return Srp6aClient(
                IDENTITY.toByteArray(),
                TRANSIENT_PIN.toByteArray()
            )
        }
    }
    
    private val random = SecureRandom()
    private val k = calculateK()
    
    private var a: BigInteger? = null // Client private key
    var A: BigInteger? = null // Client public key
        private set
    
    private var B: BigInteger? = null // Server public key
    private var salt: ByteArray? = null
    private var S: BigInteger? = null // Session key (raw)
    
    var K: ByteArray? = null // Derived session key
        private set
    var M1: ByteArray? = null // Client proof
        private set
    
    /**
     * Generate client credentials (private key a, public key A)
     */
    fun generateClientCredentials(): BigInteger {
        a = BigInteger(256, random)
        A = g.modPow(a, N)
        return A!!
    }
    
    /**
     * Process server challenge (salt + server public key B)
     * Returns client public key and proof M1
     */
    fun processChallenge(serverSalt: ByteArray, serverPublicKey: BigInteger): Pair<ByteArray, ByteArray> {
        salt = serverSalt
        B = serverPublicKey
        
        require(B != BigInteger.ZERO && B!! % N != BigInteger.ZERO) {
            "Invalid server public key B"
        }
        
        if (A == null) {
            generateClientCredentials()
        }
        
        // u = H(PAD(A) | PAD(B)) - uses PADDED hashing
        val u = BigInteger(1, sha512(A!!.padToN(), B!!.padToN()))
        
        // x = H(salt | H(I | ":" | P))
        val innerHash = sha512(identity + ":".toByteArray() + password)
        val x = BigInteger(1, sha512(serverSalt, innerHash))
        
        // S = (B - k * g^x) ^ (a + u * x) mod N
        val gx = g.modPow(x, N)
        val kgx = (k * gx) % N
        val base = (B!! - kgx).mod(N)
        val exp = a!! + (u * x)
        S = base.modPow(exp, N)
        
        // K = H(S) - session key (natural bytes)
        this.K = sha512(S!!.toNaturalBytes())
        
        // M1 = H(H(N) XOR H(g) | H(I) | s | A | B | K) - all natural bytes
        val hN = sha512(N.toNaturalBytes())
        val hG = sha512(g.toNaturalBytes())
        val hXor = ByteArray(64) { i -> (hN[i].toInt() xor hG[i].toInt()).toByte() }
        val hI = sha512(identity)
        
        M1 = sha512(hXor, hI, serverSalt, A!!.toNaturalBytes(), B!!.toNaturalBytes(), K!!)
        
        return Pair(A!!.toNaturalBytes(), M1!!)
    }
    
    /**
     * Verify server proof M2
     * M2 = H(A | M1 | K)
     */
    fun verifyServerProof(M2: ByteArray): Boolean {
        val expectedM2 = sha512(A!!.toNaturalBytes(), M1!!, K!!)
        return M2.contentEquals(expectedM2)
    }
}
