package com.airplay.streamer

import android.Manifest
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.airplay.streamer.databinding.ActivityMainBinding
import com.airplay.streamer.discovery.AirPlayDevice
import com.airplay.streamer.engine.SessionState
import com.airplay.streamer.engine.SpeakerStatus
import com.airplay.streamer.service.AudioCaptureService
import com.airplay.streamer.service.MediaInfoTracker
import com.airplay.streamer.service.StreamController
import com.airplay.streamer.shizuku.ShizukuManager
import com.airplay.streamer.ui.MainViewModel
import com.airplay.streamer.ui.SpeakerAdapter
import com.airplay.streamer.ui.SpeakerRow
import com.airplay.streamer.util.LogServer
import com.airplay.streamer.util.Prefs
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var adapter: SpeakerAdapter
    private lateinit var prefs: Prefs
    private var autoConnectDone = false
    private var groupDragging = false
    private var dismissedSetup: String? = null
    private var lastSession = SessionState()
    private var lastMedia = MediaInfoTracker.MediaInfo()
    private val endDrag = Runnable { groupDragging = false }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        updateSetupCard()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)
        if (prefs.debugLogServer || BuildConfig.DEBUG) LogServer.start()
        ShizukuManager.init(this)

        setupInsets()
        setupList()
        setupControls()
        observe()
    }

    override fun onResume() {
        super.onResume()
        ShizukuManager.refresh()
        viewModel.refreshTracker()
        viewModel.onForeground()
        checkForManualDevice()
        updateSetupCard()
        window.apply {
            if (prefs.keepScreenOn && AudioCaptureService.state.value.capturing) addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun setupInsets() {
        // Side insets (landscape navigation bar, cutouts); children still get the rest.
        ViewCompat.setOnApplyWindowInsetsListener(binding.mainContent) { v, insets ->
            val side = insets.getInsets(WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(left = side.left, right = side.right)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.header) { v, insets ->
            v.updatePadding(top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top + dp(16))
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.controlsContent) { v, insets ->
            v.updatePadding(bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom + dp(12))
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.speakersRecyclerView) { v, insets ->
            val controlsVisible = binding.controlsCard.visibility == View.VISIBLE
            v.updatePadding(bottom = if (controlsVisible) dp(16) else insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom + dp(16))
            insets
        }
    }

    private fun setupList() {
        adapter = SpeakerAdapter(
            onClick = ::onSpeakerClick,
            onLongClick = ::showSpeakerDetails,
            onVolume = { row, v -> StreamController.setSpeakerVolume(row.device.identity, v) },
        )
        binding.speakersRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.speakersRecyclerView.adapter = adapter
        binding.speakersRecyclerView.itemAnimator?.changeDuration = 120
    }

    private fun onSpeakerClick(row: SpeakerRow) {
        val device = row.device
        if (!row.supported) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.fairplay_required_title)
                .setMessage(getString(R.string.fairplay_required_message, device.displayName))
                .setPositiveButton(R.string.ok, null)
                .show()
            return
        }
        when (row.state?.status) {
            SpeakerStatus.NEEDS_PASSWORD -> askPassword(device)
            SpeakerStatus.NEEDS_PIN -> askPin(device)
            SpeakerStatus.FAILED, SpeakerStatus.NEEDS_ACCEPT -> {
                StreamController.disconnect(this, device.identity)
                StreamController.connect(this, device)
            }
            null -> StreamController.connect(this, device)
            else -> StreamController.disconnect(this, device.identity)
        }
    }

    private fun showSleepTimer() {
        val options = intArrayOf(15, 30, 45, 60, 90, 0)
        val labels = options.map { if (it == 0) getString(R.string.sleep_timer_off) else "$it min" }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sleep_timer)
            .setItems(labels) { _, i -> StreamController.setSleepTimer(this, options[i]) }
            .show()
    }

    private var pinDialogFor: String? = null

    /** AirPlay 2 pairing code (shown on an Apple TV) or the receiver's AirPlay password. */
    private fun askPin(device: AirPlayDevice) {
        if (pinDialogFor == device.identity) return
        pinDialogFor = device.identity
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            hint = getString(R.string.pin_hint)
        }
        val container = FrameLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(input) }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.pin_title, device.displayName.lowercase()))
            .setMessage(R.string.pin_message)
            .setView(container)
            .setPositiveButton(R.string.ok) { _, _ -> StreamController.submitPin(device.identity, input.text.toString()) }
            .setNegativeButton(R.string.cancel) { _, _ -> StreamController.submitPin(device.identity, null) }
            .setOnDismissListener { pinDialogFor = null }
            .show()
    }

    private fun askPassword(device: AirPlayDevice) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.password_hint)
            setText(prefs.password(device.identity) ?: "")
        }
        val container = FrameLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(input) }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.password_title, device.displayName.lowercase()))
            .setView(container)
            .setPositiveButton(R.string.ok) { _, _ ->
                prefs.setPassword(device.identity, input.text.toString().ifEmpty { null })
                StreamController.disconnect(this, device.identity)
                StreamController.connect(this, device)
            }
            .setNegativeButton(R.string.cancel) { _, _ -> StreamController.disconnect(this, device.identity) }
            .show()
    }

    private fun showSpeakerDetails(row: SpeakerRow) {
        val d = row.device
        val info = buildString {
            appendLine("address: ${d.host}")
            appendLine("airplay port: ${d.port}${d.raopPort?.let { " · raop port: $it" } ?: ""}")
            appendLine("protocol: ${row.transport.name.lowercase()}")
            SpeakerAdapter.prettyModel(d.features["am"] ?: d.features["model"])?.let { appendLine("model: $it") }
            (d.features["am"] ?: d.features["model"])?.let { appendLine("model id: $it") }
            d.features["srcvers"]?.let { appendLine("source version: $it") }
            d.features["et"]?.let { appendLine("encryption: et=$it") }
            d.features["cn"]?.let { appendLine("codecs: cn=$it") }
            row.state?.let { appendLine(); appendLine("status: ${it.status.name.lowercase()}"); if (it.stats.isNotBlank()) appendLine(it.stats); it.message?.let { m -> appendLine(m) } }
        }
        val offsetLabel = TextView(this).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
        }
        fun label(ms: Int) = getString(R.string.audio_delay_label, if (ms > 0) "+$ms" else "$ms")
        val slider = com.google.android.material.slider.Slider(this).apply {
            valueFrom = -500f; valueTo = 500f; stepSize = 10f
            value = prefs.speakerOffsetMs(d.identity).toFloat().coerceIn(-500f, 500f)
            isTickVisible = false
            addOnChangeListener { _, v, fromUser ->
                offsetLabel.text = label(v.toInt())
                if (fromUser) StreamController.setSpeakerOffset(this@MainActivity, d.identity, v.toInt())
            }
        }
        offsetLabel.text = label(slider.value.toInt())
        val panel = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(24), dp(4), dp(24), 0)
            addView(TextView(this@MainActivity).apply { text = info.trim() })
            addView(offsetLabel, android.widget.LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
            addView(slider)
        }
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(d.displayName.lowercase())
            .setView(android.widget.ScrollView(this).apply { addView(panel) })
            .setPositiveButton(R.string.ok, null)
        if (d.identity.startsWith("manual_")) {
            builder.setNeutralButton(R.string.remove_speaker) { _, _ -> viewModel.removeManualSpeaker(d.identity) }
        } else if (prefs.password(d.identity) != null) {
            builder.setNeutralButton(R.string.forget_password) { _, _ -> prefs.setPassword(d.identity, null) }
        } else if (row.transport == com.airplay.streamer.engine.Transport.AIRPLAY2) {
            if (prefs.hapStore.load(d.identity) != null) {
                builder.setNeutralButton(R.string.forget_pairing) { _, _ -> StreamController.forgetPairing(this, d.identity) }
            } else {
                builder.setNeutralButton(R.string.pair_with_code) { _, _ -> StreamController.pairWithCode(this, d) }
            }
        }
        builder.show()
    }

    private fun setupControls() {
        binding.settingsButton.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.refreshButton.setOnClickListener {
            viewModel.refreshDiscovery()
            binding.refreshButton.animate().rotationBy(360f).setDuration(500).start()
        }
        binding.stopButton.setOnClickListener { StreamController.stopAll(this) }
        binding.timerButton.setOnClickListener { showSleepTimer() }
        binding.playPauseButton.setOnClickListener { viewModel.togglePlayback() }
        binding.nextButton.setOnClickListener { viewModel.next() }
        binding.prevButton.setOnClickListener { viewModel.previous() }
        binding.groupVolumeSlider.addOnChangeListener { value, fromUser ->
            if (fromUser) {
                groupDragging = true
                StreamController.setGroupVolume(value)
                binding.groupVolumeSlider.removeCallbacks(endDrag)
                binding.groupVolumeSlider.postDelayed(endDrag, 600)
            }
        }
        binding.setupDismiss.setOnClickListener {
            dismissedSetup = binding.setupTitle.text.toString()
            if (dismissedSetup == getString(R.string.setup_tip_title)) {
                prefs.raw.edit().putBoolean("shizuku_tip_dismissed", true).apply()
            }
            binding.setupCard.visibility = View.GONE
        }
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    val oldFirst = adapter.currentList.firstOrNull()?.device?.identity
                    adapter.submitList(state.rows) {
                        // Active speakers move to the top; keep them in view.
                        if (state.rows.firstOrNull()?.device?.identity != oldFirst) binding.speakersRecyclerView.scrollToPosition(0)
                    }
                    binding.emptyView.visibility = if (state.searching) View.VISIBLE else View.GONE
                    renderSession(state.session)
                    val supported = state.rows.count { it.supported }
                    binding.statusText.text = when {
                        state.session.isStreaming -> getString(R.string.status_playing_on, viewModel.playingNames())
                        supported == 0 -> getString(R.string.status_searching)
                        supported == 1 -> getString(R.string.status_found_one)
                        else -> getString(R.string.status_found, supported)
                    }
                    maybeAutoConnect(state.rows)
                    renderGroups(state)
                    state.rows.firstOrNull { it.state?.status == SpeakerStatus.NEEDS_PIN }?.let { askPin(it.device) }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.mediaInfo.collect(::renderNowPlaying)
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AudioCaptureService.sleepAt.collect { renderBottomPanel() }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(ShizukuManager.status, ShizukuManager.privileged) { a, b -> a to b }.collect { updateSetupCard() }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    if (binding.controlsCard.visibility == View.VISIBLE) {
                        binding.levelMeter.setProgressCompat((AudioCaptureService.level * 100).toInt(), true)
                        val progress = viewModel.trackProgress()
                        binding.trackProgress.visibility = if (progress == null) View.INVISIBLE else View.VISIBLE
                        if (progress != null) binding.trackProgress.setProgressCompat((progress * 1000).toInt(), false)
                    }
                    delay(120)
                }
            }
        }
    }

    private fun renderSession(session: SessionState) {
        lastSession = session
        renderBottomPanel()
    }

    /**
     * The bottom panel doubles as a mini player: it shows the current track + cover art
     * whenever something plays (so you can see what you're about to send), and adds the
     * group volume, level meter and stop button while streaming.
     */
    private fun renderBottomPanel() {
        val session = lastSession
        val streaming = session.capturing && session.speakers.isNotEmpty()
        val mediaVisible = prefs.showNowPlaying && MediaInfoTracker.hasNotificationAccess(this) &&
            (lastMedia.hasContent || streaming)
        val show = streaming || mediaVisible
        if ((binding.controlsCard.visibility == View.VISIBLE) != show) {
            androidx.transition.TransitionManager.beginDelayedTransition(
                binding.mainContent,
                androidx.transition.ChangeBounds().setDuration(350)
                    .setInterpolator(android.view.animation.OvershootInterpolator(1.2f))
            )
            binding.controlsCard.visibility = if (show) View.VISIBLE else View.GONE
            ViewCompat.requestApplyInsets(binding.speakersRecyclerView)
        }
        binding.nowPlayingLayout.visibility = if (mediaVisible) View.VISIBLE else View.GONE
        binding.streamingRow.visibility = if (streaming) View.VISIBLE else View.GONE
        binding.groupVolumeLayout.visibility = if (streaming) View.VISIBLE else View.GONE
        if (!streaming) return
        val playing = session.speakers.filter { it.status == SpeakerStatus.PLAYING }
        val sleepAt = AudioCaptureService.sleepAt.value
        binding.streamingStatusText.text = when {
            playing.isEmpty() -> getString(R.string.connecting)
            sleepAt > 0 -> getString(R.string.status_playing_on, playing.joinToString(" + ") { it.name.lowercase() }) + " · " +
                getString(R.string.sleep_timer_set, java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(sleepAt)))
            else -> getString(R.string.status_playing_on, playing.joinToString(" + ") { it.name.lowercase() })
        }
        binding.timerButton.alpha = if (sleepAt > 0) 1f else 0.6f
        if (!groupDragging) binding.groupVolumeSlider.setValue((session.speakers.maxOfOrNull { it.volume } ?: 0f).coerceIn(0f, 1f))
        if (prefs.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun renderNowPlaying(info: MediaInfoTracker.MediaInfo) {
        lastMedia = info
        renderBottomPanel()
        if (info.hasContent) {
            binding.nowPlayingTitle.text = (info.title ?: "").lowercase()
            binding.nowPlayingArtist.text = (info.artist ?: getString(R.string.unknown_artist)).lowercase()
        } else {
            binding.nowPlayingTitle.text = getString(R.string.waiting_for_music)
            binding.nowPlayingArtist.text = getString(R.string.play_something)
        }
        binding.playPauseButton.setImageResource(if (info.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        if (info.albumArt != null) {
            binding.albumArt.imageTintList = null
            binding.albumArt.setPadding(0, 0, 0, 0)
            binding.albumArt.setImageBitmap(info.albumArt)
        } else {
            binding.albumArt.setImageResource(R.drawable.ic_music_note)
            binding.albumArt.setPadding(dp(14), dp(14), dp(14), dp(14))
            binding.albumArt.imageTintList = ColorStateList.valueOf(
                MaterialColors.getColor(binding.albumArt, com.google.android.material.R.attr.colorOnSecondaryContainer)
            )
        }
    }

    // ------------------------------------------------------------------ setup card

    private enum class Setup { PERMISSIONS, SHIZUKU_PERMISSION, SHIZUKU_SETUP, NOTIFICATION_ACCESS, SHIZUKU_TIP }

    private fun pendingSetup(): Setup? {
        val shizuku = ShizukuManager.status.value
        val needsProjectionApproval = !ShizukuManager.projectionPreApproved(this)
        val hasListener = MediaInfoTracker.hasNotificationAccess(this)
        return when {
            shizuku == ShizukuManager.Status.NO_PERMISSION -> Setup.SHIZUKU_PERMISSION
            shizuku == ShizukuManager.Status.READY && (needsProjectionApproval || !hasListener || !StreamController.hasRuntimePermissions(this)) -> Setup.SHIZUKU_SETUP
            !StreamController.hasRuntimePermissions(this) -> Setup.PERMISSIONS
            !hasListener && prefs.showNowPlaying -> Setup.NOTIFICATION_ACCESS
            shizuku == ShizukuManager.Status.NOT_INSTALLED && needsProjectionApproval &&
                !prefs.raw.getBoolean("shizuku_tip_dismissed", false) -> Setup.SHIZUKU_TIP
            else -> null
        }
    }

    private fun updateSetupCard() {
        val setup = pendingSetup()
        val (title, text, button) = when (setup) {
            Setup.PERMISSIONS -> Triple(R.string.setup_permissions_title, R.string.setup_permissions_text, R.string.setup_permissions_button)
            Setup.SHIZUKU_PERMISSION -> Triple(R.string.setup_shizuku_perm_title, R.string.setup_shizuku_perm_text, R.string.setup_shizuku_perm_button)
            Setup.SHIZUKU_SETUP -> Triple(R.string.setup_shizuku_title, R.string.setup_shizuku_text, R.string.setup_shizuku_button)
            Setup.NOTIFICATION_ACCESS -> Triple(R.string.setup_listener_title, R.string.setup_listener_text, R.string.setup_listener_button)
            Setup.SHIZUKU_TIP -> Triple(R.string.setup_tip_title, R.string.setup_tip_text, R.string.setup_tip_button)
            null -> {
                binding.setupCard.visibility = View.GONE
                return
            }
        }
        if (dismissedSetup == getString(title)) return
        binding.setupTitle.setText(title)
        binding.setupText.setText(text)
        binding.setupButton.setText(button)
        binding.setupButton.isEnabled = true
        binding.setupCard.visibility = View.VISIBLE
        binding.setupButton.setOnClickListener {
            when (setup) {
                Setup.PERMISSIONS -> permissionLauncher.launch(
                    buildList {
                        add(Manifest.permission.RECORD_AUDIO)
                        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                    }.toTypedArray()
                )
                Setup.SHIZUKU_PERMISSION -> ShizukuManager.requestPermission()
                Setup.SHIZUKU_SETUP -> {
                    binding.setupButton.isEnabled = false
                    lifecycleScope.launch {
                        val report = ShizukuManager.setupPermissions()
                        viewModel.refreshTracker()
                        MaterialAlertDialogBuilder(this@MainActivity)
                            .setTitle(R.string.setup_done)
                            .setMessage(report)
                            .setPositiveButton(R.string.ok, null)
                            .show()
                        updateSetupCard()
                    }
                }
                Setup.NOTIFICATION_ACCESS -> startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                Setup.SHIZUKU_TIP -> startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://shizuku.rikka.app/")))
            }
        }
    }

    // ------------------------------------------------------------------ misc

    private var renderedGroups: List<Pair<Set<String>, String>>? = null

    /** One-tap chips for recently used multi-speaker groups whose speakers are all present. */
    private fun renderGroups(state: com.airplay.streamer.ui.MainUiState) {
        val present = state.rows.filter { it.supported }.map { it.device.identity }.toSet()
        val groups = if (state.session.capturing) emptyList()
        else prefs.recentGroups.filter { (ids, _) -> present.containsAll(ids) }
        if (groups == renderedGroups) return
        renderedGroups = groups
        binding.groupChips.removeAllViews()
        for ((ids, label) in groups) {
            binding.groupChips.addView(com.google.android.material.chip.Chip(this).apply {
                text = label
                setChipIconResource(R.drawable.ic_speaker)
                isChipIconVisible = true
                setOnClickListener {
                    state.rows.filter { it.device.identity in ids }.forEach { StreamController.connect(this@MainActivity, it.device) }
                }
            })
        }
        binding.groupsScroll.visibility = if (groups.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun maybeAutoConnect(rows: List<SpeakerRow>) {
        if (autoConnectDone || !prefs.autoConnect || AudioCaptureService.state.value.capturing) return
        val wanted = prefs.lastSpeakers
        if (wanted.isEmpty()) {
            autoConnectDone = true
            return
        }
        val found = rows.filter { it.device.identity in wanted && it.supported }
        if (found.size == wanted.size || (found.isNotEmpty() && rows.size > 3)) {
            autoConnectDone = true
            found.forEach { StreamController.connect(this, it.device) }
        }
    }

    private fun checkForManualDevice() {
        val raw = prefs.raw
        if (!raw.getBoolean("manual_device_pending", false)) return
        val host = raw.getString("manual_device_host", null) ?: return
        val port = raw.getInt("manual_device_port", 5000)
        viewModel.addManualSpeaker("$host:$port")
        raw.edit().putBoolean("manual_device_pending", false).apply()
        Toast.makeText(this, "added $host:$port", Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
