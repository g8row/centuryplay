package com.airplay.streamer.engine

import com.airplay.streamer.airplay2.AirPlay2Capabilities
import com.airplay.streamer.airplay2.AirPlay2Sink
import com.airplay.streamer.discovery.AirPlayDevice
import com.airplay.streamer.raop.RaopCapabilities
import com.airplay.streamer.raop.RaopSink

/** Which wire protocol to use for a device, and why. */
enum class Transport { RAOP, AIRPLAY2, UNSUPPORTED }

object SinkFactory {

    /**
     * RAOP when the device offers it without FairPlay (simplest, lowest overhead, works with
     * every shairport-sync/AirPort/AVR). AirPlay 2 (transient pairing, NTP) for devices that
     * only accept AirPlay 2 without FairPlay, e.g. HomePod, Apple TV, Macs, Sonos.
     * shairport-sync in AirPlay 2 mode only supports PTP timing, so it always gets RAOP.
     */
    fun transportFor(device: AirPlayDevice, preferAirPlay2: Boolean = false): Transport {
        val txt = device.features
        val raopOk = device.raopPort != null && !RaopCapabilities.requiresUnsupportedFairPlay(txt)
        val ap2Ok = device.protocolVersion == 2 && AirPlay2Capabilities.usesTransientAirPlay2(txt) && !isShairportSync(device)
        // Sonos' AirPlay support is AirPlay 2 (its RAOP endpoint wants MFi auth-setup).
        val ap2First = preferAirPlay2 || isSonos(device)
        return when {
            ap2Ok && (ap2First || !raopOk) -> Transport.AIRPLAY2
            raopOk -> Transport.RAOP
            ap2Ok -> Transport.AIRPLAY2
            else -> Transport.UNSUPPORTED
        }
    }

    fun isSonos(device: AirPlayDevice): Boolean =
        device.features["manufacturer"]?.contains("Sonos", ignoreCase = true) == true

    /** The other transport to try if [failed] didn't work, or null. */
    fun alternative(device: AirPlayDevice, failed: Transport): Transport? {
        val txt = device.features
        return when (failed) {
            Transport.AIRPLAY2 -> Transport.RAOP.takeIf { device.raopPort != null && !RaopCapabilities.requiresUnsupportedFairPlay(txt) }
            Transport.RAOP -> Transport.AIRPLAY2.takeIf {
                device.protocolVersion == 2 && AirPlay2Capabilities.usesTransientAirPlay2(txt) && !isShairportSync(device)
            }
            Transport.UNSUPPORTED -> null
        }
    }

    fun isShairportSync(device: AirPlayDevice): Boolean =
        device.features["model"]?.contains("Shairport", ignoreCase = true) == true ||
            device.features["am"]?.contains("Shairport", ignoreCase = true) == true

    fun create(
        device: AirPlayDevice,
        transport: Transport,
        senderName: String,
        preferPcm: Boolean,
        password: String?,
        hapStore: com.airplay.streamer.airplay2.HapCredentialStore? = null,
        pinProvider: ((String) -> String?)? = null,
        forcePin: Boolean = false,
    ): AudioSink = when (transport) {
        Transport.AIRPLAY2 -> AirPlay2Sink(
            device.host, device.port, device.displayName, device.features, senderName, preferPcm,
            device.identity, hapStore, pinProvider, forcePin,
        )
        Transport.RAOP -> RaopSink(device.host, device.raopPort ?: device.port, device.displayName, device.features, preferPcm, password)
        Transport.UNSUPPORTED -> throw SinkException(SinkError.UNSUPPORTED, "${device.displayName} requires FairPlay, which isn't supported")
    }
}
