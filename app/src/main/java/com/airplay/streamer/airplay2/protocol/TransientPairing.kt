package com.airplay.streamer.airplay2.protocol

import com.airplay.streamer.airplay2.crypto.*
import com.dd.plist.NSDictionary
import com.dd.plist.PropertyListParser
import java.math.BigInteger
import java.util.UUID

/**
 * AirPlay 2 Transient Pairing Manager
 * 
 * Handles the transient pairing flow:
 * 1. /pair-pin-start
 * 2. M1-M4 pair-setup exchange
 * 3. Key derivation
 * 4. Enable encrypted session
 */
class TransientPairing(
    private val rtspClient: RtspClient
) {
    private var srpClient: Srp6aClient? = null
    var sessionKey: ByteArray? = null
        private set
    
    /**
     * Execute full transient pairing flow
     */
    suspend fun pair(): Boolean {
        // Step 1: POST /pair-pin-start
        if (!pairPinStart()) {
            return false
        }
        
        // Step 2: M1 -> M2
        val (salt, serverPk) = pairSetupM1()
        
        // Step 3: M3 -> M4
        if (!pairSetupM3(salt, serverPk)) {
            return false
        }
        
        // Step 4: Derive and enable encryption
        deriveControlKeys()
        
        return true
    }
    
    /**
     * Step 1: POST /pair-pin-start
     */
    private fun pairPinStart(): Boolean {
        val response = rtspClient.sendRtsp(
            "POST",
            "/pair-pin-start",
            contentType = "application/x-apple-binary-plist"
        )
        return response.statusCode == 200
    }
    
    /**
     * Step 2: M1 -> M2 (Send transient flag, receive salt and server public key)
     */
    private fun pairSetupM1(): Pair<ByteArray, BigInteger> {
        val m1Data = Tlv8.encode(
            Tlv8.Type.METHOD to Tlv8.byteValue(0x00),
            Tlv8.Type.SEQ_NO to Tlv8.byteValue(0x01),
            Tlv8.Type.FLAGS to Tlv8.byteValue(Tlv8.Flags.TRANSIENT_PAIRING)
        )
        
        val response = rtspClient.sendRtsp("POST", "/pair-setup", m1Data)
        
        if (response.statusCode != 200) {
            throw Exception("M1 failed: ${response.statusCode}")
        }
        
        val m2 = Tlv8.decode(response.body)
        
        if (m2.containsKey(Tlv8.Type.ERROR)) {
            val error = m2[Tlv8.Type.ERROR]?.get(0)?.toInt() ?: 0
            throw Exception("M2 error: 0x${error.toString(16)}")
        }
        
        val salt = m2[Tlv8.Type.SALT] ?: throw Exception("No salt in M2")
        val serverPkBytes = m2[Tlv8.Type.PUBLIC_KEY] ?: throw Exception("No public key in M2")
        val serverPk = BigInteger(1, serverPkBytes)
        
        return Pair(salt, serverPk)
    }
    
    /**
     * Step 3: M3 -> M4 (SRP exchange)
     */
    private fun pairSetupM3(salt: ByteArray, serverPk: BigInteger): Boolean {
        // Create SRP client for transient pairing
        srpClient = Srp6aClient.forTransientPairing()
        srpClient!!.generateClientCredentials()
        
        // Process server challenge
        val (clientPk, clientProof) = srpClient!!.processChallenge(salt, serverPk)
        println("TransientPairing: M3 srpClient hash=${srpClient.hashCode()}, K=${if (srpClient!!.K != null) "SET" else "NULL"}")
        
        val m3Data = Tlv8.encode(
            Tlv8.Type.SEQ_NO to Tlv8.byteValue(0x03),
            Tlv8.Type.PUBLIC_KEY to clientPk,
            Tlv8.Type.PROOF to clientProof
        )
        
        val response = rtspClient.sendRtsp("POST", "/pair-setup", m3Data)
        
        if (response.statusCode != 200) {
            throw Exception("M3 failed: ${response.statusCode}")
        }
        
        val m4 = Tlv8.decode(response.body)
        
        if (m4.containsKey(Tlv8.Type.ERROR)) {
            val error = m4[Tlv8.Type.ERROR]?.get(0)?.toInt() ?: 0
            throw Exception("M4 error: 0x${error.toString(16)}")
        }
        
        val serverProof = m4[Tlv8.Type.PROOF] ?: throw Exception("No proof in M4")
        
        // Verify server proof
        if (!srpClient!!.verifyServerProof(serverProof)) {
            throw Exception("Server proof verification failed")
        }
        
        return true
    }
    
    /**
     * Step 4: Derive Control channel encryption keys and enable encryption
     */
    private fun deriveControlKeys() {
        sessionKey = srpClient!!.K ?: throw IllegalStateException("Session key not available")
        rtspClient.enableEncryption(sessionKey!!)
    }
}
