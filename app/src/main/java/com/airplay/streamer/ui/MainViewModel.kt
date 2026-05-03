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

    private val repository = com.airplay.streamer.discovery.DiscoveryRepository.getInstance(application)

    private val mediaInfoTracker = com.airplay.streamer.service.MediaInfoTracker(application)
    val mediaInfo = mediaInfoTracker.mediaInfo

    init {
        val startTime = System.currentTimeMillis()
        android.util.Log.d("PROFILING", "MainViewModel init started")
        repository.startDiscovery()
        mediaInfoTracker.start()
        
        viewModelScope.launch {
            repository.devices.collect { devices ->
                // Show devices that support RAOP (AirPlay 1).
                val filtered = devices.filter { it.protocolVersion == 1 || it.raopPort != null }
                
                val message = if (filtered.isEmpty()) {
                    "searching for airplay speakers..."
                } else {
                    if (filtered.size == 1) "found 1 speaker" else "found ${filtered.size} speakers"
                }
                
                _uiState.value = _uiState.value.copy(
                    devices = filtered,
                    statusMessage = message
                )
            }
        }
        android.util.Log.d("PROFILING", "MainViewModel init finished in ${System.currentTimeMillis() - startTime}ms")
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
        // Since the repository is global, we can't easily add a manual device just for one session
        // without it affecting everything, but we can just update the UI state locally if needed.
        val current = _uiState.value.devices.toMutableList()
        if (current.none { it.host == device.host && it.port == device.port }) {
            current.add(device)
            _uiState.value = _uiState.value.copy(devices = current)
        }
    }

    fun refreshDiscovery() {
        repository.refresh()
    }

    fun setStreamingState(isStreaming: Boolean) {
        _uiState.value = _uiState.value.copy(isStreaming = isStreaming)
    }

    fun togglePlayback() {
        mediaInfoTracker.togglePlayback()
    }

    override fun onCleared() {
        super.onCleared()
        repository.stopDiscovery()
        mediaInfoTracker.stop()
    }
}
