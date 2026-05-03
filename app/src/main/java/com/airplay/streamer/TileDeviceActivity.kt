package com.airplay.streamer

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.airplay.streamer.databinding.ActivityTileDeviceBinding
import com.airplay.streamer.discovery.AirPlayDevice
import com.airplay.streamer.raop.RaopCapabilities
import com.airplay.streamer.service.AudioCaptureService
import com.airplay.streamer.ui.MainViewModel
import com.airplay.streamer.ui.SpeakerAdapter
import com.google.android.material.color.DynamicColors
import kotlinx.coroutines.launch

class TileDeviceActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTileDeviceBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var speakerAdapter: SpeakerAdapter

    private var pendingDevice: AirPlayDevice? = null

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            pendingDevice?.let { device ->
                startStreamingService(result.resultCode, result.data!!, device)
            }
        } else {
            Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            pendingDevice?.let { requestMediaProjection(it) }
        } else {
            Toast.makeText(this, "Permissions required", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        
        binding = ActivityTileDeviceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Close when clicking outside the card (on the dim area)
        binding.root.setOnClickListener { finish() }
        
        setupRecyclerView()
        observeState()
    }

    private fun setupRecyclerView() {
        speakerAdapter = SpeakerAdapter { device ->
            checkPermissionsAndStart(device)
        }

        binding.speakersRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@TileDeviceActivity)
            adapter = speakerAdapter
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    val items = state.devices.map { device ->
                        SpeakerAdapter.SpeakerItem(device = device, isConnected = false)
                    }
                    speakerAdapter.submitList(items)
                    binding.emptyView.visibility = if (state.devices.isEmpty()) View.VISIBLE else View.GONE
                    
                    if (state.devices.isNotEmpty()) {
                        binding.loadingIndicator.visibility = View.GONE
                    }
                }
            }
        }
        
        lifecycleScope.launch {
            kotlinx.coroutines.delay(5000)
            binding.loadingIndicator.visibility = View.GONE
        }
    }

    private fun checkPermissionsAndStart(device: AirPlayDevice) {
        if (RaopCapabilities.requiresUnsupportedFairPlay(device.features)) {
            Toast.makeText(
                this,
                getString(R.string.fairplay_required_message, device.displayName),
                Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }

        val permissions = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS
        )

        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isEmpty()) {
            requestMediaProjection(device)
        } else {
            pendingDevice = device
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    private fun requestMediaProjection(device: AirPlayDevice) {
        pendingDevice = device
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun startStreamingService(resultCode: Int, data: Intent, device: AirPlayDevice) {
        val serviceIntent = Intent(this, AudioCaptureService::class.java).apply {
            action = AudioCaptureService.ACTION_START
            putExtra(AudioCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(AudioCaptureService.EXTRA_RESULT_DATA, data)
            putExtra(AudioCaptureService.EXTRA_HOST, device.host)
            putExtra(AudioCaptureService.EXTRA_PORT, device.raopPort ?: device.port)
            putExtra(AudioCaptureService.EXTRA_DEVICE_NAME, device.displayName)
            putExtra(AudioCaptureService.EXTRA_DEVICE_FEATURES,
                device.features.entries.joinToString(";") { "${it.key}=${it.value}" })
        }
        startForegroundService(serviceIntent)
        finish()
    }
}
