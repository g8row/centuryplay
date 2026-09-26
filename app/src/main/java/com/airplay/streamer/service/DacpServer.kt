package com.airplay.streamer.service

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.airplay.streamer.util.LogServer
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * DACP ("Digital Audio Control Protocol") endpoint so receivers can control us: AV receivers'
 * remotes, shairport-sync's MPRIS/D-Bus remote control, Apple remotes paired to a receiver.
 *
 * Every RTSP request carries `DACP-ID` + `Active-Remote`; the receiver looks up
 * `iTunes_Ctrl_<DACP-ID>._dacp._tcp` via mDNS and sends `GET /ctrl-int/1/<command>` with the
 * `Active-Remote` header (shairport-sync dacp.c, OwnTone, the unofficial AirPlay spec).
 */
object DacpServer {
    /** 64-bit hex id shared by all sessions of this process. */
    val dacpId: String = "%016X".format(Random.nextLong())
    /** 32-bit decimal token receivers must echo (some can't handle larger values). */
    val activeRemote: String = Random.nextLong(1, 0xFFFFFFFFL).toString()

    interface Handler {
        fun onCommand(command: String, query: String?)
    }

    @Volatile var handler: Handler? = null
    private var server: ServerSocket? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var nsd: NsdManager? = null

    val isRunning: Boolean get() = server != null

    @Synchronized
    fun start(context: Context) {
        if (server != null) return
        val s = ServerSocket(0)
        server = s
        thread(name = "DacpServer", isDaemon = true) {
            while (!s.isClosed) {
                val client = try { s.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) { handle(client) }
            }
        }
        val info = NsdServiceInfo().apply {
            serviceName = "iTunes_Ctrl_$dacpId"
            serviceType = "_dacp._tcp"
            port = s.localPort
            setAttribute("txtvers", "1")
            setAttribute("Ver", "131077")
            setAttribute("DbId", dacpId)
            setAttribute("OSsi", "0x1F5")
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                LogServer.log("DACP: advertised ${serviceInfo.serviceName} on port ${s.localPort} (Active-Remote $activeRemote)")
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                LogServer.log("DACP: mDNS registration failed ($errorCode)")
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        val manager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
        nsd = manager
        registration = listener
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    @Synchronized
    fun stop() {
        registration?.let { r -> runCatching { nsd?.unregisterService(r) } }
        registration = null
        runCatching { server?.close() }
        server = null
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 5000
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
            val requestLine = reader.readLine() ?: return
            var remote: String? = null
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i > 0 && line.substring(0, i).trim().equals("Active-Remote", ignoreCase = true)) {
                    remote = line.substring(i + 1).trim()
                }
            }
            val path = requestLine.split(" ").getOrNull(1) ?: ""
            val ok = remote == activeRemote && path.startsWith("/ctrl-int/1/")
            if (ok) {
                val cmd = path.removePrefix("/ctrl-int/1/").substringBefore("?")
                val query = path.substringAfter("?", "").ifEmpty { null }
                LogServer.log("DACP: $cmd${query?.let { "?$it" } ?: ""}")
                runCatching { handler?.onCommand(cmd, query) }
            } else {
                LogServer.log("DACP: rejected $path (Active-Remote=$remote)")
            }
            val status = if (ok) "204 No Content" else "403 Forbidden"
            s.getOutputStream().write("HTTP/1.1 $status\r\nDAAP-Server: centuryplay\r\nContent-Length: 0\r\n\r\n".toByteArray())
        }
    }
}
