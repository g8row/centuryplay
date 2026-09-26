package com.airplay.streamer.airplay2

import com.airplay.streamer.raop.RaopCapabilities

/**
 * Parses the AirPlay feature flags ("ft" / "features" TXT record) to decide whether
 * a device speaks AirPlay 2 and whether it uses transient HAP pairing.
 *
 * Mirrors pyatv's `protocols/airplay/utils.py` (parse_features, get_protocol_version,
 * extract_credentials).
 */
object AirPlay2Capabilities {

    private const val SUPPORTS_UNIFIED_MEDIA_CONTROL = 1L shl 38
    private const val SUPPORTS_SYSTEM_PAIRING = 1L shl 43
    private const val SUPPORTS_COREUTILS_PAIRING_ENCRYPTION = 1L shl 48

    /**
     * Combine an AirPlay feature string into a 64-bit value.
     *
     * Format is either "0xLOW" or "0xLOW,0xHIGH" where HIGH holds bits 32-63.
     */
    fun parseFeatures(features: String): Long {
        val parts = features.split(",")
        val low = parts[0].trim().removePrefix("0x").ifEmpty { "0" }.toLong(16) and 0xFFFFFFFFL
        val high = if (parts.size > 1) {
            parts[1].trim().removePrefix("0x").ifEmpty { "0" }.toLong(16) and 0xFFFFFFFFL
        } else 0L
        return (high shl 32) or low
    }

    private fun featuresValue(txt: Map<String, String>): Long {
        val raw = txt["ft"] ?: txt["features"] ?: return 0L
        return runCatching { parseFeatures(raw) }.getOrDefault(0L)
    }

    /** True if the device should be driven over the AirPlay 2 protocol. */
    fun isAirPlay2(txt: Map<String, String>): Boolean {
        val f = featuresValue(txt)
        return (f and (SUPPORTS_UNIFIED_MEDIA_CONTROL or SUPPORTS_COREUTILS_PAIRING_ENCRYPTION)) != 0L
    }

    /** True if AudioCaptureService will stream to this device via AirPlay2Client. */
    fun usesTransientAirPlay2(txt: Map<String, String>): Boolean = isAirPlay2(txt) && needsTransient(txt)

    /**
     * True if the device can't be streamed to at all: its RAOP endpoint needs FairPlay
     * (et=5 without et=1) and it offers no transient AirPlay 2 route. A device like a Mac
     * or HomePod advertises RAOP et=5 but is still reachable over AirPlay 2.
     */
    fun blockedByFairPlay(txt: Map<String, String>): Boolean =
        RaopCapabilities.requiresUnsupportedFairPlay(txt) && !usesTransientAirPlay2(txt)

    /** True if the device accepts transient pairing (PIN 3939) — e.g. Sonos, HomePod. */
    fun needsTransient(txt: Map<String, String>): Boolean {
        val f = featuresValue(txt)
        return (f and (SUPPORTS_SYSTEM_PAIRING or SUPPORTS_COREUTILS_PAIRING_ENCRYPTION)) != 0L
    }
}
