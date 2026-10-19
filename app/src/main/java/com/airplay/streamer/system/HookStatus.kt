package com.airplay.streamer.system

/**
 * Whether the centuryplay LSPosed / Vector module is enabled. The module hooks [active] in our
 * own process to return true; it also hooks SystemUI and Settings (see xposed/), so when this is
 * true the system output switcher lists the speakers by itself and the route provider can drop
 * its stand-ins (which hide Bluetooth/headphone outputs).
 */
object HookStatus {
    @JvmStatic
    fun active(): Boolean = false
}
