package com.airplay.streamer

import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.airplay.streamer.databinding.ActivityTileDeviceBinding
import com.airplay.streamer.engine.SpeakerStatus
import com.airplay.streamer.service.StreamController
import com.airplay.streamer.shizuku.ShizukuManager
import com.airplay.streamer.ui.MainViewModel
import com.airplay.streamer.ui.SpeakerAdapter
import com.google.android.material.color.DynamicColors
import kotlinx.coroutines.launch

/**
 * Quick output picker opened from the Quick Settings tile: tap speakers to add/remove them
 * from the stream, adjust their volume, or stop everything. Tap outside to close.
 */
class TileDeviceActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTileDeviceBinding
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        binding = ActivityTileDeviceBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ShizukuManager.init(this)
        binding.root.setOnClickListener { finish() }

        val adapter = SpeakerAdapter(
            onClick = { row ->
                if (!row.supported) return@SpeakerAdapter
                when (row.state?.status) {
                    null, SpeakerStatus.FAILED, SpeakerStatus.NEEDS_ACCEPT -> {
                        if (row.state != null) StreamController.disconnect(this, row.device.identity)
                        StreamController.connect(this, row.device)
                    }
                    SpeakerStatus.NEEDS_PASSWORD, SpeakerStatus.NEEDS_PIN -> {
                        // Passwords are entered in the main app.
                        startActivity(android.content.Intent(this, MainActivity::class.java))
                        finish()
                    }
                    else -> StreamController.disconnect(this, row.device.identity)
                }
            },
            onLongClick = {},
            onVolume = { row, v -> StreamController.setSpeakerVolume(row.device.identity, v) },
        )
        binding.speakersRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.speakersRecyclerView.adapter = adapter
        binding.stopAllButton.setOnClickListener {
            StreamController.stopAll(this)
            finish()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    adapter.submitList(state.rows)
                    binding.emptyView.visibility = if (state.rows.isEmpty()) View.VISIBLE else View.GONE
                    binding.loadingIndicator.visibility = if (state.searching) View.VISIBLE else View.GONE
                    binding.stopAllButton.visibility = if (state.session.capturing) View.VISIBLE else View.GONE
                }
            }
        }
    }
}
