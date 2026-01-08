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
    private var hasAttemptedAutoConnect = false

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
        // Refresh UI to update media player visibility if permission changed
        val isStreaming = AudioCaptureService.instance?.isCurrentlyStreaming() == true
        updateStreamingUI(isStreaming)
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
            // Check if currently streaming
            val isStreaming = AudioCaptureService.instance?.isCurrentlyStreaming() == true
            val currentDevice = viewModel.uiState.value.selectedDevice
            
            if (isStreaming && currentDevice != null) {
                // If tapping the same speaker that's playing, do nothing
                if (currentDevice.host == device.host && currentDevice.port == device.port) {
                    return@SpeakerAdapter
                }
                // Show confirmation dialog for switching to different speaker
                showSwitchSpeakerDialog(device)
            } else {
                viewModel.selectDevice(device)
            }
        }

        binding.speakersRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = speakerAdapter
            // Enable default animations for entry/exit effects
            itemAnimator = androidx.recyclerview.widget.DefaultItemAnimator()
        }
    }
    
    private fun showSwitchSpeakerDialog(newDevice: com.airplay.streamer.discovery.AirPlayDevice) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Switch Speaker?")
            .setMessage("This will stop streaming to the current speaker and switch to ${newDevice.displayName}.")
            .setPositiveButton("Switch") { _, _ ->
                // Stop current stream and switch
                stopStreaming()
                viewModel.selectDevice(newDevice)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupControls() {
        // Play/Pause button
        binding.playPauseButton.setOnClickListener {
            viewModel.togglePlayback()
        }

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

        binding.volumeSlider.addOnChangeListener { value, fromUser ->
            if (fromUser) {
                // WavySlider already uses 0.0-1.0 range
                AudioCaptureService.instance?.setVolume(value)
            }
        }
        
        // Set initial volume to 80%
        binding.volumeSlider.setValue(0.8f)
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
                    
                    // Auto-Connect Logic
                    if (!hasAttemptedAutoConnect && !state.isStreaming && state.devices.isNotEmpty()) {
                        val prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)
                        if (prefs.getBoolean("auto_connect", false)) {
                            val lastHost = prefs.getString("last_device_host", null)
                            val lastPort = prefs.getInt("last_device_port", 0)
                            
                            if (lastHost != null && lastPort != 0) {
                                val match = state.devices.find { it.host == lastHost && it.port == lastPort }
                                if (match != null) {
                                    hasAttemptedAutoConnect = true
                                    Toast.makeText(this@MainActivity, "Auto-connecting to ${match.displayName}...", Toast.LENGTH_SHORT).show()
                                    viewModel.selectDevice(match)
                                    checkPermissionsAndStart(match)
                                }
                            }
                        }
                        // Only try once per app session (or until devices list is populated enough)
                        // If we have devices but no match, we still mark attempted so we don't spam
                         if (state.devices.isNotEmpty()) hasAttemptedAutoConnect = true
                    }
                }
            }
        }

        // Observe media info for Now Playing updates
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.mediaInfo.collect { info ->
                    if (info.hasContent) {
                        binding.nowPlayingTitle.text = (info.title ?: "unknown title").lowercase()
                        binding.nowPlayingArtist.text = (info.artist ?: "unknown artist").lowercase()
                        
                        // Update Play/Pause Icon
                        val iconRes = if (info.isPlaying) R.drawable.ic_stop else R.drawable.ic_play
                        binding.playPauseButton.setImageResource(iconRes)
                        
                        if (info.albumArt != null) {
                            binding.albumArt.imageTintList = null // Clear tint for actual image
                            binding.albumArt.setImageBitmap(info.albumArt)
                        } else {
                            // Restore tint for placeholder icon
                            binding.albumArt.imageTintList = android.content.res.ColorStateList.valueOf(
                                com.google.android.material.color.MaterialColors.getColor(binding.albumArt, com.google.android.material.R.attr.colorOnSurfaceVariant)
                            )
                            binding.albumArt.setImageResource(R.drawable.ic_music_note)
                        }
                    } else {
                        // Reset to check
                        binding.nowPlayingTitle.text = "waiting for music..."
                        binding.nowPlayingArtist.text = "play something on spotify/music app"
                        binding.playPauseButton.setImageResource(R.drawable.ic_play)
                    }
                }
            }
        }
    }

    private fun updateConnectedSpeakerUI(device: AirPlayDevice?) {
        if (device != null) {
            binding.connectedSpeakerLayout.visibility = View.VISIBLE
            binding.connectedSpeakerName.text = device.displayName.lowercase()
            // Hide protocol version chip for v1-only mode
            binding.protocolChip.visibility = View.GONE
        } else {
            binding.connectedSpeakerLayout.visibility = View.GONE
        }
    }

    private fun updateStreamingUI(isStreaming: Boolean) {
        if (isStreaming) {
            binding.streamButton.text = getString(R.string.stop_streaming)
            binding.streamButton.setIconResource(R.drawable.ic_stop)
            
            // Smooth transition for layout changes - animate the parent so all children move together
            val transition = androidx.transition.ChangeBounds().apply {
                duration = 450
                interpolator = android.view.animation.OvershootInterpolator(2.0f)
            }
            androidx.transition.TransitionManager.beginDelayedTransition(
                binding.mainContent,
                transition
            )
            
            // Show volume layout
            binding.volumeLayout.visibility = View.VISIBLE

            // Handle Keep Screen On
            val keepScreenOn = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)
                .getBoolean("keep_screen_on", false)
            
            if (keepScreenOn) {
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }

            // Show media player only if permission is granted AND enabled in settings
            val showNowPlaying = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)
                .getBoolean("show_now_playing", true)

            if (showNowPlaying && hasMetadataPermission()) {
                binding.nowPlayingLayout.visibility = View.VISIBLE
            } else {
                binding.nowPlayingLayout.visibility = View.GONE
            }
            
            binding.streamingStatusText.visibility = View.VISIBLE
            binding.streamingStatusText.text = "streaming..."
            
            // Pulse animation on the connection indicator
            binding.connectionIndicator.animate()
                .scaleX(1.2f).scaleY(1.2f)
                .setDuration(500)
                .withEndAction {
                    binding.connectionIndicator.animate()
                        .scaleX(1f).scaleY(1f)
                        .setDuration(500)
                        .start()
                }
                .start()
        } else {
            binding.streamButton.text = getString(R.string.start_streaming)
            binding.streamButton.setIconResource(R.drawable.ic_play)
            
            // Smooth transition for layout changes - animate the parent so all children move together
            val transition = androidx.transition.ChangeBounds().apply {
                duration = 350
                interpolator = android.view.animation.OvershootInterpolator(1.5f)
            }
            androidx.transition.TransitionManager.beginDelayedTransition(
                binding.mainContent,
                transition
            )
            
            // Hide volume layout
            binding.volumeLayout.visibility = View.GONE

            // Hide media player
            binding.nowPlayingLayout.visibility = View.GONE
            
            binding.streamingStatusText.visibility = View.GONE
            binding.connectionIndicator.animate().cancel()
            binding.connectionIndicator.scaleX = 1f
            binding.connectionIndicator.scaleY = 1f
            
            // Allow screen to turn off
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
        
        // Save Last Device for Auto-Connect
        getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE).edit()
            .putString("last_device_host", device.host)
            .putInt("last_device_port", device.port)
            .apply()

        startForegroundService(serviceIntent)
        
        // Update UI immediately to show streaming state
        viewModel.setStreamingState(true)
        updateStreamingUI(true)
        
        // Register callback after service starts (with small delay to ensure service is created)
        binding.root.postDelayed({
            AudioCaptureService.instance?.onStateChanged = { isStreaming ->
                runOnUiThread {
                    viewModel.setStreamingState(isStreaming)
                    updateStreamingUI(isStreaming)
                }
            }
        }, 100)
    }

    private fun stopStreaming() {
        val serviceIntent = Intent(this, AudioCaptureService::class.java).apply {
            action = AudioCaptureService.ACTION_STOP
        }
        startService(serviceIntent)
    }

    private fun hasMetadataPermission(): Boolean {
        val componentName = android.content.ComponentName(this, com.airplay.streamer.service.NotificationListener::class.java)
        val flat = android.provider.Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(componentName.flattenToString())
    }

    override fun onDestroy() {
        super.onDestroy()
        AudioCaptureService.instance?.onStateChanged = null
    }
}
