package com.airplay.streamer.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.airplay.streamer.discovery.AirPlayDevice
import com.airplay.streamer.discovery.DiscoveryRepository
import com.airplay.streamer.engine.SessionState
import com.airplay.streamer.engine.SinkFactory
import com.airplay.streamer.engine.SpeakerStatus
import com.airplay.streamer.engine.Transport
import com.airplay.streamer.service.AudioCaptureService
import com.airplay.streamer.service.MediaInfoTracker
import com.airplay.streamer.util.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class MainUiState(
    val rows: List<SpeakerRow> = emptyList(),
    val session: SessionState = SessionState(),
    val searching: Boolean = true,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = DiscoveryRepository.getInstance(application)
    private val prefs = Prefs(application)
    private val manualDevices = MutableStateFlow<List<AirPlayDevice>>(emptyList())

    private val tracker = MediaInfoTracker(application)
    val mediaInfo: StateFlow<MediaInfoTracker.MediaInfo> = tracker.mediaInfo

    val uiState: StateFlow<MainUiState> = combine(
        repository.devices, manualDevices, AudioCaptureService.state
    ) { discovered, manual, session ->
        val all = (discovered + manual.filter { m -> discovered.none { it.identity == m.identity } })
        val rows = all.map { d ->
            SpeakerRow(d, session.speaker(d.identity), SinkFactory.transportFor(d, prefs.preferAirPlay2))
        }.sortedWith(
            compareByDescending<SpeakerRow> { it.active }
                .thenByDescending { it.supported }
                .thenBy { it.device.displayName.lowercase() }
        )
        MainUiState(rows = rows, session = session, searching = all.isEmpty())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MainUiState())

    init {
        repository.startDiscovery()
        tracker.start()
    }

    fun refreshTracker() = tracker.tryRegister()

    fun onForeground() = repository.ensureFresh()

    fun addManualDevice(device: AirPlayDevice) {
        manualDevices.value = manualDevices.value.filterNot { it.identity == device.identity } + device
    }

    fun refreshDiscovery() = repository.refresh()

    /** Track progress 0..1, or null when the duration is unknown. */
    fun trackProgress(): Float? {
        val duration = tracker.mediaInfo.value.duration
        if (duration <= 0) return null
        return (tracker.currentPosition().toFloat() / duration).coerceIn(0f, 1f)
    }

    fun togglePlayback() = tracker.togglePlayback()
    fun next() = tracker.next()
    fun previous() = tracker.previous()

    fun playingNames(): String = uiState.value.session.speakers
        .filter { it.status == SpeakerStatus.PLAYING }.joinToString(" + ") { it.name.lowercase() }

    override fun onCleared() {
        repository.stopDiscovery()
        tracker.stop()
        super.onCleared()
    }

    companion object {
        fun isSupported(transport: Transport) = transport != Transport.UNSUPPORTED
    }
}
