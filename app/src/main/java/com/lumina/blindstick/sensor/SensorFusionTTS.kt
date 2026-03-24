package com.lumina.blindstick.sensor

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale
import com.lumina.blindstick.vision.Detection

class SensorFusionTTS(context: Context) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private val lastSpokenMap = mutableMapOf<String, Long>()

    init {
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            isInitialized = true
        }
    }

    fun process(distance: Float, detections: List<Detection>) {
        if (!isInitialized || detections.isEmpty()) return

        val now = System.currentTimeMillis()

        for (detection in detections) {
            val labelStr = "${detection.label} ${detection.direction}"
            // Format: [Label] [Direction], [Distance] meters.
            val statement = "$labelStr, $distance meters."
            
            val lastSpoken = lastSpokenMap[labelStr] ?: 0L

            if (distance < 0.5f) {
                // Urgent case: QUEUE_FLUSH
                lastSpokenMap[labelStr] = now
                tts?.speak("Urgent! $statement", TextToSpeech.QUEUE_FLUSH, null, null)
                // Break to avoid rapidly speaking multiple items if there are many objects < 0.5m
                break
            } else {
                // Throttle 3 seconds: QUEUE_ADD
                if (now - lastSpoken > 3000L) {
                    lastSpokenMap[labelStr] = now
                    tts?.speak(statement, TextToSpeech.QUEUE_ADD, null, null)
                }
            }
        }
    }
    
    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
    }
}
