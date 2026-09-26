package com.airplay.streamer.airplay2

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * SRP-6a client for HAP (HomeKit) Pair-Setup, byte-for-byte compatible with the
 * `srptools` library that pyatv uses (verified against real HomePod/Sonos devices).
 *
 * Parameters: 3072-bit RFC 5054 group, generator 5, SHA-512.
 *
 * Conventions replicated from srptools (`context.py` / `common.py`):
 *  - Integers are hashed as **minimal** big-endian bytes ([minBytes]) — no leading
 *    zeros — except where [pad] (left-pad to the byte length of N) is explicitly used.
 *  - The salt is hashed as the **raw bytes** received from the device (leading zeros
 *    preserved), since `init_base` does `unhexlify(salt)`.
 *  - k = H(N | PAD(g))
 *  - x = H(s | H(I | ":" | P))
 *  - u = H(PAD(A) | PAD(B))
 *  - S = (B - k*g^x) ^ (a + u*x) % N
 *  - K = H(S)                       (the 64-byte shared key)
 *  - M1 = H(H(N) XOR H(g) | H(I) | s | A | B | K)
 */
class Srp6a(
    private val username: String,
    private val password: String,
) {
    private val n = BigInteger(PRIME_3072_HEX, 16)
    private val g = BigInteger.valueOf(5)
    private val nLen = minBytes(n).size // 384 bytes

    // k = H(N | PAD(g))
    private val k = hashInt(minBytes(n), pad(g))

    // Client private a and public A = g^a mod N
    private val a: BigInteger = BigInteger(1, ByteArray(32).also { SecureRandom().nextBytes(it) })
    private val aPub: BigInteger = g.modPow(a, n)

    private var sharedKey: ByteArray? = null
    private var proof: ByteArray? = null

    /** Client public key A as minimal big-endian bytes (TLV PublicKey). */
    fun publicKey(): ByteArray = minBytes(aPub)

    /**
     * Process the device's salt and public key B, deriving the shared key K and the
     * client proof M1. Must be called before [proof] / [sharedKey].
     */
    fun process(salt: ByteArray, serverPublic: ByteArray) {
        val b = BigInteger(1, serverPublic)
        if (b.mod(n) == BigInteger.ZERO) {
            throw IllegalArgumentException("invalid server public key (B mod N == 0)")
        }

        // x = H(s | H(I | ":" | P)) — salt as raw bytes
        val inner = sha512("$username:$password".toByteArray(Charsets.UTF_8))
        val x = hashInt(salt, inner)

        // u = H(PAD(A) | PAD(B))
        val u = hashInt(pad(aPub), pad(b))

        // S = (B - k * g^x) ^ (a + u*x) % N
        val v = g.modPow(x, n)
        val base = b.subtract(k.multiply(v)).mod(n)
        val exp = a.add(u.multiply(x))
        val s = base.modPow(exp, n)

        // K = H(S)
        val key = sha512(minBytes(s))
        sharedKey = key

        // M1 = H(H(N) XOR H(g) | H(I) | s | A | B | K)
        val hN = hashInt(minBytes(n))
        val hG = hashInt(minBytes(g))
        val hXor = hN.xor(hG)
        val hI = hashInt(username.toByteArray(Charsets.UTF_8))
        proof = sha512(
            minBytes(hXor),
            minBytes(hI),
            salt,
            minBytes(aPub),
            minBytes(b),
            key,
        )
    }

    /** Client proof M1 (64 bytes). [process] must have been called. */
    fun proof(): ByteArray = proof ?: error("process() not called")

    /** Shared key K (64 bytes), used as input to HKDF. [process] must have been called. */
    fun sharedKey(): ByteArray = sharedKey ?: error("process() not called")

    private fun hashInt(vararg parts: ByteArray): BigInteger = BigInteger(1, sha512(*parts))

    /** Left-pad the minimal big-endian bytes of [v] to the byte length of N. */
    private fun pad(v: BigInteger): ByteArray {
        val mb = minBytes(v)
        if (mb.size >= nLen) return mb
        val out = ByteArray(nLen)
        System.arraycopy(mb, 0, out, nLen - mb.size, mb.size)
        return out
    }

    companion object {
        // RFC 5054 3072-bit group (matches srptools PRIME_3072)
        private const val PRIME_3072_HEX =
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74" +
            "020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F1437" +
            "4FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
            "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF05" +
            "98DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB" +
            "9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
            "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF695581718" +
            "3995497CEA956AE515D2261898FA051015728E5A8AAAC42DAD33170D04507A33" +
            "A85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7" +
            "ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864" +
            "D87602733EC86A64521F2B18177B200CBBE117577A615D6C770988C0BAD946E2" +
            "08E24FA074E5AB3143DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF"

        /** Minimal big-endian magnitude bytes, no leading zeros (matches srptools int_to_bytes). */
        fun minBytes(v: BigInteger): ByteArray {
            val b = v.toByteArray()
            return if (b.size > 1 && b[0].toInt() == 0) b.copyOfRange(1, b.size) else b
        }

        fun sha512(vararg parts: ByteArray): ByteArray {
            val md = MessageDigest.getInstance("SHA-512")
            for (p in parts) md.update(p)
            return md.digest()
        }
    }
}
