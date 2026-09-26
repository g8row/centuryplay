package com.airplay.streamer.util

import android.util.Log
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

/**
 * Simple HTTP server that serves logs for debugging.
 * Access at http://<phone-ip>:8080
 */
object LogServer {
    private const val TAG = "LogServer"
    private const val PORT = 8080
    private const val MAX_LOGS = 500
    
    private val logs = ConcurrentLinkedQueue<String>()
    private var serverSocket: ServerSocket? = null
    private var running = false
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    
    fun start() {
        if (running) return
        running = true
        
        thread(name = "LogServer") {
            try {
                serverSocket = ServerSocket(PORT)
                log("LogServer started on port $PORT")
                
                while (running) {
                    try {
                        val client = serverSocket?.accept() ?: break
                        handleClient(client)
                    } catch (e: Exception) {
                        if (running) log("Client error: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                log("Server error: ${e.message}")
            }
        }
    }
    
    fun stop() {
        running = false
        serverSocket?.close()
        serverSocket = null
    }
    
    fun log(message: String) {
        Log.d(TAG, message)
        // SimpleDateFormat isn't thread-safe and ConcurrentLinkedQueue.size is O(n): guard both.
        synchronized(logs) {
            logs.add("[${dateFormat.format(Date())}] $message")
            while (logs.size > MAX_LOGS) logs.poll()
        }
    }

    /** Snapshot of the buffered log lines, oldest first. */
    fun snapshot(): List<String> = synchronized(logs) { logs.toList() }
    
    fun d(tag: String, message: String) {
        log("D/$tag: $message")
    }
    
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        log("E/$tag: $message")
        throwable?.let { log("E/$tag: ${it.stackTraceToString()}") }
    }
    
    private fun handleClient(client: Socket) {
        try {
            val writer = PrintWriter(client.getOutputStream(), true)
            
            // GET /text returns plain text (newest last) for scripts; anything else the HTML view.
            val requestLine = client.getInputStream().bufferedReader().readLine() ?: ""
            val plain = requestLine.contains(" /text")
            val html = if (plain) snapshot().joinToString("\n") else buildHtml()
            writer.print("HTTP/1.1 200 OK\r\n")
            writer.print("Content-Type: ${if (plain) "text/plain" else "text/html"}; charset=utf-8\r\n")
            writer.print("Content-Length: ${html.toByteArray().size}\r\n")
            writer.print("Connection: close\r\n")
            writer.print("\r\n")
            writer.print(html)
            writer.flush()
            
            client.close()
        } catch (e: Exception) {
            // Ignore
        }
    }
    
    private fun buildHtml(): String {
        val logContent = snapshot().reversed().joinToString("\n") { escapeHtml(it) }
        
        return """
<!DOCTYPE html>
<html>
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <meta http-equiv="refresh" content="2">
    <title>centuryplay logs</title>
    <style>
        body { 
            background: #1e1e1e; 
            color: #d4d4d4; 
            font-family: 'Consolas', 'Monaco', monospace; 
            font-size: 12px;
            margin: 0; 
            padding: 10px; 
        }
        h1 { 
            color: #569cd6; 
            margin: 0 0 10px 0; 
            font-size: 16px;
        }
        pre { 
            white-space: pre-wrap; 
            word-wrap: break-word; 
            margin: 0;
            line-height: 1.4;
        }
        .error { color: #f44747; }
        .debug { color: #9cdcfe; }
        .info { color: #4ec9b0; }
    </style>
</head>
<body>
    <h1>🔊 centuryplay logs (auto-refresh 2s)</h1>
    <pre>$logContent</pre>
</body>
</html>
        """.trimIndent()
    }
    
    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .let { line ->
                when {
                    line.contains("E/") -> "<span class=\"error\">$line</span>"
                    line.contains("D/") -> "<span class=\"debug\">$line</span>"
                    else -> line
                }
            }
    }
}
