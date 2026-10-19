package com.airplay.streamer.system

import android.annotation.SuppressLint

/**
 * Whether the system output switcher lists the speakers by itself, so the route provider can
 * drop its stand-ins (which hide Bluetooth/headphone outputs). True when the centuryplay
 * LSPosed / Vector module is enabled (it hooks [active] in our own process, besides SystemUI and
 * Settings, see xposed/) or when the ROM's SettingsLib does the same natively for the providers
 * in ro.settingslib.system_audio_route_providers.
 */
object HookStatus {
    @JvmStatic
    fun active(): Boolean = romSupport

    private val romSupport: Boolean by lazy {
        systemProperty("ro.settingslib.system_audio_route_providers")
            .split(',').any { it.trim() == "com.airplay.streamer" }
    }

    @SuppressLint("PrivateApi")
    private fun systemProperty(key: String): String = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, key) as String
    }.getOrDefault("")
}
