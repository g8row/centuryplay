package com.airplay.streamer.router

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.airplay.streamer.R
import com.airplay.streamer.discovery.AirPlayDevice
import com.airplay.streamer.discovery.DiscoveryRepository
import com.airplay.streamer.engine.SessionState
import com.airplay.streamer.engine.SinkFactory
import com.airplay.streamer.engine.SpeakerStatus
import com.airplay.streamer.engine.Transport
import com.airplay.streamer.service.AudioCaptureService
import com.airplay.streamer.service.StreamController
import com.airplay.streamer.util.LogServer
import com.android.media.remotedisplay.RemoteDisplay
import com.android.media.remotedisplay.RemoteDisplayProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Publishes the speakers as remote displays: the system's Cast quick settings tile, its device
 * picker and Settings → Cast list them next to Cast devices, and the tile shows "casting to …"
 * while streaming. Connecting starts streaming there; disconnecting (or "stop casting") stops.
 *
 * The system only binds providers that hold REMOTE_DISPLAY_PROVIDER, i.e. when centuryplay is
 * a privileged system app (root module or ROM); otherwise this service is simply never used.
 * The provider classes come from the platform's com.android.media.remotedisplay library.
 */
class AirPlayCastProviderService : Service() {

    private var provider: Provider? = null

    override fun onBind(intent: Intent?): IBinder? {
        if (intent?.action != RemoteDisplayProvider.SERVICE_INTERFACE) return null
        return try {
            (provider ?: Provider().also { provider = it }).binder
        } catch (e: LinkageError) {
            LogServer.log("Cast tile: platform remote display library unavailable (${e.message})")
            null
        }
    }

    override fun onDestroy() {
        provider?.release()
        provider = null
        super.onDestroy()
    }

    private inner class Provider : RemoteDisplayProvider(this@AirPlayCastProviderService) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private val repo = DiscoveryRepository.getInstance(this@AirPlayCastProviderService)
        private var discovering = false
        private var devices: Map<String, AirPlayDevice> = emptyMap()

        init {
            scope.launch {
                combine(repo.devices, AudioCaptureService.state) { d, s -> d to s }.collect { (d, s) -> publish(d, s) }
            }
        }

        fun release() {
            setDiscovering(false)
            scope.cancel()
        }

        private fun publish(list: List<AirPlayDevice>, state: SessionState) {
            devices = list.filter { SinkFactory.transportFor(it) != Transport.UNSUPPORTED }.associateBy { it.identity }
            for (gone in displays.filter { it.id !in devices }) removeDisplay(gone)
            for (device in devices.values) {
                val speaker = state.speaker(device.identity)
                val existing = findRemoteDisplay(device.identity)
                val display = existing ?: RemoteDisplay(device.identity, device.displayName)
                display.name = device.displayName
                display.description = getString(R.string.route_description)
                display.status = when (speaker?.status) {
                    SpeakerStatus.PLAYING -> RemoteDisplay.STATUS_CONNECTED
                    SpeakerStatus.CONNECTING, SpeakerStatus.RECONNECTING -> RemoteDisplay.STATUS_CONNECTING
                    else -> RemoteDisplay.STATUS_AVAILABLE
                }
                display.volumeHandling = RemoteDisplay.PLAYBACK_VOLUME_VARIABLE
                display.volumeMax = 100
                display.volume = ((speaker?.volume ?: 0.6f) * 100).roundToInt()
                if (existing == null) addDisplay(display) else updateDisplay(display)
            }
        }

        override fun onDiscoveryModeChanged(mode: Int) = setDiscovering(mode != DISCOVERY_MODE_NONE)

        private fun setDiscovering(on: Boolean) {
            if (on == discovering) return
            discovering = on
            if (on) repo.startDiscovery() else repo.stopDiscovery()
        }

        override fun onConnect(display: RemoteDisplay) {
            val device = devices[display.id] ?: return
            LogServer.log("Cast tile: connect ${device.displayName}")
            StreamController.connect(this@AirPlayCastProviderService, device)
        }

        override fun onDisconnect(display: RemoteDisplay) {
            LogServer.log("Cast tile: disconnect ${display.name}")
            StreamController.disconnect(this@AirPlayCastProviderService, display.id)
        }

        override fun onSetVolume(display: RemoteDisplay, volume: Int) =
            StreamController.setSpeakerVolume(display.id, volume.coerceIn(0, 100) / 100f)

        override fun onAdjustVolume(display: RemoteDisplay, delta: Int) {
            val current = AudioCaptureService.state.value.speaker(display.id)?.volume ?: return
            StreamController.setSpeakerVolume(display.id, (current + delta / 100f).coerceIn(0f, 1f))
        }
    }
}
