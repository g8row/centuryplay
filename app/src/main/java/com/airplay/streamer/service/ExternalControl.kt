package com.airplay.streamer.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.airplay.streamer.discovery.AirPlayDevice
import com.airplay.streamer.discovery.DiscoveryRepository
import com.airplay.streamer.shizuku.ShizukuManager
import com.airplay.streamer.util.LogServer
import com.airplay.streamer.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Control from other apps (Home Assistant, Tasker, MacroDroid, adb), issue #13. Off unless
 * "allow external control" is on in settings, since any app on the phone can send these.
 *
 * Actions (`com.airplay.streamer.action.*`):
 *  - START       `device` = speaker name(s), comma separated; none = the speakers used last time
 *  - STOP        optional `device` = only those speakers; none = stop streaming
 *  - TOGGLE      START when not streaming, STOP otherwise
 *  - SET_VOLUME  `volume` = 0-100; optional `device` = only those speakers, else the group
 *
 * Sent as a broadcast ([ExternalControlReceiver]) or as an activity ([ExternalControlActivity]);
 * the activity can also show the capture prompt when Shizuku isn't set up. Every command
 * answers with a `com.airplay.streamer.action.RESULT` broadcast (`action`, `success`, `message`).
 */
object ExternalControl {
    private const val PREFIX = "com.airplay.streamer.action."
    const val ACTION_START = PREFIX + "START"
    const val ACTION_STOP = PREFIX + "STOP"
    const val ACTION_TOGGLE = PREFIX + "TOGGLE"
    const val ACTION_SET_VOLUME = PREFIX + "SET_VOLUME"
    const val ACTION_RESULT = PREFIX + "RESULT"
    const val EXTRA_DEVICE = "device"
    const val EXTRA_VOLUME = "volume"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Runs [intent]; [done] is called once the command finished (for goAsync / finish()). */
    fun handle(context: Context, intent: Intent, fromActivity: Boolean, done: () -> Unit) {
        val action = intent.action ?: return done()
        if (!Prefs(context).externalControl) {
            LogServer.log("External control: ignored ${action.removePrefix(PREFIX)} (turn on \"allow external control\" in settings)")
            return done()
        }
        ShizukuManager.init(context)
        val names = intent.getStringExtra(EXTRA_DEVICE)?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        scope.launch {
            val result = try {
                when (action) {
                    ACTION_START -> start(context, names, fromActivity)
                    ACTION_STOP -> stop(context, names)
                    ACTION_TOGGLE ->
                        if (AudioCaptureService.state.value.capturing) stop(context, names) else start(context, names, fromActivity)
                    ACTION_SET_VOLUME -> setVolume(intent, names)
                    else -> Result(false, "unknown action $action")
                }
            } catch (e: Exception) {
                Result(false, e.message ?: e.javaClass.simpleName)
            }
            LogServer.log("External control: ${action.removePrefix(PREFIX)}${if (names.isEmpty()) "" else " $names"} → ${result.message}")
            context.sendBroadcast(
                Intent(ACTION_RESULT)
                    .putExtra("action", action.removePrefix(PREFIX))
                    .putExtra("success", result.success)
                    .putExtra("message", result.message)
            )
            done()
        }
    }

    private data class Result(val success: Boolean, val message: String)

