package com.airplay.streamer.router

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.airplay.streamer.R
import com.airplay.streamer.airplay2.AirPlay2Capabilities
import com.airplay.streamer.discovery.AirPlayDevice
import com.airplay.streamer.engine.SinkFactory
import com.airplay.streamer.engine.Transport
import com.airplay.streamer.service.AudioCaptureService
import com.airplay.streamer.service.StreamController

/**
 * Invisible trampoline that gets everything needed to start streaming to [EXTRA_DEVICE]:
 * runtime permissions, then (unless Shizuku can capture) the MediaProjection consent —
 * which Android skips without any UI when the PROJECT_MEDIA app-op is pre-approved.
 * Used by the main screen, the Quick Settings tile and the system output switcher.
 */
class MediaProjectionConsentActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DEVICE = "device"
        /** Skip the silent (Shizuku) path, e.g. because it just failed. */
        const val EXTRA_FORCE_PROJECTION = "force_projection"
    }

    private var device: AirPlayDevice? = null

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val d = device
        if (result.resultCode == Activity.RESULT_OK && result.data != null && d != null) {
            ContextCompat.startForegroundService(
                this,
                Intent(this, AudioCaptureService::class.java)
                    .setAction(AudioCaptureService.ACTION_START_PROJECTION)
                    .putExtra(AudioCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    .putExtra(AudioCaptureService.EXTRA_RESULT_DATA, result.data)
                    .putExtra(AudioCaptureService.EXTRA_DEVICE, d)
            )
        } else {
            Toast.makeText(this, R.string.capture_permission_denied, Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results[Manifest.permission.RECORD_AUDIO] == true) proceed()
        else {
            Toast.makeText(this, R.string.permission_required, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d: AirPlayDevice? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_DEVICE, AirPlayDevice::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DEVICE)
        if (d == null) {
            finish()
            return
        }
        device = d
        if (SinkFactory.transportFor(d) == Transport.UNSUPPORTED || AirPlay2Capabilities.blockedByFairPlay(d.features)) {
            Toast.makeText(this, getString(R.string.fairplay_required_message, d.displayName), Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (savedInstanceState == null) checkPermissions()
    }

    private fun checkPermissions() {
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) proceed() else permissionLauncher.launch(needed.toTypedArray())
    }

    private fun proceed() {
        val d = device ?: return finish()
        val force = intent.getBooleanExtra(EXTRA_FORCE_PROJECTION, false)
        if (!force && StreamController.canConnectSilently(this)) {
            ContextCompat.startForegroundService(
                this,
                Intent(this, AudioCaptureService::class.java)
                    .setAction(AudioCaptureService.ACTION_CONNECT)
                    .putExtra(AudioCaptureService.EXTRA_DEVICE, d)
            )
            finish()
            return
        }
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        // Android 14+ defaults the dialog to "a single app", which confuses users (GitHub #2/#5)
        // and isn't what audio capture needs: ask for the whole display directly.
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            manager.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay())
        } else manager.createScreenCaptureIntent()
        projectionLauncher.launch(intent)
    }
}
