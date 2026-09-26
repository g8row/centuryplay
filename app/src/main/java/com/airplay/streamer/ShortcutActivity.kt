package com.airplay.streamer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.lifecycleScope
import com.airplay.streamer.discovery.DiscoveryRepository
import com.airplay.streamer.service.StreamController
import com.airplay.streamer.shizuku.ShizukuManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Invisible target for launcher shortcuts ("play on Century", "stop streaming") and other
 * external triggers. Being an activity, it may start capture (and ask for consent) directly.
 */
class ShortcutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ShizukuManager.init(this)
        when (intent.action) {
            ACTION_STOP -> {
                StreamController.stopAll(this)
                finish()
            }
            ACTION_PLAY -> play(intent.getStringExtra(EXTRA_IDENTITIES)?.split(",")?.filter { it.isNotBlank() }.orEmpty())
            else -> finish()
        }
    }

    private fun play(identities: List<String>) {
        if (identities.isEmpty()) return finish()
        val repo = DiscoveryRepository.getInstance(this)
        repo.startDiscovery()
        lifecycleScope.launch {
            val devices = withTimeoutOrNull(6000) {
                repo.devices.first { list -> identities.all { id -> list.any { it.identity == id } } }
            } ?: repo.devices.value
            val found = devices.filter { it.identity in identities }
            if (found.isEmpty()) Toast.makeText(this@ShortcutActivity, R.string.shortcut_not_found, Toast.LENGTH_SHORT).show()
            found.forEach { StreamController.connect(this@ShortcutActivity, it) }
            repo.stopDiscovery()
            finish()
        }
    }

    companion object {
        const val ACTION_PLAY = "com.airplay.streamer.shortcut.PLAY"
        const val ACTION_STOP = "com.airplay.streamer.shortcut.STOP"
        const val EXTRA_IDENTITIES = "identities"

        /** Publish a dynamic shortcut for a speaker (or group) the user just played on. */
        fun publish(context: Context, identities: List<String>, label: String) {
            if (identities.isEmpty()) return
            val id = "play_" + identities.sorted().joinToString("_")
            val intent = Intent(context, ShortcutActivity::class.java)
                .setAction(ACTION_PLAY)
                .putExtra(EXTRA_IDENTITIES, identities.joinToString(","))
            val shortcut = ShortcutInfoCompat.Builder(context, id)
                .setShortLabel(label.take(24))
                .setLongLabel(context.getString(R.string.shortcut_play_on, label))
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_speaker))
                .setIntent(intent)
                .build()
            runCatching { ShortcutManagerCompat.pushDynamicShortcut(context, shortcut) }
        }
    }
}
