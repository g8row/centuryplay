package com.airplay.streamer

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.airplay.streamer.service.MediaInfoTracker
import com.airplay.streamer.shizuku.ShizukuManager
import com.airplay.streamer.util.LogServer
import com.airplay.streamer.util.Prefs
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    companion object {
        const val PREFS_NAME = Prefs.NAME
        const val KEY_DEBUG_MODE = "debug_mode"
        const val KEY_MANUAL_HOST = "manual_host"
    }

    private lateinit var prefs: Prefs
    private lateinit var container: LinearLayout
    private val refreshers = mutableListOf<() -> Unit>()

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_settings)
        prefs = Prefs(this)
        container = findViewById(R.id.settingsContainer)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.appBar)) { v, insets ->
            v.updatePadding(top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top); insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(container) { v, insets ->
            v.updatePadding(bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom + dp(32)); insets
        }
        build()
    }

    override fun onResume() {
        super.onResume()
        ShizukuManager.refresh()
        refreshers.forEach { it() }
    }

    private fun build() {
        section("streaming")
        slider(
            "latency", "how far behind the phone the speakers play. lower = snappier, higher = fewer dropouts on busy wi-fi. applies to the next stream.",
            0.5f, 4f, 0.25f, prefs.latencyMs / 1000f, { "%.2f s".format(it) }
        ) { prefs.latencyMs = (it * 1000).toInt() }
        switch("lossless compression (alac)", "halves wi-fi usage with no quality loss. turn off only for receivers that misbehave.", !prefs.preferPcm) { prefs.preferPcm = !it }
        switch("prefer airplay 2", "use airplay 2 for speakers that support both. airplay 1 is simpler and works with more receivers.", prefs.preferAirPlay2) { prefs.preferAirPlay2 = it }
        switch("stream game sounds", "off: games keep playing on the phone while music goes to the speakers. applies to the next stream.", prefs.includeGames) { prefs.includeGames = it }
        switch("show track info on speakers", "send song title, artist, artwork and progress to speakers with a display.", prefs.sendMetadata) { prefs.sendMetadata = it }

        section("phone")
        switch("keep phone silent", "mute the phone's own speaker while streaming (the stream itself is unaffected).", prefs.silencePhone) { prefs.silencePhone = it }
        switch("volume buttons control speakers", "hardware volume keys and the system volume panel adjust the airplay speakers while streaming.", prefs.volumeKeysControlSpeakers) { prefs.volumeKeysControlSpeakers = it }
        slider("stop after silence", "stop streaming when nothing has played for this long, to save battery. 0 = never.", 0f, 60f, 5f, prefs.autoStopMinutes.toFloat(),
            { if (it < 1f) "never" else "${it.toInt()} min" }) { prefs.autoStopMinutes = it.toInt() }
        switch("auto-play on my speakers", "when music starts on the phone and your last speakers are on the wi-fi, stream there automatically. needs shizuku (or a held capture permission); skipped when headphones are connected.", prefs.autoPlay) { prefs.autoPlay = it }
        switch("reconnect on launch", "when the app opens, reconnect to the speakers you used last.", prefs.autoConnect) { prefs.autoConnect = it }
        switch("keep screen on while streaming", "only while the app is open.", prefs.keepScreenOn) { prefs.keepScreenOn = it }
        switch("now playing card", "show the current track and playback controls in the app.", prefs.showNowPlaying) { prefs.showNowPlaying = it }

        section("system integration")
        switch("speakers in the system output switcher", "your airplay speakers appear in the media controls' output picker of any app, like bluetooth devices. hidden while headphones are connected.", prefs.systemSwitcher) { prefs.systemSwitcher = it }
        action("shizuku", { shizukuSummary() }) { onShizukuClicked() }
        action("skip the capture prompt without shizuku", {
            if (ShizukuManager.projectionPreApproved(this)) "done — android won't ask again"
            else "one adb command from a computer, once"
        }) {
            val cmd = "adb shell appops set $packageName PROJECT_MEDIA allow"
            MaterialAlertDialogBuilder(this)
                .setTitle("skip the capture prompt")
                .setMessage("connect the phone to a computer with usb debugging on and run:\n\n$cmd\n\nandroid will then allow audio capture without asking (it survives reboots, not reinstalls).")
                .setPositiveButton("copy") { _, _ ->
                    val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("adb", cmd))
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        switch("capture through shizuku", "no screen-capture permission, and the phone speaker is fully bypassed (android 13+).", prefs.useShizukuCapture) { prefs.useShizukuCapture = it }
        action("track info access", {
            if (MediaInfoTracker.hasNotificationAccess(this)) "allowed — speakers can show what's playing" else "not allowed — tap to allow notification access"
        }) { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        action("battery optimisation", {
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) "unrestricted — streams won't be killed in the background"
            else "restricted — tap to allow background streaming"
        }) {
            runCatching {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }.onFailure { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
        action("quick settings tile", { "add the airplay tile: tap to reconnect your speakers, long-press to pick" }) {
            if (Build.VERSION.SDK_INT >= 33) requestTile()
            else Toast.makeText(this, "pull down quick settings → edit (pencil) → drag the airplay tile in", Toast.LENGTH_LONG).show()
        }

        action("home screen widget", { "one-tap play/stop on your usual speakers" }) {
            val awm = android.appwidget.AppWidgetManager.getInstance(this)
            if (awm.isRequestPinAppWidgetSupported) {
                awm.requestPinAppWidget(android.content.ComponentName(this, AirPlayWidget::class.java), null, null)
            } else {
                Toast.makeText(this, "long-press your home screen → widgets → centuryplay", Toast.LENGTH_LONG).show()
            }
        }

        section("advanced")
        switch("debug log server", "serve logs at http://<phone-ip>:8080 on your wi-fi. leave off unless debugging.", prefs.debugLogServer) {
            prefs.debugLogServer = it
            if (it) LogServer.start() else LogServer.stop()
        }
        action("name on speakers", { prefs.senderName }) { editSenderName() }
        action("share debug log", { "attach this to a github issue if something doesn't work" }) {
            val header = "centuryplay ${BuildConfig.VERSION_NAME} · ${Build.MANUFACTURER} ${Build.MODEL} · android ${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})\n" +
                "shizuku: ${ShizukuManager.status.value} · capture pre-approved: ${ShizukuManager.projectionPreApproved(this)}\n\n"
            val text = header + LogServer.snapshot().joinToString("\n")
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "centuryplay debug log")
                putExtra(Intent.EXTRA_TEXT, text.takeLast(200_000))
            }, "share debug log"))
        }
        action("add speaker manually", { "for receivers that don't show up automatically (ip:port)" }) { addManualDevice() }
        action("version", { "centuryplay ${BuildConfig.VERSION_NAME} · airplay 1 + 2 · alac" }) {}
    }

    // ------------------------------------------------------------------ actions

    private fun shizukuSummary(): String = when (ShizukuManager.status.value) {
        ShizukuManager.Status.NOT_INSTALLED -> "not installed — tap to learn how shizuku removes every permission prompt"
        ShizukuManager.Status.NOT_RUNNING -> "installed but not running — open shizuku and start it"
        ShizukuManager.Status.NO_PERMISSION -> "running — tap to connect centuryplay"
        ShizukuManager.Status.READY -> "connected — tap to re-run one-tap setup"
    }

    private fun onShizukuClicked() {
        when (ShizukuManager.status.value) {
            ShizukuManager.Status.NOT_INSTALLED -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/")))
            ShizukuManager.Status.NOT_RUNNING -> packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")?.let { startActivity(it) }
            ShizukuManager.Status.NO_PERMISSION -> ShizukuManager.requestPermission()
            ShizukuManager.Status.READY -> lifecycleScope.launch {
                val report = ShizukuManager.setupPermissions()
                MaterialAlertDialogBuilder(this@SettingsActivity).setTitle(R.string.setup_done).setMessage(report)
                    .setPositiveButton(R.string.ok, null).show()
                refreshers.forEach { it() }
            }
        }
    }

    private fun requestTile() {
        if (Build.VERSION.SDK_INT < 33) return
        val sbm = getSystemService(android.app.StatusBarManager::class.java)
        sbm.requestAddTileService(
            android.content.ComponentName(this, com.airplay.streamer.service.AirPlayTileService::class.java),
            getString(R.string.tile_label),
            android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_speaker),
            mainExecutor,
        ) {}
    }

    private fun editSenderName() {
        val input = EditText(this).apply {
            setText(prefs.customSenderName)
            hint = prefs.senderName
        }
        val frame = FrameLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(input) }
        MaterialAlertDialogBuilder(this)
            .setTitle("name on speakers")
            .setMessage("shown by receivers that ask before playing (e.g. macs). leave empty for automatic.")
            .setView(frame)
            .setPositiveButton(R.string.ok) { _, _ ->
                prefs.customSenderName = input.text.toString()
                refreshers.forEach { it() }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun addManualDevice() {
        val input = EditText(this).apply {
            hint = getString(R.string.manual_connect_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.raw.getString(KEY_MANUAL_HOST, ""))
        }
        val frame = FrameLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(input) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.manual_connect_title)
            .setView(frame)
            .setPositiveButton(R.string.save_manual_device) { _, _ ->
                val text = input.text.toString().trim()
                val host = text.substringBefore(":")
                val port = text.substringAfter(":", "5000").toIntOrNull() ?: 5000
                if (host.isEmpty()) return@setPositiveButton
                prefs.raw.edit()
                    .putString(KEY_MANUAL_HOST, text)
                    .putString("manual_device_host", host)
                    .putInt("manual_device_port", port)
                    .putBoolean("manual_device_pending", true)
                    .apply()
                Toast.makeText(this, "added $host:$port", Toast.LENGTH_SHORT).show()
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ row builders

    private fun section(title: String) {
        container.addView(TextView(this).apply {
            text = title
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary))
            setPadding(dp(24), dp(24), dp(24), dp(8))
        })
    }

    private fun row(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(24), dp(14), dp(20), dp(14))
        val ripple = android.util.TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
        setBackgroundResource(ripple.resourceId)
    }

    private fun texts(parent: LinearLayout, title: String, summary: String): TextView {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            text = title
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
        })
        val sum = TextView(this).apply {
            text = summary
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
        }
        col.addView(sum)
        parent.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return sum
    }

    private fun switch(title: String, summary: String, value: Boolean, onChange: (Boolean) -> Unit) {
        val r = row()
        texts(r, title, summary)
        val sw = MaterialSwitch(this).apply { isChecked = value; setOnCheckedChangeListener { _, c -> onChange(c) } }
        r.addView(sw)
        r.setOnClickListener { sw.toggle() }
        container.addView(r)
    }

    private fun action(title: String, summary: () -> String, onClick: () -> Unit) {
        val r = row()
        val sum = texts(r, title, summary())
        refreshers += { sum.text = summary() }
        r.setOnClickListener { onClick() }
        container.addView(r)
    }

    private fun slider(
        title: String, summary: String, from: Float, to: Float, step: Float, value: Float,
        label: (Float) -> String, onChange: (Float) -> Unit,
    ) {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(14), dp(20), dp(4))
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        texts(head, title, summary)
        val valueText = TextView(this).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary))
            setPadding(dp(12), 0, 0, 0)
        }
        head.addView(valueText)
        col.addView(head)
        val sl = Slider(this).apply {
            valueFrom = from; valueTo = to; stepSize = step
            this.value = (Math.round(value / step) * step).coerceIn(from, to)
            labelBehavior = com.google.android.material.slider.LabelFormatter.LABEL_GONE
            isTickVisible = false
            addOnChangeListener { _, v, fromUser -> valueText.text = label(v); if (fromUser) onChange(v) }
        }
        valueText.text = label(sl.value)
        col.addView(sl)
        container.addView(col)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
