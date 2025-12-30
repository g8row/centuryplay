package com.airplay.streamer.raop

import com.airplay.streamer.util.Tlv8
import org.bouncycastle.crypto.agreement.srp.SRP6Client
import org.bouncycastle.crypto.agreement.srp.SRP6Util
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.params.SRP6GroupParameters
import java.math.BigInteger
import java.security.SecureRandom
import java.util.UUID

/**
 * Handles AirPlay 2 SRP Authentication (Pair-Setup)
 */
class AirPlayAuth {

    companion object {
        // Standard 2048-bit Group parameters (RFC 5054) used by AirPlay
        private val N_2048 = BigInteger(1, hexToBytes("AC6BDB41324A9A9BF166DE5E1389582FAF72B6651987EE07FC3192943DB56050A37329CBB4A099ED8193E0757767A13DD52312AB4B03310DCD7F48A9DA04FD50E8083969EDB767B0CF6095179A163AB3661A05FBD5FAAAE82918A9962F0B93B855F97993EC975EEAA80D740ADBF4FF747359D041D5C33EA71D281E446B14773BCA97B43A23FB801676BD207A436C6481F1D2B9078717461A5B9D32E688F87748544523B524B0D57D5EA77A2775D2ECFA032CFBDBF52FB3786160279004E57AE6AF874E7303CE53299CCC041C7BC308D82A5698F3A8D0C38271AE35F8E9DBFBB694B5C803D89F7AE435DE236D525F54759B65E372FCD68EF20FA7111F9E4AFF73"))
        private val G_2048 = BigInteger.valueOf(2)
        
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
    // Use MAC-style client ID for compatibility
    private val clientId = "00:11:22:33:44:55"

    // Session State
    private var A: BigInteger? = null
    private var B: BigInteger? = null
    private var s: ByteArray? = null
    private var K: BigInteger? = null
    private var M1: BigInteger? = null

    init {
        // Initialize SRP6 Client with SHA-512
        val group = SRP6GroupParameters(N_2048, G_2048)
        srpClient.init(N_2048, G_2048, SHA512Digest(), random)
    }

    /**
     * M1: Client -> Server
     */
    fun createPairSetupM1(): ByteArray {
        val tlv = Tlv8()
        tlv.add(0x00, 0x00.toByte()) // Method: Pair Setup (0) - Trying 0 instead of 1
        // Usually: 0=PairSetup, 1=PairVerify in some docs. 
        // But often M1 includes 'method' = 1 (Pair-Setup).
        // Let's assume Method=1.
        
        tlv.add(0x01, clientId)
        return tlv.encode()
    }

    /**
     * M3: Client -> Server
     * Input: M2 Data (Salt, Public Key B)
     */
    fun parseM2AndGenerateM3(m2Data: ByteArray, pin: String): ByteArray {
        val map = Tlv8.decode(m2Data)
        val salt = map[0x02] ?: throw Exception("Missing Salt in M2")
        val publicKeyB = map[0x03] ?: throw Exception("Missing Public Key B in M2")
        
        val B_int = BigInteger(1, publicKeyB)
        
        // Generate Client Credentials
        val A_int = srpClient.generateClientCredentials(salt, clientId.toByteArray(), pin.toByteArray())
        
        // Calculate S and K
        // Note: srpClient.calculateSecret returns S
        val S_int = try {
            srpClient.calculateSecret(B_int)
        } catch (e: Exception) {
            throw Exception("SRP Secret Calc Error: ${e.message}")
        }
        
        val digest = SHA512Digest()
        
        // Manual M1 calculation using SRP6Util
        val M1_int = SRP6Util.calculateM1(digest, N_2048, A_int, B_int, S_int)
        
        // Store state
        this.A = A_int
        this.B = B_int
        this.s = salt
        this.M1 = M1_int
        this.K = SRP6Util.calculateK(digest, N_2048, S_int)

        // Convert A to bytes (unsigned)
        var aBytes = A_int.toByteArray()
        if (aBytes[0] == 0.toByte() && aBytes.size > 1) {
            aBytes = aBytes.copyOfRange(1, aBytes.size)
        }
        
        // Convert M1 to bytes
        var m1Bytes = M1_int.toByteArray()
        if (m1Bytes[0] == 0.toByte() && m1Bytes.size > 1) {
            m1Bytes = m1Bytes.copyOfRange(1, m1Bytes.size)
        }

        val tlv = Tlv8()
        tlv.add(0x03, aBytes) // pk: Client Public Key
        tlv.add(0x04, m1Bytes) // proof: Client Proof
        return tlv.encode()
    }
    
    /**
     * M5: Finish
     * Input: M4 Data (Server Proof M2)
     */
    fun parseM4AndFinish(m4Data: ByteArray): ByteArray {
        val map = Tlv8.decode(m4Data)
        val serverProof = map[0x04] ?: throw Exception("Missing Server Proof in M4")
        
        // Verify Server Proof (M2)
        val digest = SHA512Digest()
        
        // We need local M2 calculation
        // M2 = H(A, M1, S)
        // We assume 'S' is still valid in srpClient or we could recalculate it
        // But SRP6Util.calculateM2 requires S.
        // Sadly we didn't store S in a field (only K).
        // BUT, srpClient.calculateSecret(B) is repeatable! 
        
        val S_int = srpClient.calculateSecret(this.B)
        val expectedM2 = SRP6Util.calculateM2(digest, N_2048, this.A, this.M1, S_int)
        
        val serverProofInt = BigInteger(1, serverProof)
        val expectedM2Int = BigInteger(1, expectedM2.toByteArray()) // M2 is BigInteger? calculateM2 returns BigInteger.
        
        // Wait, calculateM2 returns BigInteger.
        
        if (serverProofInt != expectedM2Int) {
             throw Exception("Server Proof Invalid. Auth Failed.")
        }
        
        return ByteArray(0) 
    }
}
