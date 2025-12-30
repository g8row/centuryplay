package com.airplay.streamer

import android.content.SharedPreferences
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import com.google.android.material.color.DynamicColors
import com.google.android.material.radiobutton.MaterialRadioButton
import android.widget.RadioGroup
import android.widget.TextView
import com.google.android.material.appbar.MaterialToolbar

class SettingsActivity : AppCompatActivity() {

    companion object {
        const val PREFS_NAME = "airplay_prefs"
        const val KEY_PROTOCOL = "protocol_preference"
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
}
