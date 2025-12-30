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

        binding.volumeSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                // Volume changes will be implemented when streaming is active
            }
        }
        
        // FAB for manual connection
        binding.fabManualConnect.setOnClickListener {
            showManualConnectDialog()
        }
    }
    
    private fun showManualConnectDialog() {
        val editText = android.widget.EditText(this).apply {
            hint = getString(R.string.manual_connect_hint)
            setText("192.168.1.216:5000") // Pre-fill with PC's shairport address
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setPadding(48, 32, 48, 32)
        }
        
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.manual_connect_title)
            .setView(editText)
            .setPositiveButton("Connect") { _, _ ->
                val input = editText.text.toString().trim()
                parseAndConnect(input)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun parseAndConnect(input: String) {
        try {
            val parts = input.split(":")
            if (parts.size != 2) {
                Toast.makeText(this, "Invalid format. Use IP:Port", Toast.LENGTH_SHORT).show()
                return
            }
            val host = parts[0]
            val port = parts[1].toIntOrNull() ?: throw NumberFormatException()
            
            // Create a manual device and select it
            val device = com.airplay.streamer.discovery.AirPlayDevice(
                name = "Manual: $host",
                host = host,
                port = port,
                deviceId = "manual",
                protocolVersion = if (port == 7000) 2 else 1
            )
            viewModel.selectDevice(device)
            Toast.makeText(this, "Selected $host:$port - tap Stream to connect", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Invalid input: ${e.message}", Toast.LENGTH_SHORT).show()
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
