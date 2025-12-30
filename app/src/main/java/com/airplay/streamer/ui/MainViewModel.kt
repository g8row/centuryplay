package com.airplay.streamer.ui

import android.app.Application
import android.content.Context
import android.net.wifi.WifiManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.airplay.streamer.discovery.AirPlayDevice
import com.airplay.streamer.discovery.AirPlayDiscovery
import com.airplay.streamer.discovery.DiscoveryEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MainUiState(
    val devices: List<AirPlayDevice> = emptyList(),
    val selectedDevice: AirPlayDevice? = null,
    val isStreaming: Boolean = false,
    val statusMessage: String = "Searching for AirPlay speakers..."
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val wifiManager = application.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val discovery = AirPlayDiscovery(wifiManager)

    private val discoveredDevices = mutableMapOf<String, AirPlayDevice>()

    init {
        startDiscovery()
    }

    private fun startDiscovery() {
        viewModelScope.launch {
            discovery.discoverDevices().collect { event ->
                when (event) {
                    is DiscoveryEvent.DiscoveryStarted -> {
                        updateStatus("Searching for AirPlay speakers...")
                    }
                    is DiscoveryEvent.DeviceFound -> {
                        val key = "${event.device.host}:${event.device.port}"
                        discoveredDevices[key] = event.device
                        updateDeviceList()
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
            "Searching for AirPlay speakers..."
        } else {
            "Found ${devices.size} speaker(s)"
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

    fun setStreamingState(isStreaming: Boolean) {
        _uiState.value = _uiState.value.copy(isStreaming = isStreaming)
    }

    override fun onCleared() {
        super.onCleared()
        discovery.stop()
    }
}
