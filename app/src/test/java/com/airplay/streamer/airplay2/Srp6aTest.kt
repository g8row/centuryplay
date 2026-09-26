package com.airplay.streamer.airplay2

import com.airplay.streamer.airplay2.Srp6a.Companion.minBytes
import com.airplay.streamer.airplay2.Srp6a.Companion.sha512
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigInteger
import java.security.SecureRandom

/**
 * Validates the SRP-6a client against an independently-computed server side using
 * the same RFC 5054 3072-bit group / SHA-512 conventions as srptools. If the
 * client's k/x/u/S/K/M1 derivation is wrong, the shared key or proof will not match.
 */
class Srp6aTest {

    private val n = BigInteger(PRIME_3072_HEX, 16)
    private val g = BigInteger.valueOf(5)
    private val nLen = minBytes(n).size

    private fun pad(v: BigInteger): ByteArray {
        val mb = minBytes(v)
        if (mb.size >= nLen) return mb
        return ByteArray(nLen).also { System.arraycopy(mb, 0, it, nLen - mb.size, mb.size) }
    }

    @Test
    fun clientSharedKeyAndProofMatchServer() {
        val identity = "Pair-Setup"
        val password = "3939"

        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }

        // Server-side verifier
        val inner = sha512("$identity:$password".toByteArray())
        val x = BigInteger(1, sha512(salt, inner))
        val v = g.modPow(x, n)
        val k = BigInteger(1, sha512(minBytes(n), pad(g)))
        val b = BigInteger(1, ByteArray(32).also { SecureRandom().nextBytes(it) })
        val bPub = k.multiply(v).add(g.modPow(b, n)).mod(n)

        // Client
        val client = Srp6a(identity, password)
        val aPub = BigInteger(1, client.publicKey())
        client.process(salt, minBytes(bPub))

        // Server computes the shared key the SRP way
        val u = BigInteger(1, sha512(pad(aPub), pad(bPub)))
        val sServer = aPub.multiply(v.modPow(u, n)).modPow(b, n)
        val kServer = sha512(minBytes(sServer))

        assertArrayEquals("shared key mismatch", kServer, client.sharedKey())
        assertEquals(64, client.sharedKey().size)

        // Server recomputes M1 and compares with the client's proof
        val hXor = BigInteger(1, sha512(minBytes(n))).xor(BigInteger(1, sha512(minBytes(g))))
        val hI = BigInteger(1, sha512(identity.toByteArray()))
        val m1Server = sha512(minBytes(hXor), minBytes(hI), salt, minBytes(aPub), minBytes(bPub), kServer)
        assertArrayEquals("proof mismatch", m1Server, client.proof())
    }

    companion object {
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
    }
}
