package com.airplay.streamer.xposed

import com.airplay.streamer.BuildConfig
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * LSPosed / Vector entry point (assets/xposed_init). Scope: System UI, Settings and centuryplay.
 * The hooks only add the speakers to what the system already shows; without them the app falls
 * back to its own output-switcher integration.
 */
class XposedEntry : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        when (lpparam.packageName) {
            BuildConfig.APPLICATION_ID -> runCatching {
                XposedHelpers.findAndHookMethod(
                    "com.airplay.streamer.system.HookStatus", lpparam.classLoader, "active",
                    XC_MethodReplacement.returnConstant(true),
                )
            }.onFailure { XposedBridge.log("centuryplay: status hook failed: $it") }

            SYSTEMUI, SETTINGS -> withAppClassLoader(lpparam) { OutputSwitcherHooks.install(lpparam.packageName, it) }
        }
    }

    /**
     * System UI's classes aren't always reachable from the loader handed to handleLoadPackage
     * (its app component factory sets up its own); fall back to the Application's loader.
     */
    private fun withAppClassLoader(lpparam: XC_LoadPackage.LoadPackageParam, install: (ClassLoader) -> Unit) {
        if (XposedHelpers.findClassIfExists(PROBE_CLASS, lpparam.classLoader) != null) return install(lpparam.classLoader)
        XposedHelpers.findAndHookMethod(android.app.Application::class.java, "attach", android.content.Context::class.java, object : XC_MethodHook() {
            private var done = false
            override fun afterHookedMethod(param: MethodHookParam) {
                if (done) return
                done = true
                install((param.args[0] as android.content.Context).classLoader)
            }
        })
    }

    companion object {
        private const val PROBE_CLASS = "com.android.settingslib.media.InfoMediaManager"
        const val SYSTEMUI = "com.android.systemui"
        const val SETTINGS = "com.android.settings"
    }
}
