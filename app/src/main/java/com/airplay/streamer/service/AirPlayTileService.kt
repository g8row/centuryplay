package com.airplay.streamer.service

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.airplay.streamer.R
import com.airplay.streamer.TileDeviceActivity
import com.airplay.streamer.discovery.DiscoveryRepository
import com.airplay.streamer.engine.SessionState
import com.airplay.streamer.engine.SpeakerStatus
import com.airplay.streamer.shizuku.ShizukuManager
import com.airplay.streamer.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Quick Settings tile.
 *  - Streaming: tap stops.
 *  - Idle: tap reconnects the speakers used last time (no UI when capture can start
 *    silently), otherwise opens the speaker picker. Long-press always opens the picker.
 */
class AirPlayTileService : TileService() {

    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onStartListening() {
        super.onStartListening()
        ShizukuManager.init(this)
        DiscoveryRepository.getInstance(this).startDiscovery()
        job = scope.launch { AudioCaptureService.state.collect { updateTile(it) } }
    }

    override fun onStopListening() {
        job?.cancel()
        DiscoveryRepository.getInstance(this).stopDiscovery()
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val state = AudioCaptureService.state.value
        if (state.capturing) {
            StreamController.stopAll(this)
            return
        }
        val last = Prefs(this).lastSpeakers
        val devices = DiscoveryRepository.getInstance(this).devices.value.filter { it.identity in last }
        if (devices.isNotEmpty() && StreamController.canConnectSilently(this)) {
            devices.forEach { StreamController.connect(this, it) }
        } else {
            openPicker()
        }
    }

    private fun openPicker() {
        val intent = Intent(this, TileDeviceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTile(state: SessionState) {
        val tile = qsTile ?: return
        val playing = state.speakers.filter { it.status == SpeakerStatus.PLAYING }
        tile.state = if (state.capturing) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        tile.subtitle = when {
            playing.isNotEmpty() -> playing.joinToString(", ") { it.name.lowercase() }
            state.capturing -> getString(R.string.connecting)
            else -> getString(R.string.tile_disconnected)
        }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_speaker)
        tile.updateTile()
    }
}
