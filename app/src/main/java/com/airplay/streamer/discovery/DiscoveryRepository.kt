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
    
    private val _devices = MutableStateFlow<List<AirPlayDevice>>(emptyList())
    val devices: StateFlow<List<AirPlayDevice>> = _devices.asStateFlow()
    
    private var discoveryJob: Job? = null
    private val discoveredMap = mutableMapOf<String, AirPlayDevice>()
    
    private var observerCount = 0

    /**
     * Start discovery and keep it running while there are active observers.
     */
    fun startDiscovery() {
        synchronized(this) {
            observerCount++
            
            // Immediately emit current cached devices to eliminate startup delay
            updateList()
            
            if (discoveryJob != null) return
            
            Log.d(TAG, "Starting persistent discovery (observers: $observerCount)")
            discoveryJob = repositoryScope.launch {
                discovery.discoverDevices().collect { event ->
                    when (event) {
                        is DiscoveryEvent.DeviceFound -> {
                            val key = "${event.device.host}:${event.device.port}"
                            discoveredMap[key] = event.device
                            updateList()
                        }
                        is DiscoveryEvent.DeviceLost -> {
                            val key = "${event.device.host}:${event.device.port}"
                            discoveredMap.remove(key)
                            updateList()
                        }
                        else -> {}
                    }
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
                Log.d(TAG, "Stopping persistent discovery (no observers)")
                discoveryJob?.cancel()
                discoveryJob = null
                discovery.stop()
                // We EXPLICITLY do not clear discoveredMap here so that
                // the next time startDiscovery is called, we can show
                // cached devices instantly.
            }
        }
    }

    private fun updateList() {
        _devices.value = discoveredMap.values.toList()
    }
    
    fun refresh() {
        Log.d(TAG, "Forcing discovery refresh")
        discoveredMap.clear()
        updateList()
        
        discoveryJob?.cancel()
        discoveryJob = null
        discovery.stop()
        
        // Restart if we still have observers
        if (observerCount > 0) {
            startDiscovery()
        }
    }
}
