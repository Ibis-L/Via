package com.lumina.blindstick.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.lumina.blindstick.location.LocationTracker
import com.lumina.blindstick.sensor.SensorFusionTTS
import com.lumina.blindstick.vision.VisionProcessor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket

class BlindStickService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    
    private lateinit var visionProcessor: VisionProcessor
    private lateinit var sensorFusionTTS: SensorFusionTTS
    private lateinit var locationTracker: LocationTracker

    companion object {
        val latestFrame = MutableStateFlow<Bitmap?>(null)
        val latestLog = MutableStateFlow<String>("Service started")
        const val STOP_ACTION = "STOP_SERVICE"
        const val CHANNEL_ID = "BlindStickChannel"
    }

    override fun onCreate() {
        super.onCreate()
        visionProcessor = VisionProcessor(this)
        sensorFusionTTS = SensorFusionTTS(this)
        locationTracker = LocationTracker(this)
        
        startForegroundService()
        locationTracker.startTracking()
        isRunning = true
        startTcpServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP_ACTION) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        serverSocket?.close()
        scope.cancel()
        sensorFusionTTS.shutdown()
        locationTracker.stopTracking()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundService() {
        val stopIntent = Intent(this, BlindStickService::class.java).apply {
            action = STOP_ACTION
        }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Lumina BlindStick Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Lumina BlindStick Active")
            .setContentText("Listening on Port 5000")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .setOngoing(true)
            .build()

        startForeground(1, notification)
    }

    private fun startTcpServer() {
        scope.launch {
            while (isRunning) {
                try {
                    serverSocket = ServerSocket(5000)
                    latestLog.value = "Waiting for Pi on port 5000..."
                    // Block until a connection arrives
                    val socket = serverSocket!!.accept()
                    latestLog.value = "Pi Connected!"
                    handleClient(socket)
                } catch (e: Exception) {
                    if (isRunning) {
                        latestLog.value = "Server crashed, restarting: ${e.message}"
                        delay(2000) // Recursive/loop reconnect strategy
                    }
                } finally {
                    try { serverSocket?.close() } catch (e: Exception) {}
                }
            }
        }
    }

    private suspend fun handleClient(socket: Socket) {
        withContext(Dispatchers.IO) {
            try {
                val input = DataInputStream(socket.getInputStream())
                while (isRunning && !socket.isClosed) {
                    // Strict Binary format: [4 bytes: img_size][JPEG bytes][4 bytes: float distance]
                    val imgSize = input.readInt()
                    if (imgSize <= 0 || imgSize > 5000000) {
                        throw Exception("Invalid image size: $imgSize")
                    }
                    
                    val jpegBytes = ByteArray(imgSize)
                    input.readFully(jpegBytes)
                    
                    val distance = input.readFloat()

                    // Start Processing
                    val resultBitmap = visionProcessor.processFrame(jpegBytes)
                    latestFrame.value = resultBitmap

                    val detections = visionProcessor.detectObjects(resultBitmap)
                    
                    // Fusion
                    sensorFusionTTS.process(distance, detections)
                }
            } catch (e: Exception) {
                latestLog.value = "Client disconnected: ${e.message}"
            } finally {
                withContext(NonCancellable) {
                    try { socket.close() } catch (e: Exception) {}
                }
            }
        }
    }
}
