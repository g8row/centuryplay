package com.airplay.streamer.router

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.media.MediaRoute2Info
import android.media.MediaRoute2ProviderService
import android.media.RouteDiscoveryPreference
import android.media.RoutingSessionInfo
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.mediarouter.media.MediaControlIntent
import com.airplay.streamer.R
import com.airplay.streamer.discovery.AirPlayDevice
import com.airplay.streamer.discovery.DiscoveryRepository
import com.airplay.streamer.engine.SessionState
import com.airplay.streamer.engine.SinkFactory
import com.airplay.streamer.engine.SpeakerStatus
import com.airplay.streamer.engine.Transport
import com.airplay.streamer.service.AudioCaptureService
import com.airplay.streamer.service.MediaInfoTracker
import com.airplay.streamer.service.StreamController
import com.airplay.streamer.util.LogServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Publishes AirPlay speakers as system media routes and puts them into the **system
 * Output Switcher** (the media card's output chip) for whatever app is playing.
 *
 * SystemUI (SettingsLib InfoMediaManager) lists, for the playing app, the selected /
 * selectable / transferable routes of that app's latest routing session, and
 * MediaRouter2Manager.getFilteredRoutes lets routes named by the session bypass the app's
 * feature filter. Ordinary players never ask for our routes, so we publish a routing session
 * *on behalf of* the playing app (clientPackageName = that app, created with REQUEST_ID_NONE):
 *  - idle:      selected = our "this phone" route, transferable = every AirPlay speaker
 *               → tapping a speaker in the switcher starts streaming there;
 *  - streaming: selected = playing speakers, selectable = the rest (multi-room),
 *               transferable = "this phone" (→ stop streaming).
 * A second session owned by our own package exists while streaming: it's what allows our
 * remote-volume MediaSession to receive the hardware volume keys
 * (MediaSessionRecord.canHandleVolumeKey, b/228021646).
 */
class AirPlayRouteProviderService : MediaRoute2ProviderService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val repo by lazy { DiscoveryRepository.getInstance(this) }
    private lateinit var tracker: MediaInfoTracker

    /** sessionId -> client package */
    private val sessions = mutableMapOf<String, String>()
    private var pendingRequest: Long = -1L
    private var pendingSessionId: String? = null
    private var lastDevices: List<AirPlayDevice> = emptyList()
    private var lastState = SessionState()
    private var playingPackage: String? = null

    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as android.media.AudioManager }
    private val deviceCallback = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out android.media.AudioDeviceInfo>?) = publish()
        override fun onAudioDevicesRemoved(removed: Array<out android.media.AudioDeviceInfo>?) = publish()
    }

    /**
     * Headphones/Bluetooth/USB outputs are system routes; while our idle session stands in for
     * the app, the switcher would hide them. So only offer the idle session on the phone speaker.
     */
    private fun externalOutputConnected(): Boolean = audioManager.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).any {
        it.type in EXTERNAL_OUTPUT_TYPES
    }

    override fun onCreate() {
        super.onCreate()
        audioManager.registerAudioDeviceCallback(deviceCallback, android.os.Handler(mainLooper))
        repo.startDiscovery()
        tracker = MediaInfoTracker(this).also { it.start() }
        scope.launch {
            combine(repo.devices, AudioCaptureService.state) { devices, state -> devices to state }
                .collect { (devices, state) ->
                    lastDevices = devices
                    lastState = state
                    publish()
                }
        }
        scope.launch {
            tracker.mediaInfo.map { it.packageName }.distinctUntilChanged().collect { pkg ->
                playingPackage = pkg
                publish()
            }
        }
    }

    override fun onDestroy() {
        runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
        repo.stopDiscovery()
        tracker.stop()
        scope.cancel()
        super.onDestroy()
    }

    override fun onDiscoveryPreferenceChanged(preference: RouteDiscoveryPreference) {
        // Discovery runs for the whole lifetime of the provider (it's only bound while the
        // system/apps are interested in routes), so nothing to do here.
    }

    private fun routeId(d: AirPlayDevice) = "airplay_${d.identity}"
    private fun deviceFor(routeId: String): AirPlayDevice? = lastDevices.firstOrNull { routeId(it) == routeId }
    private fun identityOf(routeId: String) = routeId.removePrefix("airplay_")

    // ------------------------------------------------------------------ publishing

    private fun publish() {
        val state = lastState
        // Route ids must be unique or MediaRoute2ProviderInfo throws (and takes the app down).
        val speakers = lastDevices.filter { SinkFactory.transportFor(it) != Transport.UNSUPPORTED }.distinctBy { routeId(it) }
        val routes = speakers.map { toRoute(it, state) } + phoneRoute(state)
        notifyRoutes(routes)
        val airplayIds = speakers.map { routeId(it) }
        val selected = state.activeSpeakers.map { "airplay_${it.identity}" }.filter { it in airplayIds }
        val streaming = state.capturing && selected.isNotEmpty()

        // Answer a pending create request once streaming actually started.
        pendingRequest.takeIf { it != -1L }?.let { req ->
            if (state.isStreaming) {
                val id = pendingSessionId ?: return@let
                notifySessionCreated(req, sessionInfo(id, sessions[id] ?: packageName, selected, airplayIds, streaming))
                pendingRequest = -1L
                pendingSessionId = null
            }
            return
        }

        val wanted = LinkedHashMap<String, String>()
        if (streaming) wanted[OWN_SESSION] = packageName
        val offerInSwitcher = com.airplay.streamer.util.Prefs(this).systemSwitcher &&
            (streaming || !externalOutputConnected())
        if (offerInSwitcher) {
            playingPackage?.takeIf { it != packageName && airplayIds.isNotEmpty() }?.let { wanted[clientSessionId(it)] = it }
        }

        // Release sessions we no longer want.
        (sessions.keys - wanted.keys).forEach { id ->
            sessions.remove(id)
            notifySessionReleased(id)
        }
        for ((id, client) in wanted) {
            val info = sessionInfo(id, client, selected, airplayIds, streaming)
            if (sessions.put(id, client) == null) notifySessionCreated(REQUEST_ID_NONE, info) else notifySessionUpdated(info)
        }
    }

    private fun clientSessionId(pkg: String) = "centuryplay_for_$pkg"

    private fun sessionInfo(id: String, client: String, selected: List<String>, all: List<String>, streaming: Boolean): RoutingSessionInfo {
        val b = RoutingSessionInfo.Builder(id, client)
            .setName(getString(R.string.app_name))
            .setVolumeHandling(MediaRoute2Info.PLAYBACK_VOLUME_VARIABLE)
            .setVolumeMax(100)
            .setVolume(((AudioCaptureService.instance?.groupVolume ?: 0.6f) * 100).toInt())
        if (streaming) {
            selected.forEach { b.addSelectedRoute(it); b.addDeselectableRoute(it) }
            all.filterNot { it in selected }.forEach { b.addSelectableRoute(it); b.addTransferableRoute(it) }
            b.addTransferableRoute(PHONE_ROUTE)
        } else {
            b.addSelectedRoute(PHONE_ROUTE)
            all.forEach { b.addTransferableRoute(it) }
        }
        return b.build()
    }

    private fun toRoute(d: AirPlayDevice, state: SessionState): MediaRoute2Info {
        val speaker = state.speaker(d.identity)
        val conn = when (speaker?.status) {
            SpeakerStatus.PLAYING -> MediaRoute2Info.CONNECTION_STATE_CONNECTED
            SpeakerStatus.CONNECTING, SpeakerStatus.RECONNECTING -> MediaRoute2Info.CONNECTION_STATE_CONNECTING
            else -> MediaRoute2Info.CONNECTION_STATE_DISCONNECTED
        }
        val extras = Bundle().apply {
            // AndroidX MediaRouter expects these; without them some apps crash (Apple Music).
            putInt("androidx.mediarouter.media.KEY_DEVICE_TYPE", 2) // speaker
            putInt("androidx.mediarouter.media.KEY_PLAYBACK_TYPE", 1) // remote
            putString("androidx.mediarouter.media.KEY_ORIGINAL_ROUTE_ID", routeId(d))
            putParcelableArrayList(
                "androidx.mediarouter.media.KEY_CONTROL_FILTERS",
                arrayListOf(android.content.IntentFilter().apply { addCategory(MediaControlIntent.CATEGORY_LIVE_AUDIO) })
            )
            putBundle("androidx.mediarouter.media.KEY_EXTRAS", Bundle())
        }
        return MediaRoute2Info.Builder(routeId(d), d.displayName)
            .setDescription(getString(R.string.route_description))
            .addFeature(MediaRoute2Info.FEATURE_LIVE_AUDIO)
            .setConnectionState(conn)
            .setVolumeHandling(MediaRoute2Info.PLAYBACK_VOLUME_VARIABLE)
            .setVolumeMax(100)
            .setVolume(((speaker?.volume ?: 0.6f) * 100).toInt())
            .setExtras(extras)
            .build()
    }

    /** Stand-in for the phone's own speaker inside our sessions (system routes can't be listed there). */
    private fun phoneRoute(state: SessionState): MediaRoute2Info =
        MediaRoute2Info.Builder(PHONE_ROUTE, getString(R.string.route_this_phone))
            .setDescription(getString(R.string.route_this_phone_desc))
            .addFeature(MediaRoute2Info.FEATURE_LIVE_AUDIO)
            .setConnectionState(if (state.capturing) MediaRoute2Info.CONNECTION_STATE_DISCONNECTED else MediaRoute2Info.CONNECTION_STATE_CONNECTED)
            .setVolumeHandling(MediaRoute2Info.PLAYBACK_VOLUME_FIXED)
            .build()

    // ------------------------------------------------------------------ requests

    override fun onCreateSession(requestId: Long, packageName: String, routeId: String, sessionHints: Bundle?) {
        if (routeId == PHONE_ROUTE) {
            StreamController.stopAll(this)
            return notifyRequestFailed(requestId, REASON_ROUTE_NOT_AVAILABLE)
        }
        val device = deviceFor(routeId) ?: return notifyRequestFailed(requestId, REASON_ROUTE_NOT_AVAILABLE)
        LogServer.log("Media router: $packageName selected ${device.displayName}")
        val id = clientSessionId(packageName)
        sessions[id] = packageName
        if (StreamController.canConnectSilently(this)) {
            StreamController.connect(this, device)
            notifySessionCreated(requestId, sessionInfo(id, packageName, listOf(routeId), lastDevices.map { routeId(it) }, true))
        } else {
            pendingRequest = requestId
            pendingSessionId = id
            askForConsent(device)
        }
    }

    override fun onReleaseSession(requestId: Long, sessionId: String) {
        sessions.remove(sessionId)
        // "Stop casting" in the output switcher.
        StreamController.stopAll(this)
        notifySessionReleased(sessionId)
    }

    override fun onSelectRoute(requestId: Long, sessionId: String, routeId: String) {
        deviceFor(routeId)?.let { StreamController.connect(this, it) } ?: notifyRequestFailed(requestId, REASON_ROUTE_NOT_AVAILABLE)
    }

    override fun onDeselectRoute(requestId: Long, sessionId: String, routeId: String) {
        StreamController.disconnect(this, identityOf(routeId))
    }

    override fun onTransferToRoute(requestId: Long, sessionId: String, routeId: String) {
        if (routeId == PHONE_ROUTE) {
            StreamController.stopAll(this)
            return
        }
        val device = deviceFor(routeId) ?: return notifyRequestFailed(requestId, REASON_ROUTE_NOT_AVAILABLE)
        LogServer.log("Output switcher: transfer to ${device.displayName}")
        val others = AudioCaptureService.state.value.speakers.filter { "airplay_${it.identity}" != routeId }
        // Connect first so the session (and capture) survives the switch.
        StreamController.connect(this, device)
        others.forEach { StreamController.disconnect(this, it.identity) }
    }

    override fun onSetRouteVolume(requestId: Long, routeId: String, volume: Int) {
        if (routeId != PHONE_ROUTE) StreamController.setSpeakerVolume(identityOf(routeId), volume / 100f)
    }

    override fun onSetSessionVolume(requestId: Long, sessionId: String, volume: Int) {
        StreamController.setGroupVolume(volume / 100f)
    }

    /** No silent path: ask via a notification (a service may not start activities). */
    private fun askForConsent(device: AirPlayDevice) {
        val intent = Intent(this, MediaProjectionConsentActivity::class.java)
            .putExtra(MediaProjectionConsentActivity.EXTRA_DEVICE, device)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(this, device.identity.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("airplay_consent", getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH))
        nm.notify(
            1001, NotificationCompat.Builder(this, "airplay_consent")
                .setSmallIcon(R.drawable.ic_speaker)
                .setContentTitle(getString(R.string.consent_notification_title, device.displayName))
                .setContentText(getString(R.string.consent_notification_text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
        )
    }

    companion object {
        private const val OWN_SESSION = "centuryplay_session"
        private const val PHONE_ROUTE = "centuryplay_phone"
        private val EXTERNAL_OUTPUT_TYPES = setOf(
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            android.media.AudioDeviceInfo.TYPE_BLE_HEADSET,
            android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER,
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
            android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
            android.media.AudioDeviceInfo.TYPE_USB_DEVICE,
            android.media.AudioDeviceInfo.TYPE_HEARING_AID,
            android.media.AudioDeviceInfo.TYPE_HDMI,
        )
    }
}
