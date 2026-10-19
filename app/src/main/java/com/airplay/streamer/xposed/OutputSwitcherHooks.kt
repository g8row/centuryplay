package com.airplay.streamer.xposed

import android.media.MediaRoute2Info
import android.media.MediaRouter2
import android.media.RoutingSessionInfo
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * Hooks into SettingsLib's media output code (statically linked into System UI and Settings),
 * which backs the media card's output chip, the output switcher dialog and Settings' "play
 * media to":
 *
 *  - Apps that play locally always get the *system* routing session as their active one
 *    (InfoMediaManager.getActiveRoutingSession), so while centuryplay streams an app the chip
 *    still says "This tablet". If centuryplay holds a session with speakers for that app, it
 *    becomes the active one: the chip names the speakers, the dialog shows them selected with
 *    their volume, and the other speakers become multi-room checkboxes.
 *  - For the system session, SettingsLib offers the routes the app's own media router can see,
 *    which for ordinary players excludes ours. Our routes are added there, so the speakers are
 *    listed for every app, next to Bluetooth and headphones, and with nothing playing.
 *
 * Picking a speaker then goes through the normal MediaRouter2 path (a session is created on
 * centuryplay's route provider), so no other code changes in the system.
 */
object OutputSwitcherHooks {

    private const val PROVIDER_PACKAGE = "com.airplay.streamer/"
    private const val PHONE_ROUTE_SUFFIX = ":centuryplay_phone"

    fun install(process: String, cl: ClassLoader) {
        val info = XposedHelpers.findClassIfExists("com.android.settingslib.media.InfoMediaManager", cl)
        val router = XposedHelpers.findClassIfExists("com.android.settingslib.media.RouterInfoMediaManager", cl)
        if (info == null || router == null) {
            XposedBridge.log("centuryplay: SettingsLib media classes not found in $process (info=$info router=$router)")
            return
        }
        XposedBridge.log("centuryplay: hooking the output switcher in $process")
        hook(process, "active session") {
            XposedBridge.hookAllMethods(info, "getActiveRoutingSession", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val active = param.result as? RoutingSessionInfo ?: return
                    if (!active.isSystem()) return
                    @Suppress("UNCHECKED_CAST")
                    val sessions = XposedHelpers.callMethod(param.thisObject, "getRoutingSessionsForPackage") as? List<RoutingSessionInfo>
                    sessions?.lastOrNull { it.isOurs() && it.selectedRoutes.any { id -> !id.endsWith(PHONE_ROUTE_SUFFIX) } }
                        ?.let { param.result = it }
                }
            })
        }
        hook(process, "transferable routes") {
            XposedHelpers.findAndHookMethod(router, "getTransferableRoutes", MediaRouter2.RoutingController::class.java, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val controller = param.args[0] as? MediaRouter2.RoutingController ?: return
                    if (!controller.routingSessionInfo.isSystem()) return
                    param.result = withOurRoutes(param.result, param.thisObject)
                }
            })
        }
        hook(process, "available routes") {
            // Apps with a RouteListingPreference get their list from here instead.
            XposedHelpers.findAndHookMethod(router, "getAvailableRoutesFromRouter", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    param.result = withOurRoutes(param.result, param.thisObject)
                }
            })
        }
    }

    // @hide in the SDK, public in the platform.
    private fun RoutingSessionInfo.isSystem() = XposedHelpers.callMethod(this, "isSystemSession") as Boolean

    // Outside the provider, ids are unique ids: "<provider component>:<original id>".
    private fun RoutingSessionInfo.isOurs() = id.startsWith(PROVIDER_PACKAGE)

    private fun MediaRoute2Info.isOurSpeaker() = id.startsWith(PROVIDER_PACKAGE) && !id.endsWith(PHONE_ROUTE_SUFFIX)

    @Suppress("UNCHECKED_CAST")
    private fun withOurRoutes(result: Any?, manager: Any): List<MediaRoute2Info> {
        val routes = (result as? List<MediaRoute2Info>).orEmpty()
        val mediaRouter = XposedHelpers.getObjectField(manager, "mRouter") as? MediaRouter2 ?: return routes
        val all = runCatching { XposedHelpers.callMethod(mediaRouter, "getAllRoutes") as List<MediaRoute2Info> }.getOrNull()
            ?: return routes
        val known = routes.mapTo(HashSet()) { it.id }
        val ours = all.filter { it.isOurSpeaker() && it.id !in known }
        return if (ours.isEmpty()) routes else routes + ours
    }

    private inline fun hook(process: String, what: String, block: () -> Unit) {
        runCatching(block).onFailure { XposedBridge.log("centuryplay: $what hook failed in $process: $it") }
    }
}
