package com.airplay.streamer.airplay2

import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters

/**
 * HKDF-SHA512 (extract + expand), matching pyatv's `hkdf_expand` in
 * `auth/hap_srp.py` / `auth/hap_transient.py`.
 */
object Hkdf {
    fun expand(salt: String, info: String, ikm: ByteArray, length: Int = 32): ByteArray {
        val gen = HKDFBytesGenerator(SHA512Digest())
        gen.init(
            HKDFParameters(
                ikm,
                salt.toByteArray(Charsets.UTF_8),
                info.toByteArray(Charsets.UTF_8),
            )
        )
        val out = ByteArray(length)
        gen.generateBytes(out, 0, length)
        return out
    }
}
