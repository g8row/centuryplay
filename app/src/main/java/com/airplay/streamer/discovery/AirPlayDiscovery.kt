package com.airplay.streamer.discovery

import android.net.wifi.WifiManager
import android.os.Parcelable
import android.util.Log
import kotlinx.parcelize.Parcelize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener

/**
 * Represents a discovered AirPlay speaker
 */
@Parcelize
data class AirPlayDevice(
    val name: String,
    val host: String,
    val port: Int,
    val deviceId: String, // 'pi' or 'deviceid'
    val publicKey: String? = null, // 'pk'
    val features: Map<String, String> = emptyMap(),
    val protocolVersion: Int = 2, // 1 = RAOP (AirPlay 1), 2 = AirPlay 2
    val raopPort: Int? = null, // Port for RAOP protocol if discovered via _raop._tcp
    // Stable key shared by a device's _raop and _airplay records: the MAC in the RAOP
    // service name ("37562C433AF0@Name") or the AirPlay "deviceid" TXT, else the host.
    val identity: String = host
) : Parcelable {
    val displayName: String
        get() = name.substringAfter("@").ifEmpty { name }
    
    val isAirPlay2: Boolean
        get() = protocolVersion == 2
}

/**
 * Discovers AirPlay devices on the local network using mDNS/Bonjour
 * Supports both:
 * - AirPlay 1 (RAOP): _raop._tcp.local. (ports 5000-5005)
 * - AirPlay 2: _airplay._tcp.local. (port 7000)
 */
