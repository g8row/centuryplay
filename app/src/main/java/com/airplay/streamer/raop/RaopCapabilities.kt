package com.airplay.streamer.raop

/**
 * Helpers for interpreting RAOP mDNS TXT capabilities.
 */
object RaopCapabilities {
    fun encryptionTypes(features: Map<String, String>): Set<Int> {
        return features["et"]
            ?.split(",")
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.toSet()
            ?: emptySet()
    }

    fun requiresUnsupportedFairPlay(features: Map<String, String>): Boolean {
        val encryptionTypes = encryptionTypes(features)
        return 5 in encryptionTypes && 1 !in encryptionTypes
    }
}
