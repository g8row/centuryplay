package com.airplay.streamer

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.airplay.streamer.databinding.ActivityMainBinding
import com.airplay.streamer.discovery.AirPlayDevice
import com.airplay.streamer.service.AudioCaptureService
import com.airplay.streamer.ui.MainViewModel
import com.airplay.streamer.ui.SpeakerAdapter
import com.airplay.streamer.util.LogServer
import com.google.android.material.color.DynamicColors
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
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
        pendingDevice = null
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            pendingDevice?.let { requestMediaProjection(it) }
        } else {
            Toast.makeText(this, "Permissions required for audio capture", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply Material You dynamic colors (Android 12+)
        DynamicColors.applyToActivityIfAvailable(this)
        
        super.onCreate(savedInstanceState)
        
        // Enable edge-to-edge display
        enableEdgeToEdge()
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Apply window insets for status bar padding
        setupWindowInsets()

        // Start log server for web-based debugging (http://<device-ip>:8080)
        LogServer.start()
        LogServer.log("MainActivity started")

        setupRecyclerView()
        setupControls()
        observeState()

        // Register for service state changes
        AudioCaptureService.instance?.onStateChanged = { isStreaming ->
            runOnUiThread {
                viewModel.setStreamingState(isStreaming)
                updateStreamingUI(isStreaming)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkForManualDevice()
    }

    private fun checkForManualDevice() {
        val prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)
        val hasPending = prefs.getBoolean("manual_device_pending", false)
        
        if (hasPending) {
            val host = prefs.getString("manual_device_host", null)
            val port = prefs.getInt("manual_device_port", 5000)
            
            if (host != null) {
                val device = AirPlayDevice(
                    name = "Manual: $host",
                    host = host,
                    port = port,
                    deviceId = "manual_$host",
                    protocolVersion = if (port == 7000) 2 else 1
                )
                viewModel.addManualDevice(device)
                viewModel.selectDevice(device)
                
                // Clear the pending flag
                prefs.edit().putBoolean("manual_device_pending", false).apply()
                
                Toast.makeText(this, "Manual device added: $host:$port", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupWindowInsets() {
        // Apply insets to header for status bar
        ViewCompat.setOnApplyWindowInsetsListener(binding.header) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
            view.updatePadding(top = insets.top + 24) // 24dp original + status bar
            windowInsets
        }
        
        // Apply insets to bottom controls for navigation bar
        ViewCompat.setOnApplyWindowInsetsListener(binding.controlsCard) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.updatePadding(bottom = insets.bottom)
            windowInsets
        }
    }

    private fun setupRecyclerView() {
        speakerAdapter = SpeakerAdapter { device ->
            viewModel.selectDevice(device)
        }

        binding.speakersRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = speakerAdapter
        }
    }

    private fun setupControls() {
        binding.streamButton.setOnClickListener {
            val device = viewModel.uiState.value.selectedDevice
            if (device == null) {
                Toast.makeText(this, "Select a speaker first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (AudioCaptureService.instance?.isCurrentlyStreaming() == true) {
                stopStreaming()
            } else {
                checkPermissionsAndStart(device)
            }
        }
        
        // Settings button - launches SettingsActivity
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Refresh button - restarts discovery
        binding.refreshButton.setOnClickListener {
            viewModel.refreshDiscovery()
            Toast.makeText(this, "Refreshing...", Toast.LENGTH_SHORT).show()
        }

        binding.volumeSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                // Volume changes will be implemented when streaming is active
            }
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    // Update speaker list
                    val items = state.devices.map { device ->
                        SpeakerAdapter.SpeakerItem(
                            device = device,
                            isConnected = state.selectedDevice?.host == device.host &&
                                         state.selectedDevice?.port == device.port
                        )
                    }
                    speakerAdapter.submitList(items)

                    // Show/hide empty view
                    binding.emptyView.visibility = if (state.devices.isEmpty()) View.VISIBLE else View.GONE
                    binding.speakersRecyclerView.visibility = if (state.devices.isEmpty()) View.GONE else View.VISIBLE

                    // Update status
                    binding.statusText.text = state.statusMessage

                    // Update stream button
                    binding.streamButton.isEnabled = state.selectedDevice != null
                    updateConnectedSpeakerUI(state.selectedDevice)

                    // Update streaming state
                    updateStreamingUI(state.isStreaming)
                }
            }
        }
    }

    private fun updateConnectedSpeakerUI(device: AirPlayDevice?) {
        if (device != null) {
            binding.connectedSpeakerLayout.visibility = View.VISIBLE
            binding.connectedSpeakerName.text = device.displayName
            // Show protocol version chip
            binding.protocolChip.text = if (device.port == 7000) "v2" else "v1"
        } else {
            binding.connectedSpeakerLayout.visibility = View.GONE
        }
    }

    private fun updateStreamingUI(isStreaming: Boolean) {
        if (isStreaming) {
            binding.streamButton.text = getString(R.string.stop_streaming)
            binding.streamButton.setIconResource(R.drawable.ic_stop)
            binding.volumeLayout.visibility = View.VISIBLE
        } else {
            binding.streamButton.text = getString(R.string.start_streaming)
            binding.streamButton.setIconResource(R.drawable.ic_play)
            binding.volumeLayout.visibility = View.GONE
        }
    }

    private fun checkPermissionsAndStart(device: AirPlayDevice) {
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
            putExtra(AudioCaptureService.EXTRA_PORT, device.port)
            putExtra(AudioCaptureService.EXTRA_DEVICE_NAME, device.displayName)
        }
        startForegroundService(serviceIntent)
    }

    private fun stopStreaming() {
        val serviceIntent = Intent(this, AudioCaptureService::class.java).apply {
            action = AudioCaptureService.ACTION_STOP
        }
        startService(serviceIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        AudioCaptureService.instance?.onStateChanged = null
    }
}
