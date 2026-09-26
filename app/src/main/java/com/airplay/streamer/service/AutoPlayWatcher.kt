package com.airplay.streamer.service

import android.content.Context
import com.airplay.streamer.discovery.DiscoveryRepository
import com.airplay.streamer.util.LogServer
import com.airplay.streamer.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "Auto-play on my speakers": when any app starts playing and the speakers used last time
 * are on the network, start streaming to them without any interaction.
 *
 * Only acts when capture can start silently (Shizuku, or a held projection) — it never pops
 * up UI on its own — and never when a stream is already running or headphones are in use.
 * Stopping a stream by hand suppresses auto-play for an hour (you wanted the phone speaker).
 */
class AutoPlayWatcher(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val tracker = MediaInfoTracker(context)
    private val prefs = Prefs(context)
    private var job: Job? = null
    private var attempt: Job? = null

    fun start() {
        tracker.start()
        job = scope.launch {
            tracker.playbackStarted.collect { pkg -> onPlaybackStarted(pkg) }
        }
    }

    fun stop() {
        job?.cancel()
        attempt?.cancel()
        tracker.stop()
        scope.cancel()
    }

    private fun onPlaybackStarted(pkg: String) {
        if (!prefs.autoPlay || pkg == context.packageName) return
        if (System.currentTimeMillis() < prefs.autoPlaySuppressedUntil) return
        if (AudioCaptureService.state.value.capturing) return
        if (headphonesConnected()) return
        if (!StreamController.canConnectSilently(context)) {
            LogServer.log("Auto-play: $pkg started, but capture can't start silently (set up Shizuku)")
            return
        }
        val wanted = prefs.lastSpeakers
        if (wanted.isEmpty()) return
        attempt?.cancel()
        attempt = scope.launch {
            val repo = DiscoveryRepository.getInstance(context)
            repo.startDiscovery()
            try {
                // Speakers are usually cached; otherwise give mDNS a few seconds.
                val found = withTimeoutOrNull(6000) {
                    repo.devices.first { list -> wanted.all { id -> list.any { it.identity == id } } }
                } ?: repo.devices.value
                val devices = found.filter { it.identity in wanted }
                if (devices.isEmpty()) {
                    LogServer.log("Auto-play: usual speakers not on this network")
                    return@launch
                }
                LogServer.log("Auto-play: $pkg started → ${devices.joinToString { it.displayName }}")
                devices.forEach { StreamController.connect(context, it) }
            } finally {
                repo.stopDiscovery()
            }
        }
    }

    private fun headphonesConnected(): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        return am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type in setOf(
                android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, android.media.AudioDeviceInfo.TYPE_BLE_HEADSET,
                android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES, android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
                android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
            )
        }
    }
}
