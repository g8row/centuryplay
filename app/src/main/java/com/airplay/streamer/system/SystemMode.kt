package com.airplay.streamer.system

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/**
 * What the OS lets centuryplay do beyond a normal app. Installed as a privileged system app
 * (the centuryplay root module, or a ROM that ships it) it holds MODIFY_AUDIO_ROUTING and
 * captures by rerouting audio itself: no capture prompt, no Shizuku, phone speaker silent.
 */
object SystemMode {

    /** Can register an AudioPolicy loopback mix in-process ([PolicyCapture]). */
    fun canReroute(context: Context): Boolean =
        granted(context, "android.permission.MODIFY_AUDIO_ROUTING")

    fun isSystemApp(context: Context): Boolean =
        context.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0

    /** Can read every app's media sessions without notification access. */
    fun canReadSessions(context: Context): Boolean =
        granted(context, "android.permission.MEDIA_CONTENT_CONTROL")

    /** One-line state for settings and diagnostics. */
    fun describe(context: Context): String = when {
        canReroute(context) -> "system app — capture without prompts, phone speaker bypassed"
        isSystemApp(context) -> "system app, but audio routing isn't granted (privapp-permissions missing?)"
        else -> "regular app"
    }

    private fun granted(context: Context, permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
