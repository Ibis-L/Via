package com.blindstick.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Starts [BlindStickService] automatically after device boot.
 * Requires RECEIVE_BOOT_COMPLETED permission in AndroidManifest.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val serviceIntent = Intent(context, BlindStickService::class.java)
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }
}