class AirPlayDiscovery(
    private val wifiManager: WifiManager
) {
    companion object {
        private const val AIRPLAY_SERVICE_TYPE = "_airplay._tcp.local."  // AirPlay 2
        private const val RAOP_SERVICE_TYPE = "_raop._tcp.local."        // AirPlay 1
        private const val TAG = "AirPlayDiscovery"
    }

    private var jmDNS: JmDNS? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    
    // Track discovered devices to merge RAOP/AirPlay2 for same device
    private val discoveredDevices = mutableMapOf<String, AirPlayDevice>()

    /**
     * Start discovering AirPlay devices. Returns a Flow that emits discovery events.
     */
    fun discoverDevices(): Flow<DiscoveryEvent> = callbackFlow {
        // Acquire multicast lock to receive mDNS packets
        multicastLock = wifiManager.createMulticastLock("airplay_discovery").apply {
            setReferenceCounted(true)
            acquire()
        }

        // Get local IP address
        val localAddress = withContext(Dispatchers.IO) {
            val wifiInfo = wifiManager.connectionInfo
            val ipInt = wifiInfo.ipAddress
            val ipBytes = byteArrayOf(
                (ipInt and 0xff).toByte(),
                (ipInt shr 8 and 0xff).toByte(),
                (ipInt shr 16 and 0xff).toByte(),
                (ipInt shr 24 and 0xff).toByte()
            )
            InetAddress.getByAddress(ipBytes)
        }

        // Create jmDNS instance
        val jmdnsStartTime = System.currentTimeMillis()
        jmDNS = withContext(Dispatchers.IO) {
            JmDNS.create(localAddress, "AirPlayDiscovery")
        }
        android.util.Log.d("PROFILING", "JmDNS.create finished in ${System.currentTimeMillis() - jmdnsStartTime}ms")

        // Listener for AirPlay 2 services (_airplay._tcp)
        val airplay2Listener = object : ServiceListener {
            override fun serviceAdded(event: ServiceEvent) {
                jmDNS?.requestServiceInfo(event.type, event.name, true)
            }

            override fun serviceRemoved(event: ServiceEvent) {
                // Removal events usually carry no address/TXT, so match on service name.
                val device = synchronized(discoveredDevices) {
                    discoveredDevices.values.firstOrNull { it.name == event.name && it.protocolVersion == 2 }
                        ?.also { discoveredDevices.remove(it.identity) }
                }
                if (device != null) trySend(DiscoveryEvent.DeviceLost(device))
            }

            override fun serviceResolved(event: ServiceEvent) {
                val device = parseServiceEvent(event, isRaop = false)
                if (device != null) synchronized(discoveredDevices) {
                    val existingDevice = findCounterpart(device)
                    val mergedDevice = if (existingDevice != null) {
                        // Merge: keep RAOP port, and union features so RAOP's et/cn and
                        // AirPlay 2's ft flags both survive (ft is needed for v2 routing).
                        device.copy(
                            raopPort = existingDevice.raopPort,
                            features = existingDevice.features + device.features,
                            identity = existingDevice.identity
                        )
                    } else {
                        device
                    }
                    discoveredDevices[mergedDevice.identity] = mergedDevice
                    trySend(DiscoveryEvent.DeviceFound(mergedDevice))
                    Log.d(TAG, "AirPlay 2 device found: ${device.displayName} at ${device.host}:${device.port}")
                }
            }
        }

        // Listener for RAOP services (_raop._tcp) - AirPlay 1
        val raopListener = object : ServiceListener {
            override fun serviceAdded(event: ServiceEvent) {
                jmDNS?.requestServiceInfo(event.type, event.name, true)
            }

            override fun serviceRemoved(event: ServiceEvent) {
                // Only remove if not also an AirPlay 2 device
                val device = synchronized(discoveredDevices) {
                    discoveredDevices.values.firstOrNull { it.name == event.name && it.protocolVersion == 1 }
                        ?.also { discoveredDevices.remove(it.identity) }
                }
                if (device != null) trySend(DiscoveryEvent.DeviceLost(device))
            }

            override fun serviceResolved(event: ServiceEvent) {
                val device = parseServiceEvent(event, isRaop = true)
                if (device != null) synchronized(discoveredDevices) {
                    val existingDevice = findCounterpart(device)
                    if (existingDevice != null) {
                        // Merge: keep AirPlay 2 identity and RAOP port. Union features so the
                        // RAOP TXT (et=, cn= for AirPlay 1) and the AirPlay 2 TXT (ft flags for
                        // v2 routing) are both present.
                        val mergedDevice = existingDevice.copy(
                            raopPort = device.port,
                            features = existingDevice.features + device.features
                        )
                        discoveredDevices[mergedDevice.identity] = mergedDevice
                        trySend(DiscoveryEvent.DeviceFound(mergedDevice))
                    } else {
                        // New RAOP-only device (AirPlay 1)
                        discoveredDevices[device.identity] = device
                        trySend(DiscoveryEvent.DeviceFound(device))
                    }
                    Log.d(TAG, "RAOP device found: ${device.displayName} at ${device.host}:${device.port}")
                }
            }
        }

        // Start listening for both service types
        withContext(Dispatchers.IO) {
            jmDNS?.addServiceListener(AIRPLAY_SERVICE_TYPE, airplay2Listener)
            jmDNS?.addServiceListener(RAOP_SERVICE_TYPE, raopListener)
            Log.d(TAG, "Started listening for AirPlay 2 and RAOP services")
        }

        trySend(DiscoveryEvent.DiscoveryStarted)

        awaitClose {
            jmDNS?.removeServiceListener(AIRPLAY_SERVICE_TYPE, airplay2Listener)
            jmDNS?.removeServiceListener(RAOP_SERVICE_TYPE, raopListener)
            jmDNS?.close()
            jmDNS = null
            multicastLock?.release()
            multicastLock = null
            discoveredDevices.clear()
        }
    }

    private fun parseServiceEvent(event: ServiceEvent, isRaop: Boolean): AirPlayDevice? {
        val info = event.info ?: return null

        // inet4Addresses can be empty for devices with UUID hostnames (e.g. AirScreen).
        // Fall back to resolving the server hostname. Always use IPv4 — IPv6 link-local
        // addresses cause port 7000 (AirPlay 2) to be selected and RTSP connections to fail.
        val host: String = info.inet4Addresses.firstOrNull()?.hostAddress
            ?: runCatching {
                val server = info.server?.trimEnd('.')
                if (server.isNullOrEmpty()) null
                else InetAddress.getAllByName(server)
                    ?.filterIsInstance<java.net.Inet4Address>()
                    ?.firstOrNull()?.hostAddress
            }.getOrNull()
            ?: run {
                Log.w(TAG, "Could not resolve IPv4 address for ${event.name} (server=${info.server})")
                return null
            }
        val port = info.port
        val name = event.name

        // Parse TXT record
        val features = mutableMapOf<String, String>()
        info.propertyNames?.iterator()?.forEach { key ->
            val value = info.getPropertyString(key)
            if (value != null) {
                features[key] = value
            }
        }

        // Device ID from various TXT record fields
        val deviceId = features["pi"] ?: features["deviceid"] ?: name 
        
        // Public Key 'pk' is needed for AirPlay 2 auth
        val publicKey = features["pk"]

        val identity = if (isRaop) {
            name.substringBefore("@", missingDelimiterValue = "").ifEmpty { null }
        } else {
            features["deviceid"]?.replace(":", "")
        }?.uppercase() ?: host

        return AirPlayDevice(
            identity = identity,
            name = name,
            host = host,
            port = port,
            deviceId = deviceId,
            publicKey = publicKey,
            features = features,
            protocolVersion = if (isRaop) 1 else 2,
            raopPort = if (isRaop) port else null
        )
    }

    /**
     * The already-known record for the same physical device, if any: same identity, or
     * (when one of the records exposes no ID) the single other-protocol record on the
     * same host. Two records with different real IDs are different receivers even on one
     * host (e.g. macOS's own receiver next to a shairport-sync instance).
     */
    private fun findCounterpart(device: AirPlayDevice): AirPlayDevice? {
        discoveredDevices[device.identity]?.let { return it }
        return discoveredDevices.values
            .filter {
                it.host == device.host && it.protocolVersion != device.protocolVersion &&
                    (it.identity == it.host || device.identity == device.host)
            }
            .singleOrNull()
    }

    fun stop() {
        jmDNS?.close()
        jmDNS = null
        // Only release if the lock is held
        multicastLock?.let { lock ->
            if (lock.isHeld) {
                lock.release()
            }
        }
        multicastLock = null
        discoveredDevices.clear()
    }
}

sealed class DiscoveryEvent {
    data object DiscoveryStarted : DiscoveryEvent()
    data class DeviceFound(val device: AirPlayDevice) : DiscoveryEvent()
    data class DeviceLost(val device: AirPlayDevice) : DiscoveryEvent()
}
