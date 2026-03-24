package com.blindstick.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Binder
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import com.blindstick.MainActivity
import com.blindstick.fusion.FusionEngine
import com.blindstick.location.LocationTracker
import com.blindstick.model.ParsedFrame
import com.blindstick.model.ProcessedFrame
import com.blindstick.network.SocketClient
import com.blindstick.vision.ImageProcessor
import com.blindstick.vision.ObjectDetector
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Foreground service that owns all subsystems:
 * SocketClient, ImageProcessor, ObjectDetector, FusionEngine, TTS, LocationTracker.
 * Processes incoming frames and exposes [ProcessedFrame] to bound activities.
 */
class BlindStickService : Service() {

    companion object {
        private const val TAG = "BlindStickService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "blind_stick_channel"
        private const val CHANNEL_NAME = "Blind Stick"
        private const val TTS_COOLDOWN_MS = 3_000L
    }

    // --- Binder ---
    inner class LocalBinder : Binder() {
        fun getService(): BlindStickService = this@BlindStickService
    }
    private val binder = LocalBinder()

    // --- Subsystems ---
    private lateinit var objectDetector: ObjectDetector
    private lateinit var locationTracker: LocationTracker
    private lateinit var tts: TextToSpeech

    // --- Coroutine scope ---
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // --- State ---
    private val _processedFrame = MutableStateFlow<ProcessedFrame?>(null)
    val processedFrameFlow: StateFlow<ProcessedFrame?> = _processedFrame

    private var lastSpokenText = ""
    private var lastSpokenTime = 0L

    // --- Notification manager ---
    private lateinit var notificationManager: NotificationManager

    // -------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Initialising …"))

        objectDetector = ObjectDetector(this)
        locationTracker = LocationTracker(this)

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = java.util.Locale.ENGLISH
                tts.setSpeechRate(1.2f)
                tts.setPitch(1.0f)
                Log.i(TAG, "TTS initialised")
            } else {
                Log.e(TAG, "TTS init failed with status $status")
            }
        }

        SocketClient.start()
        locationTracker.start()
        startFramePipeline()

        Log.i(TAG, "BlindStickService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopSelf()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        SocketClient.stop()
        locationTracker.stop()
        objectDetector.close()
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        Log.i(TAG, "BlindStickService destroyed")
    }

    // -------------------------------------------------------------------
    // Public API for bound activities
    // -------------------------------------------------------------------

    fun getProcessedFrameFlow(): StateFlow<ProcessedFrame?> = _processedFrame

    // -------------------------------------------------------------------
    // Frame pipeline
    // -------------------------------------------------------------------

    private fun startFramePipeline() {
        serviceScope.launch {
            SocketClient.latestFrame.collect { frame ->
                frame ?: return@collect
                processFrame(frame)
            }
        }
    }

    private suspend fun processFrame(frame: ParsedFrame) = withContext(Dispatchers.Default) {
        try {
            // 1. OpenCV pipeline
            val annotatedBitmap = ImageProcessor.process(frame.jpeg)

            // 2. TFLite inference on original (unprocessed) bitmap
            val originalBitmap = BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.size)
            val (label, _) = objectDetector.classify(originalBitmap)

            // 3. Sensor fusion
            val alertText = FusionEngine.evaluate(label, frame.distance)

            // 4. Current GPS location
            val loc = locationTracker.location.value
            val latLng = loc?.let { LatLng(it.latitude, it.longitude) }

            // 5. Emit processed frame
            val processed = ProcessedFrame(
                bitmap = annotatedBitmap,
                label = label,
                distance = frame.distance,
                alertText = alertText,
                location = latLng,
            )
            _processedFrame.value = processed

            // 6. Speak alert
            if (alertText.isNotBlank()) {
                speakAlert(alertText, frame.distance)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Frame pipeline error: ${e.message}")
        }
    }

    // -------------------------------------------------------------------
    // TTS
    // -------------------------------------------------------------------

    private fun speakAlert(text: String, distance: Float) {
        val now = System.currentTimeMillis()
        val differsFromLast = text != lastSpokenText
        val cooldownExpired = now - lastSpokenTime >= TTS_COOLDOWN_MS

        if (!differsFromLast && !cooldownExpired) return

        lastSpokenText = text
        lastSpokenTime = now

        val queueMode = if (distance < 0.5f) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts.speak(text, queueMode, null, "alert_${now}")

        // Update notification with latest alert
        notificationManager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    // -------------------------------------------------------------------
    // Notification helpers
    // -------------------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        )
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(contentText: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, BlindStickService::class.java).apply { action = "STOP" },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Blind Stick Active")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_delete, "Stop", stopIntent)
            .setOngoing(true)
            .build()
    }
}