    private suspend fun start(context: Context, names: List<String>, fromActivity: Boolean): Result {
        val prefs = Prefs(context)
        if (names.isEmpty() && prefs.lastSpeakers.isEmpty()) return Result(false, "no device given and no speakers used before")
        if (!fromActivity && !StreamController.canConnectSilently(context)) {
            return Result(false, "capture needs approval: set up shizuku, run the adb appops command, or send this as an activity intent")
        }
        val repo = DiscoveryRepository.getInstance(context)
        repo.startDiscovery()
        try {
            // Speakers are usually cached; otherwise give mDNS a few seconds.
            val resolve = { list: List<AirPlayDevice> ->
                if (names.isEmpty()) prefs.lastSpeakers.map { id -> list.firstOrNull { it.identity == id } }
                else names.map { match(list, it) }
            }
            val found = withTimeoutOrNull(5000) { repo.devices.first { list -> resolve(list).all { it != null } } }
                ?: repo.devices.value
            val resolved = resolve(found)
            val devices = resolved.filterNotNull().distinctBy { it.identity }
            if (devices.isEmpty()) return Result(false, "speaker not found: ${names.ifEmpty { listOf("last used") }.joinToString()}")
            devices.filterNot { StreamController.isConnected(it.identity) }.forEach { StreamController.connect(context, it) }
            val missing = names.filterIndexed { i, _ -> resolved[i] == null }
            val playing = devices.joinToString { it.displayName }
            return if (missing.isEmpty()) Result(true, "streaming to $playing")
            else Result(true, "streaming to $playing; not found: ${missing.joinToString()}")
        } finally {
            repo.stopDiscovery()
        }
    }

    private fun stop(context: Context, names: List<String>): Result {
        val state = AudioCaptureService.state.value
        if (!state.capturing) return Result(true, "not streaming")
        if (names.isEmpty()) {
            StreamController.stopAll(context)
            return Result(true, "stopped")
        }
        val targets = names.mapNotNull { name -> matchActive(state.speakers, name) }.distinctBy { it.identity }
        if (targets.isEmpty()) return Result(false, "not streaming to ${names.joinToString()}")
        targets.forEach { StreamController.disconnect(context, it.identity) }
        return Result(true, "stopped ${targets.joinToString { it.name }}")
    }

    private fun setVolume(intent: Intent, names: List<String>): Result {
        // Accept int or string extras ("am broadcast --ei volume 30" or "--es volume 30").
        val raw = intent.extras?.get(EXTRA_VOLUME)?.toString()?.toFloatOrNull()
            ?: return Result(false, "missing volume (0-100)")
        val volume = raw.coerceIn(0f, 100f) / 100f
        val state = AudioCaptureService.state.value
        if (!state.capturing) return Result(false, "not streaming")
        if (names.isEmpty()) {
            StreamController.setGroupVolume(volume)
            return Result(true, "volume ${raw.toInt()}")
        }
        val targets = names.mapNotNull { name -> matchActive(state.speakers, name) }.distinctBy { it.identity }
        if (targets.isEmpty()) return Result(false, "not streaming to ${names.joinToString()}")
        targets.forEach { StreamController.setSpeakerVolume(it.identity, volume) }
        return Result(true, "volume ${raw.toInt()} on ${targets.joinToString { it.name }}")
    }

    /** Exact name / identity / device ID first, then a unique "name contains". */
    private fun match(devices: List<AirPlayDevice>, query: String): AirPlayDevice? {
        devices.firstOrNull { matches(it.identity, it.displayName, query) || it.deviceId.equals(query, ignoreCase = true) }?.let { return it }
        return devices.filter { it.displayName.contains(query, ignoreCase = true) }.singleOrNull()
    }

    /** Same rules for speakers in the running session (exact, then a unique "contains"). */
    private fun matchActive(speakers: List<com.airplay.streamer.engine.SpeakerState>, query: String) =
        speakers.firstOrNull { matches(it.identity, it.name, query) }
            ?: speakers.filter { it.name.contains(query, ignoreCase = true) }.singleOrNull()

    private fun matches(identity: String, name: String, query: String) =
        name.equals(query, ignoreCase = true) || identity.equals(query, ignoreCase = true)
}

/** Broadcast form: `adb shell am broadcast -p com.airplay.streamer -a com.airplay.streamer.action.START --es device Kitchen`
 *  (needs the package or component: Android doesn't deliver implicit broadcasts to manifest receivers). */
class ExternalControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        ExternalControl.handle(context.applicationContext, intent, fromActivity = false) { pending.finish() }
    }
}

/**
 * Activity form (Home Assistant `command_activity`, Tasker "send intent → activity"). Invisible;
 * being an activity it may start capture from the background and show the capture prompt.
 */
class ExternalControlActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ExternalControl.handle(this, intent, fromActivity = true) { finish() }
    }
}
