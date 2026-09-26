package com.airplay.streamer.discovery

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Singleton repository that maintains a persistent discovery session.
 * This prevents the slow JmDNS initialization when opening the Quick Tile or switching activities.
 */
class DiscoveryRepository private constructor(context: Context) {
    
    companion object {
        private const val TAG = "DiscoveryRepository"
        private const val STALE_AFTER_MS = 5 * 60_000L
        private const val WATCHDOG_MS = 6_000L
        
        @Volatile
        private var INSTANCE: DiscoveryRepository? = null

        fun getInstance(context: Context): DiscoveryRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DiscoveryRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val discovery = AirPlayDiscovery(wifiManager)
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Debug-only: emulator NAT can't see host-LAN mDNS, so inject a synthetic AirPlay 2
    // device pointing at the host loopback (10.0.2.2:7000) for the airplay2-receiver test
    // loop. ft bits 38/48 route it to AirPlay2Client (transient HAP), matching pyatv's
    // get_protocol_version / extract_credentials. Never present in release builds.
    private val isDebuggable =
        (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 &&
            (android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.HARDWARE.contains("ranchu") ||
                android.os.Build.PRODUCT.contains("sdk"))
    private val emulatorTestDevice = AirPlayDevice(
        name = "Emulator Test (10.0.2.2)",
        host = "10.0.2.2",
        port = 7000,
        deviceId = "emulator-test",
        features = mapOf("ft" to "0x0,0x10040"),
        protocolVersion = 2,
        raopPort = 7000, // also set so it passes the MainViewModel device filter
    )
    
    private val _devices = MutableStateFlow<List<AirPlayDevice>>(emptyList())
    val devices: StateFlow<List<AirPlayDevice>> = _devices.asStateFlow()
    
    private var discoveryJob: Job? = null
    private val discoveredMap = mutableMapOf<String, AirPlayDevice>()
    
    private var observerCount = 0

    private val appContext = context.applicationContext
    private val nsdDiscovery = NsdDiscovery(appContext)
    private var startedAt = 0L
    private var watchdog: Job? = null
    private var networkCallbackRegistered = false
    private var lastWatchdogRestart = 0L

    /** Called when a screen comes to the foreground: re-browse if the results may be stale. */
    fun ensureFresh() {
        synchronized(this) {
            if (observerCount <= 0) return
            if (discoveryJob == null) launchDiscovery()
            else if (discoveredMap.isEmpty() || System.currentTimeMillis() - startedAt > STALE_AFTER_MS) restartLocked("foreground")
        }
    }

    /**
     * Start discovery and keep it running while there are active observers.
     *
     * A long-running NSD browse can go stale (Doze, Wi-Fi roaming, the system mDNS daemon
     * restarting) without any error, so a new observer restarts it when it is old or has
     * found nothing, and network changes restart it too.
     */
    fun startDiscovery() {
        synchronized(this) {
            observerCount++
            if (isDebuggable) discoveredMap[emulatorTestDevice.identity] = emulatorTestDevice
            // Emit cached devices immediately so the UI never starts empty.
            updateList()
            registerNetworkCallback()
            val stale = discoveryJob != null &&
                (discoveredMap.isEmpty() || System.currentTimeMillis() - startedAt > STALE_AFTER_MS)
            if (stale) restartLocked("stale on new observer") else launchDiscovery()
        }
    }

    private fun restartLocked(reason: String) {
        Log.d(TAG, "Restarting discovery: $reason")
        discoveryJob?.cancel()
        discoveryJob = null
        runCatching { discovery.stop() }
        if (observerCount > 0) launchDiscovery()
    }

    private fun registerNetworkCallback() {
        if (networkCallbackRegistered) return
        networkCallbackRegistered = true
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val request = android.net.NetworkRequest.Builder()
            .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI).build()
        runCatching {
            cm.registerNetworkCallback(request, object : android.net.ConnectivityManager.NetworkCallback() {
                private var current: android.net.Network? = null
                override fun onAvailable(network: android.net.Network) {
                    if (current != null && current != network) {
                        repositoryScope.launch { synchronized(this@DiscoveryRepository) { restartLocked("wi-fi changed") } }
                    }
                    current = network
                }
                override fun onLost(network: android.net.Network) {
                    if (current == network) current = null
                    repositoryScope.launch {
                        synchronized(this@DiscoveryRepository) {
                            discoveredMap.clear(); updateList()
                        }
                    }
                }
            })
        }
    }

    /**
     * Wi-Fi drivers drop multicast in power save unless someone holds a MulticastLock; the
     * system mDNS stack doesn't take one for us, so without it replies from receivers that
     * answer by multicast (avahi / shairport-sync) never arrive.
     */
    private var multicastLock: WifiManager.MulticastLock? = null

    private fun acquireMulticast() {
        if (multicastLock?.isHeld == true) return
        multicastLock = wifiManager.createMulticastLock("centuryplay-mdns").apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
    }

    private fun releaseMulticast() {
        multicastLock?.let { if (it.isHeld) runCatching { it.release() } }
        multicastLock = null
    }

    private fun launchDiscovery() {
        if (discoveryJob != null) return
        Log.d(TAG, "Starting discovery (observers: $observerCount)")
        acquireMulticast()
        startedAt = System.currentTimeMillis()
        watchdog?.cancel()
        watchdog = repositoryScope.launch {
            // Nothing found after a few seconds on Wi-Fi? Browse again once.
            kotlinx.coroutines.delay(WATCHDOG_MS)
            synchronized(this@DiscoveryRepository) {
                val now = System.currentTimeMillis()
                if (discoveredMap.isEmpty() && observerCount > 0 && now - lastWatchdogRestart > STALE_AFTER_MS) {
                    lastWatchdogRestart = now
                    restartLocked("watchdog: nothing found")
                }
            }
        }
        discoveryJob = repositoryScope.launch {
            try {
                nsdDiscovery.devices().collect { list ->
                    discoveredMap.clear()
                    if (isDebuggable) discoveredMap[emulatorTestDevice.identity] = emulatorTestDevice
                    list.forEach { discoveredMap[it.identity] = it }
                    updateList()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // NSD unavailable: fall back to JmDNS (guarded — it can fail with EPERM).
                Log.w(TAG, "NSD discovery failed (${e.message}); falling back to JmDNS")
                try {
                    discovery.discoverDevices().collect { event ->
                        when (event) {
                            is DiscoveryEvent.DeviceFound -> discoveredMap[event.device.identity] = event.device
                            is DiscoveryEvent.DeviceLost -> discoveredMap.remove(event.device.identity)
                            else -> {}
                        }
                        updateList()
                    }
                } catch (e2: kotlinx.coroutines.CancellationException) {
                    throw e2
                } catch (e2: Exception) {
                    Log.e(TAG, "JmDNS discovery failed too: ${e2.message}")
                }
            }
        }
    }

    /**
     * Stop discovery if no more observers are active.
     */
    fun stopDiscovery() {
        synchronized(this) {
            observerCount--
            if (observerCount <= 0) {
                observerCount = 0
                Log.d(TAG, "Stopping discovery (no observers)")
                discoveryJob?.cancel()
                discoveryJob = null
                watchdog?.cancel()
                releaseMulticast()
                runCatching { discovery.stop() }
                // Keep discoveredMap so the next start shows cached devices instantly.
            }
        }
    }

    private fun updateList() {
        _devices.value = discoveredMap.values.toList()
    }

    fun refresh() {
        synchronized(this) {
            Log.d(TAG, "Forcing discovery refresh")
            discoveredMap.clear()
            updateList()
            discoveryJob?.cancel()
            discoveryJob = null
            runCatching { discovery.stop() }
            if (observerCount > 0) launchDiscovery()
        }
    }
}
