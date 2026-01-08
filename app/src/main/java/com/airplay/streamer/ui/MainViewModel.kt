package com.airplay.streamer.ui

import android.app.Application
import android.content.Context
import android.net.wifi.WifiManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.airplay.streamer.discovery.AirPlayDevice
import com.airplay.streamer.discovery.AirPlayDiscovery
import com.airplay.streamer.discovery.DiscoveryEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MainUiState(
    val devices: List<AirPlayDevice> = emptyList(),
    val selectedDevice: AirPlayDevice? = null,
    val isStreaming: Boolean = false,
    val statusMessage: String = "searching for airplay speakers..."
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val wifiManager = application.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val discovery = AirPlayDiscovery(wifiManager)

    private val discoveredDevices = mutableMapOf<String, AirPlayDevice>()
    private var discoveryJob: Job? = null

    private val mediaInfoTracker = com.airplay.streamer.service.MediaInfoTracker(application)
    val mediaInfo = mediaInfoTracker.mediaInfo

    init {
        startDiscovery()
        mediaInfoTracker.start()
    }
    
    private fun startDiscovery() {
        discoveryJob = viewModelScope.launch {
            discovery.discoverDevices().collect { event ->
                when (event) {
                    is DiscoveryEvent.DiscoveryStarted -> {
                        updateStatus("searching for airplay speakers...")
                    }
                    is DiscoveryEvent.DeviceFound -> {
                        // Filter out AirPlay 2 devices (protocolVersion == 2)
                        // Only add if it's strictly AirPlay 1 (RAOP)
                        if (event.device.protocolVersion != 2) {
                            val key = "${event.device.host}:${event.device.port}"
                            discoveredDevices[key] = event.device
                            updateDeviceList()
                        }
                    }
                    is DiscoveryEvent.DeviceLost -> {
                        val key = "${event.device.host}:${event.device.port}"
                        discoveredDevices.remove(key)
                        updateDeviceList()
                    }
                }
            }
        }
    }

    private fun updateDeviceList() {
        val devices = discoveredDevices.values.toList()
        val message = if (devices.isEmpty()) {
            "searching for airplay speakers..."
        } else {
            if (devices.size == 1) "found 1 speaker" else "found ${devices.size} speakers"
        }
        _uiState.value = _uiState.value.copy(
            devices = devices,
            statusMessage = message
        )
    }

    private fun updateStatus(message: String) {
        _uiState.value = _uiState.value.copy(statusMessage = message)
    }

    fun selectDevice(device: AirPlayDevice) {
        val current = _uiState.value.selectedDevice
        if (current?.host == device.host && current.port == device.port) {
            // Deselect
            _uiState.value = _uiState.value.copy(selectedDevice = null)
        } else {
            _uiState.value = _uiState.value.copy(selectedDevice = device)
        }
    }

    fun addManualDevice(device: AirPlayDevice) {
        val key = "${device.host}:${device.port}"
        discoveredDevices[key] = device
        updateDeviceList()
    }

    fun refreshDiscovery() {
        viewModelScope.launch {
            // Clear existing devices (except manual ones) and restart discovery
            val manualDevices = discoveredDevices.filter { it.value.deviceId.startsWith("manual") }
            discoveredDevices.clear()
            discoveredDevices.putAll(manualDevices)
            
            // Deselect any selected device
            _uiState.value = _uiState.value.copy(selectedDevice = null)
            
            updateDeviceList()
            
            // Cancel existing discovery job
            discoveryJob?.cancel()
            
            // Stop discovery on IO thread to avoid blocking UI
            withContext(Dispatchers.IO) {
                discovery.stop()
            }
            
            // Restart discovery
            startDiscovery()
        }
    }

    fun setStreamingState(isStreaming: Boolean) {
        _uiState.value = _uiState.value.copy(isStreaming = isStreaming)
    }

    fun togglePlayback() {
        mediaInfoTracker.togglePlayback()
    }

    override fun onCleared() {
        super.onCleared()
        discovery.stop()
        mediaInfoTracker.stop()
    }
}
