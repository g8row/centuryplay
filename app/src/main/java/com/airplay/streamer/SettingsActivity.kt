package com.airplay.streamer

import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.DynamicColors
import com.google.android.material.materialswitch.MaterialSwitch

class SettingsActivity : AppCompatActivity() {

    companion object {
        const val PREFS_NAME = "airplay_prefs"
        const val KEY_PROTOCOL = "protocol_preference"
        const val KEY_DEBUG_MODE = "debug_mode"
        const val KEY_MANUAL_HOST = "manual_host"
        const val PROTOCOL_AUTO = 0
        const val PROTOCOL_V1 = 1
        const val PROTOCOL_V2 = 2
    }

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        setupToolbar()
        setupProtocolToggle()
        setupDebugMode()
    }

    private fun setupToolbar() {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupProtocolToggle() {
        val radioGroup = findViewById<RadioGroup>(R.id.protocolRadioGroup)
        val currentProtocol = prefs.getInt(KEY_PROTOCOL, PROTOCOL_AUTO)

        // Set initial selection
        when (currentProtocol) {
            PROTOCOL_V1 -> radioGroup.check(R.id.radioV1)
            PROTOCOL_V2 -> radioGroup.check(R.id.radioV2)
            else -> radioGroup.check(R.id.radioAuto)
        }

        // Listen for changes
        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            val protocol = when (checkedId) {
                R.id.radioV1 -> PROTOCOL_V1
                R.id.radioV2 -> PROTOCOL_V2
                else -> PROTOCOL_AUTO
            }
            prefs.edit { putInt(KEY_PROTOCOL, protocol) }
        }
    }

    private fun setupDebugMode() {
        val debugSwitch = findViewById<MaterialSwitch>(R.id.debugModeSwitch)
        val debugSection = findViewById<LinearLayout>(R.id.debugSection)
        val manualHostInput = findViewById<EditText>(R.id.manualHostInput)
        val connectButton = findViewById<MaterialButton>(R.id.manualConnectButton)

        val debugEnabled = prefs.getBoolean(KEY_DEBUG_MODE, false)
        debugSwitch.isChecked = debugEnabled
        debugSection.visibility = if (debugEnabled) View.VISIBLE else View.GONE

        // Load saved host
        manualHostInput.setText(prefs.getString(KEY_MANUAL_HOST, "192.168.1.100:5000"))

        debugSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean(KEY_DEBUG_MODE, isChecked) }
            debugSection.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        connectButton.setOnClickListener {
            val input = manualHostInput.text.toString().trim()
            if (input.isNotEmpty()) {
                prefs.edit { putString(KEY_MANUAL_HOST, input) }
                addManualDevice(input)
            }
        }
    }

    private fun addManualDevice(input: String) {
        try {
            val parts = input.split(":")
            if (parts.size != 2) {
                Toast.makeText(this, "Invalid format. Use IP:Port", Toast.LENGTH_SHORT).show()
                return
            }
            val host = parts[0]
            val port = parts[1].toIntOrNull() ?: throw NumberFormatException()

            // Store the manual device info
            prefs.edit {
                putString("manual_device_host", host)
                putInt("manual_device_port", port)
                putBoolean("manual_device_pending", true)
            }
            
            Toast.makeText(this, "Manual device saved: $host:$port\nGo back to connect", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Invalid input: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
