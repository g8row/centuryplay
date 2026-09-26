package com.airplay.streamer.shizuku

import android.annotation.SuppressLint
import android.app.Application
import android.app.Instrumentation
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Binder
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import java.io.FileOutputStream
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Shizuku UserService: runs inside a separate process as the **shell** user (uid 2000),
 * which holds MODIFY_AUDIO_ROUTING / CAPTURE_AUDIO_OUTPUT. That lets us register an
 * AudioPolicy loopback mix — the same technique scrcpy uses for `--audio-source=playback`
 * (scrcpy AudioPlaybackCapture.java, Apache 2.0) — so media audio can be *rerouted* to
 * the AirPlay stream (phone speaker silent) without any MediaProjection consent.
 *
 * Everything here is reflection on hidden APIs; the process is app_process, so hidden
 * API restrictions don't apply. Must keep a public no-arg constructor for Shizuku.
 */
class PrivilegedService : IPrivilegedService.Stub() {

    private var policy: Any? = null
    private var record: AudioRecord? = null
    @Volatile private var capturing = false
    private var pumpThread: Thread? = null
    private var captureUsages: IntArray = CAPTURED_USAGES

    override fun destroy() {
        stopCapture()
        exitProcess(0)
    }

    override fun version(): Int = VERSION

    override fun exec(command: String): String {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val out = p.inputStream.bufferedReader().readText() + p.errorStream.bufferedReader().readText()
            "${p.waitFor()}\n$out"
        } catch (e: Exception) {
            "-1\n${e.message}"
        }
    }

    override fun startCapture(keepLocalPlayback: Boolean): ParcelFileDescriptor =
        startCaptureForUsages(keepLocalPlayback, CAPTURED_USAGES)

    @Synchronized
    override fun startCaptureForUsages(keepLocalPlayback: Boolean, usages: IntArray): ParcelFileDescriptor {
        stopCapture()
        captureUsages = if (usages.isEmpty()) CAPTURED_USAGES else usages
        // AudioPolicy checks MODIFY_AUDIO_ROUTING against the *binder caller* (our app);
        // clear it so the check runs against this process (shell).
        val token = Binder.clearCallingIdentity()
        val rec = try {
            createPolicyRecord(keepLocalPlayback)
        } catch (e: Throwable) {
            policy?.let { unregisterPolicy(it) }
            policy = null
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            Log.e(TAG, "audio policy capture failed", cause)
            throw IllegalStateException("audio policy capture failed: ${cause.javaClass.simpleName}: ${cause.message}", cause)
        } finally {
            Binder.restoreCallingIdentity(token)
        }
        val pipe = ParcelFileDescriptor.createPipe()
        val out = FileOutputStream(pipe[1].fileDescriptor)
        record = rec
        capturing = true
        rec.startRecording()
        pumpThread = thread(name = "PolicyCapture") {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buf = ByteArray(BUFFER_BYTES)
            try {
                while (capturing) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n < 0) break
                    if (n > 0) out.write(buf, 0, n)
                }
            } catch (e: Exception) {
                Log.w(TAG, "capture pump ended: ${e.message}")
            } finally {
                runCatching { out.close() }
                runCatching { pipe[1].close() }
                // Reader closed the pipe or the record died: release the reroute.
                if (capturing) stopCapture()
            }
        }
        return pipe[0]
    }

    @Synchronized
    override fun stopCapture() {
        capturing = false
        record?.let { r -> runCatching { r.stop() }; runCatching { r.release() } }
        record = null
        policy?.let { unregisterPolicy(it) }
        policy = null
    }

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun createPolicyRecord(keepLocal: Boolean): AudioRecord {
        val ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule")
        val ruleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
        val ruleBuilder = ruleBuilderClass.getConstructor().newInstance()
        ruleBuilderClass.getMethod("setTargetMixRole", Int::class.javaPrimitiveType)
            .invoke(ruleBuilder, ruleClass.getField("MIX_ROLE_PLAYERS").getInt(null))
        val matchUsage = ruleClass.getField("RULE_MATCH_ATTRIBUTE_USAGE").getInt(null)
        val addRule = ruleBuilderClass.getMethod("addMixRule", Int::class.javaPrimitiveType, Any::class.java)
        for (usage in captureUsages) {
            addRule.invoke(ruleBuilder, matchUsage, AudioAttributes.Builder().setUsage(usage).build())
        }
        // Note: allowPrivilegedPlaybackCapture(true) would include apps that only allow
        // capture "by system" (Apple Music), but AudioService only permits it for <=16 kHz
        // mono mixes (AudioMix.canBeUsedForPrivilegedMediaCapture) — useless for music.
        val rule = ruleBuilderClass.getMethod("build").invoke(ruleBuilder)

        val mixClass = Class.forName("android.media.audiopolicy.AudioMix")
        val mixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
        val mixBuilder = mixBuilderClass.getConstructor(ruleClass).newInstance(rule)
        mixBuilderClass.getMethod("setFormat", AudioFormat::class.java).invoke(mixBuilder, FORMAT)
        val flagName = if (keepLocal) "ROUTE_FLAG_LOOP_BACK_RENDER" else "ROUTE_FLAG_LOOP_BACK"
        mixBuilderClass.getMethod("setRouteFlags", Int::class.javaPrimitiveType)
            .invoke(mixBuilder, mixClass.getField(flagName).getInt(null))
        val mix = mixBuilderClass.getMethod("build").invoke(mixBuilder)

        val policyClass = Class.forName("android.media.audiopolicy.AudioPolicy")
        val policyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")
        val policyBuilder = policyBuilderClass.getConstructor(Context::class.java).newInstance(ShellContext.get())
        policyBuilderClass.getMethod("addMix", mixClass).invoke(policyBuilder, mix)
        val newPolicy = policyBuilderClass.getMethod("build").invoke(policyBuilder)!!

        val register = AudioManager::class.java.getDeclaredMethod("registerAudioPolicyStatic", policyClass)
        register.isAccessible = true
        val result = register.invoke(null, newPolicy) as Int
        if (result != 0) throw IllegalStateException("registerAudioPolicy returned $result")
        policy = newPolicy

        return policyClass.getMethod("createAudioRecordSink", mixClass).invoke(newPolicy, mix) as AudioRecord?
            ?: throw IllegalStateException("createAudioRecordSink returned null")
    }

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi", "SoonBlockedPrivateApi", "BlockedPrivateApi")
    private fun unregisterPolicy(p: Any) {
        val policyClass = Class.forName("android.media.audiopolicy.AudioPolicy")
        try {
            val m = AudioManager::class.java.getDeclaredMethod("unregisterAudioPolicyAsyncStatic", policyClass)
            m.isAccessible = true
            m.invoke(null, p)
            return
        } catch (_: NoSuchMethodException) {
        } catch (e: Exception) {
            Log.w(TAG, "unregisterAudioPolicyAsyncStatic failed: ${e.message}")
        }
        try {
            // Fallback: IAudioService.unregisterAudioPolicyAsync(policy.cb())
            val sm = Class.forName("android.os.ServiceManager")
            val binder = sm.getMethod("getService", String::class.java).invoke(null, Context.AUDIO_SERVICE)
            val stub = Class.forName("android.media.IAudioService\$Stub")
            val service = stub.getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)
            val cb = policyClass.getDeclaredMethod("cb").apply { isAccessible = true }.invoke(p)
            val cbClass = Class.forName("android.media.audiopolicy.IAudioPolicyCallback")
            service!!.javaClass.getMethod("unregisterAudioPolicyAsync", cbClass).invoke(service, cb)
        } catch (e: Exception) {
            Log.w(TAG, "could not unregister audio policy: ${e.message}")
        }
    }

    /**
     * Minimal Context for the shell process, like scrcpy's FakeContext/Workarounds: a system
     * context that reports package "com.android.shell" and the shell uid, installed as the
     * process's Application so AudioRecord/AudioPolicy attribute correctly.
     */
    // Runs in Shizuku's app_process (shell uid), where hidden-API restrictions don't apply.
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi", "BlockedPrivateApi", "SoonBlockedPrivateApi")
    private class ShellContext private constructor(base: Context) : ContextWrapper(base) {
        override fun getPackageName() = SHELL_PACKAGE
        override fun getOpPackageName() = SHELL_PACKAGE
        override fun getApplicationContext(): Context = this

        override fun getAttributionSource(): AttributionSource =
            if (Build.VERSION.SDK_INT >= 31) AttributionSource.Builder(Process.SHELL_UID).setPackageName(SHELL_PACKAGE).build()
            else super.getAttributionSource()

        companion object {
            private const val SHELL_PACKAGE = "com.android.shell"
            @Volatile private var instance: ShellContext? = null

            fun get(): ShellContext = instance ?: synchronized(this) { instance ?: create().also { instance = it } }

            private fun create(): ShellContext {
                val atClass = Class.forName("android.app.ActivityThread")
                var at = atClass.getMethod("currentActivityThread").invoke(null)
                if (at == null) {
                    val ctor = atClass.getDeclaredConstructor().apply { isAccessible = true }
                    at = ctor.newInstance()
                    atClass.getDeclaredField("sCurrentActivityThread").apply { isAccessible = true }.set(null, at)
                    atClass.getDeclaredField("mSystemThread").apply { isAccessible = true }.setBoolean(at, true)
                }
                if (Build.VERSION.SDK_INT >= 31) runCatching {
                    val ccClass = Class.forName("android.app.ConfigurationController")
                    val atiClass = Class.forName("android.app.ActivityThreadInternal")
                    val field = atClass.getDeclaredField("mConfigurationController").apply { isAccessible = true }
                    if (field.get(at) == null) {
                        field.set(at, ccClass.getDeclaredConstructor(atiClass).apply { isAccessible = true }.newInstance(at))
                    }
                }
                val system = atClass.getDeclaredMethod("getSystemContext").apply { isAccessible = true }.invoke(at) as Context
                val ctx = ShellContext(system)
                runCatching {
                    val bindClass = Class.forName("android.app.ActivityThread\$AppBindData")
                    val bind = bindClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
                    bindClass.getDeclaredField("appInfo").apply { isAccessible = true }
                        .set(bind, ApplicationInfo().apply { packageName = SHELL_PACKAGE })
                    atClass.getDeclaredField("mBoundApplication").apply { isAccessible = true }.set(at, bind)
                }
                runCatching {
                    val app = Instrumentation.newApplication(Application::class.java, ctx)
                    atClass.getDeclaredField("mInitialApplication").apply { isAccessible = true }.set(at, app)
                }
                return ctx
            }
        }
    }

    companion object {
        const val VERSION = 5
        private const val TAG = "centuryplay-priv"
        private const val BUFFER_BYTES = 352 * 4 * 2
        private val CAPTURED_USAGES = intArrayOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME, AudioAttributes.USAGE_UNKNOWN)
        private val FORMAT: AudioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(44100)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
    }
}
