package com.airplay.streamer.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.net.Inet4Address
import java.util.concurrent.Executors

/**
 * AirPlay discovery through Android's own mDNS stack (NsdManager).
 *
 * Unlike JmDNS this needs no multicast socket or multicast lock in our process (JmDNS fails
 * with EPERM on newer Android, GitHub issue #6) and the system caches/answers mDNS for all
 * apps, which is cheaper on battery.
 *
 * Every `_raop._tcp` / `_airplay._tcp` record is kept as-is, and the device list is
 * re-derived from the full record table on every change, so a speaker that restarts or
 * re-announces one of its two services can never leave a half-merged entry behind.
 */
class NsdDiscovery(context: Context) {

    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val executor = Executors.newSingleThreadExecutor()

    private data class Record(
        val type: String, val name: String, val host: String, val port: Int, val txt: Map<String, String>,
    )

    /** Emits the full, merged device list whenever it changes. Fails if NSD can't start. */
    fun devices(): Flow<List<AirPlayDevice>> = callbackFlow {
        val records = LinkedHashMap<String, Record>() // key = type|name
        val serviceCallbacks = mutableMapOf<String, NsdManager.ServiceInfoCallback>()
        val browsed = mutableMapOf<String, NsdServiceInfo>() // services the browse currently reports
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        lateinit var register: (String, NsdServiceInfo) -> Unit
        val resolveQueue = ArrayDeque<NsdServiceInfo>()
        var resolving = false
        var started = 0
        var failed = 0

        fun publish() {
            trySend(merge(records.values.toList()))
        }

        fun put(type: String, info: NsdServiceInfo) {
            val host = hostOf(info) ?: return
            val txt = info.attributes.mapNotNull { (k, v) ->
                (v?.toString(Charsets.UTF_8) ?: "").let { k to it }
            }.toMap()
            val key = "$type|${info.serviceName}"
            val rec = Record(type, info.serviceName, host, info.port, txt)
            if (records[key] != rec) {
                records[key] = rec
                publish()
            }
        }

        fun remove(type: String, name: String) {
            if (records.remove("$type|$name") != null) publish()
        }

        // Pre-Android 14: resolveService allows one resolve at a time, so queue them.
        fun resolveNext() {
            if (resolving) return
            val next = resolveQueue.removeFirstOrNull() ?: return
            resolving = true
            @Suppress("DEPRECATION")
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    executor.execute { resolving = false; resolveNext() }
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    executor.execute {
                        put(typeOf(next), info)
                        resolving = false
                        resolveNext()
                    }
                }
            })
        }

        register = { type, info ->
            val key = "$type|${info.serviceName}"
            if (!serviceCallbacks.containsKey(key) && Build.VERSION.SDK_INT >= 34) {
                val cb = object : NsdManager.ServiceInfoCallback {
                    override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                        Log.w(TAG, "info callback failed for ${info.serviceName}: $errorCode")
                    }
                    override fun onServiceUpdated(updated: NsdServiceInfo) = executor.execute { put(type, updated) }
                    override fun onServiceLost() = executor.execute {
                        serviceCallbacks.remove(key)?.let { if (Build.VERSION.SDK_INT >= 34) runCatching { nsd.unregisterServiceInfoCallback(it) } }
                        remove(type, info.serviceName)
                        // The browse may still list it (e.g. a TTL expired while dozing) and then
                        // never report it again: re-resolve shortly while it's still browsed.
                        handler.postDelayed({
                            executor.execute { browsed[key]?.let { register(type, it) } }
                        }, RERESOLVE_DELAY_MS)
                    }
                    override fun onServiceInfoCallbackUnregistered() {}
                }
                serviceCallbacks[key] = cb
                runCatching { nsd.registerServiceInfoCallback(info, executor, cb) }
                    .onFailure { serviceCallbacks.remove(key); Log.w(TAG, "registerServiceInfoCallback: ${it.message}") }
            }
        }

        fun listenerFor(type: String) = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                started++
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "discovery of $serviceType failed: $errorCode")
                failed++
                if (failed == SERVICE_TYPES.size) close(IllegalStateException("NSD discovery unavailable ($errorCode)"))
            }

            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

            override fun onServiceFound(info: NsdServiceInfo) {
                executor.execute {
                    val key = "$type|${info.serviceName}"
                    browsed[key] = info
                    if (Build.VERSION.SDK_INT >= 34) {
                        register(type, info)
                    } else {
                        info.serviceType = type
                        resolveQueue.addLast(info)
                        resolveNext()
                    }
                }
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                executor.execute {
                    val key = "$type|${info.serviceName}"
                    browsed.remove(key)
                    serviceCallbacks.remove(key)?.let { if (Build.VERSION.SDK_INT >= 34) runCatching { nsd.unregisterServiceInfoCallback(it) } }
                    remove(type, info.serviceName)
                }
            }
        }

        val listeners = SERVICE_TYPES.map { type ->
            listenerFor(type).also { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, it) }
        }
        publish()

        awaitClose {
            handler.removeCallbacksAndMessages(null)
            listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }
            executor.execute {
                if (Build.VERSION.SDK_INT >= 34) serviceCallbacks.values.forEach { runCatching { nsd.unregisterServiceInfoCallback(it) } }
                serviceCallbacks.clear()
            }
        }
    }

    private fun typeOf(info: NsdServiceInfo): String =
        if (info.serviceType.contains("raop")) RAOP else AIRPLAY

    private fun hostOf(info: NsdServiceInfo): String? {
        val addresses = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(@Suppress("DEPRECATION") info.host)
        // IPv4 only: link-local IPv6 addresses need a scope id and break RTSP URIs.
        return addresses.filterIsInstance<Inet4Address>().firstOrNull()?.hostAddress
    }

    companion object {
        private const val TAG = "NsdDiscovery"
        const val RAOP = "_raop._tcp"
        const val AIRPLAY = "_airplay._tcp"
        private val SERVICE_TYPES = listOf(RAOP, AIRPLAY)
        private const val RERESOLVE_DELAY_MS = 5_000L

        /** Identity shared by a speaker's two records: RAOP name prefix (MAC) / AirPlay deviceid. */
        private fun identityOf(r: Record): String? = when (r.type) {
            RAOP -> r.name.substringBefore("@", "").takeIf { it.isNotEmpty() }?.uppercase()
            else -> r.txt["deviceid"]?.replace(":", "")?.uppercase()
        }

        private fun merge(records: List<Record>): List<AirPlayDevice> {
            val groups = records.groupBy { identityOf(it) ?: "${it.host}|${it.type}|${it.name}" }
            return groups.map { (identity, recs) ->
                val raop = recs.firstOrNull { it.type == RAOP }
                val ap = recs.firstOrNull { it.type == AIRPLAY }
                val primary = ap ?: raop!!
                val name = ap?.name ?: raop!!.name
                AirPlayDevice(
                    name = name,
                    host = primary.host,
                    port = primary.port,
                    deviceId = primary.txt["pi"] ?: primary.txt["deviceid"] ?: name,
                    publicKey = ap?.txt?.get("pk") ?: raop?.txt?.get("pk"),
                    // RAOP TXT (et, cn, md, am) + AirPlay TXT (features/ft, model, manufacturer)
                    features = (raop?.txt ?: emptyMap()) + (ap?.txt ?: emptyMap()),
                    protocolVersion = if (ap != null) 2 else 1,
                    raopPort = raop?.port,
                    identity = identity,
                )
            }
        }
    }
}
