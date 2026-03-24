package com.blindstick.fusion

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Fuses label + distance into a human-readable alert string.
 * "Path clear" is throttled to once every 10 seconds.
 */
object FusionEngine {

    private const val PATH_CLEAR_INTERVAL_MS = 10_000L
    private var _lastPathClearTime = 0L

    private val _alertText = MutableStateFlow("")
    val alertText: StateFlow<String> = _alertText

    /**
     * @param label    Object label from TFLite (e.g. "car", "person")
     * @param distance Distance from ultrasonic sensor in metres
     * @return         Alert string to speak / display
     */
    fun evaluate(label: String, distance: Float): String {
        val alert = when {
            distance < 0.5f -> "Warning! $label very close"
            distance <= 1.5f -> "$label ahead, ${String.format("%.1f", distance)} meters"
            else -> {
                val now = System.currentTimeMillis()
                if (now - _lastPathClearTime >= PATH_CLEAR_INTERVAL_MS) {
                    _lastPathClearTime = now
                    "Path clear"
                } else {
                    ""
                }
            }
        }
        _alertText.value = alert
        return alert
    }
}
