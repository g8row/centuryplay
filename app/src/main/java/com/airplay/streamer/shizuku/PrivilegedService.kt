package com.airplay.streamer.shizuku

import android.annotation.SuppressLint
import android.app.Application
import android.app.Instrumentation
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.media.AudioRecord
import android.os.Binder
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import com.airplay.streamer.system.PolicyCapture
import java.io.FileOutputStream
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Shizuku UserService: runs inside a separate process as the **shell** user (uid 2000),
 * which holds MODIFY_AUDIO_ROUTING / CAPTURE_AUDIO_OUTPUT. That lets it run [PolicyCapture],
 * so media audio can be *rerouted* to the AirPlay stream (phone speaker silent) without any
 * MediaProjection consent.
 *
 * Everything here is reflection on hidden APIs; the process is app_process, so hidden
 * API restrictions don't apply. Must keep a public no-arg constructor for Shizuku.
 */
class PrivilegedService : IPrivilegedService.Stub() {

    private val policyCapture by lazy { PolicyCapture(ShellContext.get()) }
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
            policyCapture.start(keepLocalPlayback, captureUsages)
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
        policyCapture.stop()
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
        private val CAPTURED_USAGES = PolicyCapture.DEFAULT_USAGES
    }
}
