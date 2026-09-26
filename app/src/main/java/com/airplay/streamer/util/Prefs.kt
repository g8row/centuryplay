package com.airplay.streamer.util

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings

/** Typed access to user settings (shared by activities, the service, the tile and the router). */
class Prefs(context: Context) {
    private val app = context.applicationContext
    val raw: SharedPreferences = app.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    private val secrets: SharedPreferences = app.getSharedPreferences("airplay_secrets", Context.MODE_PRIVATE)

    var latencyMs: Int
        get() = raw.getInt("latency_ms", 2000)
        set(v) = raw.edit().putInt("latency_ms", v).apply()

    /** Send uncompressed PCM instead of ALAC (more bandwidth, zero encode cost). */
    var preferPcm: Boolean
        get() = raw.getBoolean("prefer_pcm", false)
        set(v) = raw.edit().putBoolean("prefer_pcm", v).apply()

    /** Use AirPlay 2 even when the device also offers AirPlay 1. */
    var preferAirPlay2: Boolean
        get() = raw.getBoolean("prefer_airplay2", false)
        set(v) = raw.edit().putBoolean("prefer_airplay2", v).apply()

    /** Mute the phone's own speaker while streaming (capture is pre-volume, so the stream is unaffected). */
    var silencePhone: Boolean
        get() = raw.getBoolean("silence_phone", true)
        set(v) = raw.edit().putBoolean("silence_phone", v).apply()

    /** Hardware volume keys control the speakers while streaming. */
    var volumeKeysControlSpeakers: Boolean
        get() = raw.getBoolean("volume_keys_speakers", true)
        set(v) = raw.edit().putBoolean("volume_keys_speakers", v).apply()

    /** Stop streaming after this many minutes of silence (0 = never). */
    var autoStopMinutes: Int
        get() = raw.getInt("auto_stop_minutes", 15)
        set(v) = raw.edit().putInt("auto_stop_minutes", v).apply()

    /** Also stream game audio (USAGE_GAME); off keeps game sounds on the phone. */
    var includeGames: Boolean
        get() = raw.getBoolean("include_games", true)
        set(v) = raw.edit().putBoolean("include_games", v).apply()

    /** AudioAttributes usages that go to the speakers. */
    val captureUsages: IntArray
        get() = buildList {
            add(android.media.AudioAttributes.USAGE_MEDIA)
            add(android.media.AudioAttributes.USAGE_UNKNOWN)
            if (includeGames) add(android.media.AudioAttributes.USAGE_GAME)
        }.toIntArray()

    var sendMetadata: Boolean
        get() = raw.getBoolean("send_metadata", true)
        set(v) = raw.edit().putBoolean("send_metadata", v).apply()

    /** Start streaming to the last speakers automatically when any app starts playing. */
    var autoPlay: Boolean
        get() = raw.getBoolean("auto_play", false)
        set(v) = raw.edit().putBoolean("auto_play", v).apply()

    /** Auto-play is paused until this time (set when the user stops streaming by hand). */
    var autoPlaySuppressedUntil: Long
        get() = raw.getLong("auto_play_suppressed_until", 0)
        set(v) = raw.edit().putLong("auto_play_suppressed_until", v).apply()

    var autoConnect: Boolean
        get() = raw.getBoolean("auto_connect", false)
        set(v) = raw.edit().putBoolean("auto_connect", v).apply()

    var keepScreenOn: Boolean
        get() = raw.getBoolean("keep_screen_on", false)
        set(v) = raw.edit().putBoolean("keep_screen_on", v).apply()

    var showNowPlaying: Boolean
        get() = raw.getBoolean("show_now_playing", true)
        set(v) = raw.edit().putBoolean("show_now_playing", v).apply()

    /** Capture through Shizuku (no consent dialog, phone stays silent) when available. */
    var useShizukuCapture: Boolean
        get() = raw.getBoolean("use_shizuku_capture", true)
        set(v) = raw.edit().putBoolean("use_shizuku_capture", v).apply()

    /** List AirPlay speakers in the system Output Switcher of whatever app is playing. */
    var systemSwitcher: Boolean
        get() = raw.getBoolean("system_switcher", true)
        set(v) = raw.edit().putBoolean("system_switcher", v).apply()

    var debugLogServer: Boolean
        get() = raw.getBoolean("debug_mode", false)
        set(v) = raw.edit().putBoolean("debug_mode", v).apply()

    /** Identities of the speakers used last time (for auto-connect / one-tap reconnect). */
    var lastSpeakers: Set<String>
        get() = raw.getStringSet("last_speakers", emptySet()) ?: emptySet()
        set(v) = raw.edit().putStringSet("last_speakers", v).apply()

    /** Recently used multi-speaker groups (most recent first), each "id1,id2|label". */
    var recentGroups: List<Pair<Set<String>, String>>
        get() = (raw.getString("recent_groups", "") ?: "").split("\n").filter { it.contains("|") }.map {
            it.substringBefore("|").split(",").toSet() to it.substringAfter("|")
        }
        set(v) = raw.edit().putString("recent_groups", v.take(3).joinToString("\n") { (ids, label) ->
            ids.joinToString(",") + "|" + label
        }).apply()

    fun rememberGroup(ids: Set<String>, label: String) {
        if (ids.size < 2) return
        recentGroups = listOf(ids to label) + recentGroups.filterNot { it.first == ids }
    }

    fun speakerVolume(identity: String): Float = raw.getFloat("vol_$identity", 0.6f)
    fun setSpeakerVolume(identity: String, v: Float) = raw.edit().putFloat("vol_$identity", v).apply()

    fun speakerOffsetMs(identity: String): Int = raw.getInt("offset_$identity", 0)
    fun setSpeakerOffsetMs(identity: String, ms: Int) = raw.edit().putInt("offset_$identity", ms).apply()

    fun password(identity: String): String? = secrets.getString("pw_$identity", null)
    fun setPassword(identity: String, password: String?) =
        secrets.edit().apply { if (password == null) remove("pw_$identity") else putString("pw_$identity", password) }.apply()

    /** Long-term AirPlay 2 pairings (HAP), per receiver. */
    val hapStore = object : com.airplay.streamer.airplay2.HapCredentialStore {
        override fun load(identity: String) =
            com.airplay.streamer.airplay2.HapCredentials.parse(secrets.getString("hap_$identity", null))
        override fun save(identity: String, credentials: com.airplay.streamer.airplay2.HapCredentials) =
            secrets.edit().putString("hap_$identity", credentials.serialize()).apply()
        override fun clear(identity: String) = secrets.edit().remove("hap_$identity").apply()
    }

    /** Custom name shown on receivers; empty = automatic. */
    var customSenderName: String
        get() = raw.getString("sender_name", "") ?: ""
        set(v) = raw.edit().putString("sender_name", v.trim()).apply()

    /**
     * Name shown on receivers ("… would like to AirPlay"): the user's choice, else the phone's
     * device name — unless that is just the model code (MIUI), then the marketing name.
     */
    val senderName: String
        get() {
            customSenderName.takeIf { it.isNotBlank() }?.let { return it }
            val deviceName = Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME)
            if (!deviceName.isNullOrBlank() && deviceName != Build.MODEL) return deviceName
            systemProperty("ro.product.marketname")?.let { return it }
            systemProperty("ro.product.vendor.marketname")?.let { return it }
            return "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
        }

    private fun systemProperty(key: String): String? = try {
        @Suppress("PrivateApi")
        val sp = Class.forName("android.os.SystemProperties")
        (sp.getMethod("get", String::class.java).invoke(null, key) as String).takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    companion object {
        const val NAME = "airplay_prefs"
    }
}
