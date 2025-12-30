package com.airplay.streamer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.airplay.streamer.MainActivity
import com.airplay.streamer.R
import com.airplay.streamer.raop.RaopClient
import com.airplay.streamer.util.LogServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that captures system audio using MediaProjection/AudioPlaybackCapture
 * and streams it to an AirPlay speaker via RAOP
 */
class AudioCaptureService : Service() {
    companion object {
        private const val TAG = "AudioCaptureService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "airplay_streaming"

        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val FRAMES_PER_PACKET = 352
        private const val BYTES_PER_FRAME = 4 // 16-bit stereo = 4 bytes
        private const val BUFFER_SIZE = FRAMES_PER_PACKET * BYTES_PER_FRAME

        const val ACTION_START = "com.airplay.streamer.START"
        const val ACTION_STOP = "com.airplay.streamer.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_DEVICE_NAME = "device_name"

        // Singleton for accessing streaming state
        var instance: AudioCaptureService? = null
            private set
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var raopClient: RaopClient? = null
    private var airPlay2Client: com.airplay.streamer.raop.AirPlay2Client? = null
    private var captureJob: Job? = null

    private var isCapturing = false
    private var deviceName: String = "AirPlay Speaker"

    // Callback for UI updates
    var onStateChanged: ((Boolean) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onDestroy() {
        stopCapture()
        serviceScope.cancel()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                val host = intent.getStringExtra(EXTRA_HOST) ?: return START_NOT_STICKY
                val port = intent.getIntExtra(EXTRA_PORT, 0)
                deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: "AirPlay Speaker"

                if (resultData != null) {
                    startCapture(resultCode, resultData, host, port)
                }
            }
            ACTION_STOP -> {
                stopCapture()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startCapture(resultCode: Int, resultData: Intent, host: String, port: Int) {
        if (isCapturing) return

        // Start foreground with notification
        startForeground(NOTIFICATION_ID, createNotification())

        serviceScope.launch {
            try {
                // Get MediaProjection
                val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) 
                    as MediaProjectionManager
                mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

                if (mediaProjection == null) {
                    stopSelf()
                    return@launch
                }

                // Get protocol preference from SharedPreferences
                val prefs = getSharedPreferences("airplay_prefs", MODE_PRIVATE)
                val protocolPref = prefs.getInt("protocol_preference", 0) // 0=Auto, 1=v1, 2=v2
                
                // Determine which protocol to use
                val useV2 = when (protocolPref) {
                    1 -> false // Force v1
                    2 -> true  // Force v2
                    else -> port == 7000 // Auto: use v2 if port is 7000
                }
                
                LogServer.log("Protocol preference: $protocolPref, Using AirPlay ${if (useV2) "v2" else "v1"}")

                if (useV2) {
                    // AirPlay 2 Path
                    LogServer.log("Starting AirPlay 2 connection to $host:$port")
                    val device = com.airplay.streamer.discovery.AirPlayDevice(
                        deviceName, host, port, "deviceid", null, emptyMap()
                    )
                    
                    val client = com.airplay.streamer.raop.AirPlay2Client(device)
                    airPlay2Client = client
                    
                    client.connect()
                    LogServer.log("Connected. Attempting Pair-Setup...")
                    
                    try {
                        client.pair("0000") // Default PIN - TODO: make configurable
                        LogServer.log("AirPlay 2 Pair-Setup Successful!")
                        
                        // Try to set up audio stream
                        LogServer.log("Setting up audio stream...")
                        val serverPort = client.setupAudioStream()
                        LogServer.log("Audio stream ready on server port $serverPort")
                        
                        // Start audio capture for v2
                        val captureStarted = tryAudioPlaybackCapture()
                        if (captureStarted) {
                            isCapturing = true
                            onStateChanged?.invoke(true)
                            LogServer.log("Audio capture started for AirPlay 2")
                            startAirPlay2StreamLoop(client)
                        } else {
                            LogServer.log("Audio capture failed for AirPlay 2")
                            stopCapture()
                        }
                    } catch (e: Exception) {
                        LogServer.log("AirPlay 2 error: ${e.message}")
                        // Pairing or stream setup failed, but connection is ok
                        onStateChanged?.invoke(false)
                    }
                    return@launch
                }

                // AirPlay 1 (RAOP) Path
                LogServer.log("Starting AirPlay 1 (RAOP) connection to $host:$port")
                raopClient = RaopClient(host, port)
                val connected = raopClient?.connect() ?: false

                if (!connected) {
                    LogServer.log("Failed to connect to RAOP server")
                    stopCapture()
                    return@launch
                }

                LogServer.log("RAOP connection established, setting initial volume")
                // Set initial volume
                raopClient?.setVolume(0.8f)

                // Try AudioPlaybackCapture
                val captureStarted = tryAudioPlaybackCapture()

                if (captureStarted) {
                    isCapturing = true
                    onStateChanged?.invoke(true)
                    LogServer.log("Audio capture started, beginning stream loop")
                    startAudioStreamLoop()
                } else {
                    LogServer.log("Audio capture failed (possibly DRM blocked)")
                    stopCapture()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                LogServer.log("Streaming/Pairing Error: ${e.message}")
                stopCapture()
            }
        }
    }

    private fun tryAudioPlaybackCapture(): Boolean {
        return try {
            val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setEncoding(AUDIO_FORMAT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()

            val bufferSize = maxOf(
                AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT),
                BUFFER_SIZE * 4
            )

            audioRecord = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .build()

            if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                audioRecord?.startRecording()
                true
            } else {
                audioRecord?.release()
                audioRecord = null
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun startAudioStreamLoop() {
        captureJob = serviceScope.launch(Dispatchers.IO) {
            val buffer = ByteArray(BUFFER_SIZE)

            while (isActive && isCapturing) {
                val bytesRead = audioRecord?.read(buffer, 0, BUFFER_SIZE) ?: -1

                if (bytesRead > 0) {
                    raopClient?.streamAudio(buffer.copyOf(bytesRead))
                } else if (bytesRead < 0) {
                    // Error reading audio
                    break
                }
            }
        }
    }

    private fun startAirPlay2StreamLoop(client: com.airplay.streamer.raop.AirPlay2Client) {
        captureJob = serviceScope.launch(Dispatchers.IO) {
            val buffer = ByteArray(BUFFER_SIZE)
            var packetCount = 0L

            while (isActive && isCapturing) {
                val bytesRead = audioRecord?.read(buffer, 0, BUFFER_SIZE) ?: -1

                if (bytesRead > 0) {
                    // For AirPlay 2, we'd need to format as buffered audio packets
                    // For now, just send raw data (will need RTP wrapping for real implementation)
                    client.streamAudio(buffer.copyOf(bytesRead))
                    packetCount++
                    
                    // Log progress periodically
                    if (packetCount % 1000 == 0L) {
                        com.airplay.streamer.util.LogServer.log("AirPlay 2: Sent $packetCount packets")
                    }
                } else if (bytesRead < 0) {
                    // Error reading audio
                    com.airplay.streamer.util.LogServer.log("AirPlay 2: Audio read error")
                    break
                }
            }
            com.airplay.streamer.util.LogServer.log("AirPlay 2: Stream loop ended")
        }
    }

    private fun stopCapture() {
        isCapturing = false
        captureJob?.cancel()
        captureJob = null

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        serviceScope.launch {
            raopClient?.disconnect()
            raopClient = null
            
            airPlay2Client?.disconnect()
            airPlay2Client = null
        }

        mediaProjection?.stop()
        mediaProjection = null

        onStateChanged?.invoke(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "AirPlay Streaming",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when streaming to AirPlay speaker"
        }
        
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, AudioCaptureService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.streaming_notification_title))
            .setContentText(getString(R.string.streaming_notification_text, deviceName))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.stop_streaming), stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    fun isCurrentlyStreaming(): Boolean = isCapturing
}
