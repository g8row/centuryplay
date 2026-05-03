package com.airplay.streamer.service

import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.airplay.streamer.R
import com.airplay.streamer.TileDeviceActivity

class AirPlayTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        // Pre-warm discovery when the shade is open
        com.airplay.streamer.discovery.DiscoveryRepository.getInstance(this).startDiscovery()
        updateTile()
    }

    override fun onStopListening() {
        super.onStopListening()
        // Stop discovery when shade closes to save battery
        com.airplay.streamer.discovery.DiscoveryRepository.getInstance(this).stopDiscovery()
    }

    override fun onClick() {
        super.onClick()
        val isStreaming = AudioCaptureService.instance?.isCurrentlyStreaming() == true
        
        if (isStreaming) {
            // Stop streaming
            val serviceIntent = Intent(this, AudioCaptureService::class.java).apply {
                action = AudioCaptureService.ACTION_STOP
            }
            startService(serviceIntent)
            // Tile will update via onStartListening soon, but we can update it now for responsiveness
            updateTile(isStreaming = false)
        } else {
            // Open device selection activity
            val intent = Intent(this, TileDeviceActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+ requires PendingIntent for starting activity from Tile
                val pendingIntent = android.app.PendingIntent.getActivity(
                    this, 0, intent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                startActivityAndCollapse(intent)
            }
        }
    }

    private fun updateTile(isStreaming: Boolean? = null) {
        val streaming = isStreaming ?: (AudioCaptureService.instance?.isCurrentlyStreaming() == true)
        val tile = qsTile ?: return

        tile.state = if (streaming) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        tile.subtitle = if (streaming) getString(R.string.tile_connected, "speaker") else getString(R.string.tile_disconnected)
        tile.icon = Icon.createWithResource(this, if (streaming) R.drawable.ic_stop else R.drawable.ic_speaker)
        
        tile.updateTile()
    }
}
