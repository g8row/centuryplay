package com.airplay.streamer.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.airplay.streamer.BuildConfig
import com.airplay.streamer.service.NotificationListener
import com.airplay.streamer.util.LogServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

/**
 * Optional Shizuku integration. When Shizuku is running and has granted us access:
 *  - [setupPermissions] grants everything the app needs in one go (no consent dialog for
 *    MediaProjection ever again, notification access for track info, battery exemption);
 *  - [privileged] exposes a shell-user service that can capture media audio by rerouting
 *    it (the phone speaker goes silent, no MediaProjection at all).
 */
object ShizukuManager {

    enum class Status { NOT_INSTALLED, NOT_RUNNING, NO_PERMISSION, READY }

    private val _status = MutableStateFlow(Status.NOT_RUNNING)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _privileged = MutableStateFlow<IPrivilegedService?>(null)
    val privileged: StateFlow<IPrivilegedService?> = _privileged.asStateFlow()

    private const val REQUEST_CODE = 3939
    private lateinit var appContext: Context
    private var initialized = false

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == REQUEST_CODE) {
            refresh()
            if (result == PackageManager.PERMISSION_GRANTED) bind()
        }
    }

    private val binderReceived = Shizuku.OnBinderReceivedListener {
        refresh()
        if (_status.value == Status.READY) bind()
    }
    private val binderDead = Shizuku.OnBinderDeadListener {
        _privileged.value = null
        refresh()
    }

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(appContext.packageName, PrivilegedService::class.java.name))
            .daemon(false)
            .processNameSuffix("privileged")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE * 100 + PrivilegedService.VERSION)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder != null && binder.pingBinder()) {
                _privileged.value = IPrivilegedService.Stub.asInterface(binder)
                LogServer.log("Shizuku: privileged service connected")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            _privileged.value = null
        }
    }

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        refresh()
    }

    fun refresh() {
        _status.value = when {
            !isInstalled() -> Status.NOT_INSTALLED
            !Shizuku.pingBinder() -> Status.NOT_RUNNING
            Shizuku.isPreV11() -> Status.NOT_RUNNING
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> Status.NO_PERMISSION
            else -> Status.READY
        }
    }

    private fun isInstalled(): Boolean = try {
        appContext.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        Shizuku.pingBinder() // Sui / other managers
    }

    fun requestPermission() {
        if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(REQUEST_CODE)
        }
    }

    fun bind() {
        if (_privileged.value != null) return
        runCatching { Shizuku.bindUserService(userServiceArgs, connection) }
            .onFailure { LogServer.log("Shizuku: bind failed: ${it.message}") }
    }

    /** Waits (briefly) for the privileged service after [bind]. */
    suspend fun awaitPrivileged(timeoutMs: Long = 3000): IPrivilegedService? {
        refresh()
        if (_status.value != Status.READY) return null
        bind()
        return _privileged.value ?: withTimeoutOrNull(timeoutMs) { _privileged.filterNotNull().first() }
    }

    /**
     * Grants the app everything it would otherwise ask the user for, via shell commands.
     * Returns a human-readable report.
     */
    suspend fun setupPermissions(): String = withContext(Dispatchers.IO) {
        val svc = awaitPrivileged() ?: return@withContext "Shizuku isn't ready"
        val pkg = appContext.packageName
        val listener = ComponentName(appContext, NotificationListener::class.java).flattenToString()
        val commands = listOf(
            "Skip screen-capture consent" to "appops set $pkg PROJECT_MEDIA allow",
            "Microphone/capture" to "pm grant $pkg android.permission.RECORD_AUDIO",
            "Notifications" to "pm grant $pkg android.permission.POST_NOTIFICATIONS",
            "Track info access" to "cmd notification allow_listener $listener",
            "Battery optimisation exemption" to "cmd deviceidle whitelist +$pkg",
            "Background running" to "appops set $pkg RUN_ANY_IN_BACKGROUND allow",
        )
        commands.joinToString("\n") { (label, cmd) ->
            val result = runCatching { svc.exec(cmd) }.getOrElse { "-1\n${it.message}" }
            val ok = result.substringBefore('\n').trim() == "0"
            LogServer.log("Shizuku: $cmd -> ${result.replace('\n', ' ').take(120)}")
            "${if (ok) "✓" else "✗"} $label"
        }
    }

    /** True if PROJECT_MEDIA is already allowed (the consent dialog won't show). */
    fun projectionPreApproved(context: Context): Boolean = try {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        @Suppress("DEPRECATION")
        ops.unsafeCheckOpNoThrow("android:project_media", android.os.Process.myUid(), context.packageName) ==
            android.app.AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) {
        false
    }
}
