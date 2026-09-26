package com.airplay.streamer.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.airplay.streamer.discovery.AirPlayDevice
import com.airplay.streamer.router.MediaProjectionConsentActivity
import com.airplay.streamer.shizuku.ShizukuManager
import com.airplay.streamer.util.LogServer
import com.airplay.streamer.util.Prefs

/** Commands for the streaming service, usable from activities, the QS tile and the route provider. */
object StreamController {

    /** True if a speaker can be connected right now without showing any UI. */
    fun canConnectSilently(context: Context): Boolean {
        if (!hasRuntimePermissions(context)) return false
        if (AudioCaptureService.hasActiveProjection) return true
        if (AudioCaptureService.state.value.capturing) return true
        return shizukuCaptureAvailable(context)
    }

    fun shizukuCaptureAvailable(context: Context): Boolean {
        if (!Prefs(context).useShizukuCapture || Build.VERSION.SDK_INT < 33) return false
        // May be called from the route provider/tile before any activity initialised Shizuku.
        ShizukuManager.init(context)
        ShizukuManager.refresh()
        return ShizukuManager.status.value == ShizukuManager.Status.READY
    }

    fun hasRuntimePermissions(context: Context): Boolean {
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return needed.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }

    /** Connects [device], adding it to the current session (multi-room) if one is running. */
    fun connect(context: Context, device: AirPlayDevice) {
        Prefs(context).autoPlaySuppressedUntil = 0
        if (canConnectSilently(context)) {
            try {
                ContextCompat.startForegroundService(context, serviceIntent(context, AudioCaptureService.ACTION_CONNECT).putExtra(AudioCaptureService.EXTRA_DEVICE, device))
                return
            } catch (e: Exception) {
                // Background FGS start not allowed from here — go through the activity.
                LogServer.log("Direct connect not allowed (${e.message}); using consent activity")
            }
        }
        context.startActivity(
            Intent(context, MediaProjectionConsentActivity::class.java)
                .putExtra(MediaProjectionConsentActivity.EXTRA_DEVICE, device)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun disconnect(context: Context, identity: String) = send(context, AudioCaptureService.ACTION_DISCONNECT) {
        putExtra(AudioCaptureService.EXTRA_IDENTITY, identity)
    }

    /** Stop streaming to every speaker (keeps capture permission for a fast restart). */
    fun stopAll(context: Context) {
        Prefs(context).autoPlaySuppressedUntil = System.currentTimeMillis() + 60 * 60_000L
        send(context, AudioCaptureService.ACTION_STOP)
    }

    fun setSleepTimer(context: Context, minutes: Int) = send(context, AudioCaptureService.ACTION_SLEEP_TIMER) {
        putExtra(AudioCaptureService.EXTRA_MINUTES, minutes)
    }

    fun toggle(context: Context, device: AirPlayDevice) {
        val st = AudioCaptureService.state.value
        if (st.speaker(device.identity) != null) disconnect(context, device.identity) else connect(context, device)
    }

    fun isConnected(identity: String): Boolean = AudioCaptureService.state.value.speaker(identity) != null

    fun setSpeakerVolume(identity: String, volume: Float) {
        AudioCaptureService.instance?.setSpeakerVolume(identity, volume)
    }

    fun submitPin(identity: String, pin: String?) {
        AudioCaptureService.instance?.submitPin(identity, pin)
    }

    /** Pair with the code shown on the receiver (Apple TV) or its AirPlay password. */
    fun pairWithCode(context: Context, device: AirPlayDevice) {
        val svc = AudioCaptureService.instance
        if (svc != null && canConnectSilently(context)) svc.pairWithCode(device) else {
            Prefs(context).raw.edit().putString("pair_with_code_next", device.identity).apply()
            connect(context, device)
        }
    }

    fun forgetPairing(context: Context, identity: String) = Prefs(context).hapStore.clear(identity)

    fun setSpeakerOffset(context: Context, identity: String, ms: Int) {
        AudioCaptureService.instance?.setSpeakerOffset(identity, ms) ?: Prefs(context).setSpeakerOffsetMs(identity, ms)
    }

    fun setGroupVolume(volume: Float) {
        AudioCaptureService.instance?.setGroupVolume(volume)
    }

    private fun serviceIntent(context: Context, action: String) = Intent(context, AudioCaptureService::class.java).setAction(action)

    private fun send(context: Context, action: String, extras: Intent.() -> Unit = {}) {
        if (AudioCaptureService.instance == null) return
        runCatching { context.startService(serviceIntent(context, action).apply(extras)) }
            .onFailure { LogServer.log("Service command $action failed: ${it.message}") }
    }
}
