package com.blindstick

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.blindstick.model.ProcessedFrame
import com.blindstick.network.SocketClient
import com.blindstick.service.BlindStickService
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import android.Manifest

/**
 * Main activity:
 * - Binds to [BlindStickService]
 * - Displays live annotated camera feed
 * - Shows connection status dot, address, alert text, status bar
 * - Floating button to open CaregiverActivity
 */
class MainActivity : AppCompatActivity() {

    private var blindStickService: BlindStickService? = null
    private var isBound = false

    private lateinit var connectionDot: android.view.View
    private lateinit var connectionStatus: TextView
    private lateinit var locationText: TextView
    private lateinit var cameraFeed: ImageView
    private lateinit var detectionLabel: TextView
    private lateinit var statusBar: android.view.View
    private lateinit var alertText: TextView
    private lateinit var caregiverButton: FloatingActionButton

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            blindStickService = (service as BlindStickService.LocalBinder).getService()
            isBound = true
            observeService()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            blindStickService = null
            isBound = false
        }
    }

    // --- Permission launcher ---
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val denied = grants.filter { !it.value }.keys
        if (denied.isNotEmpty()) {
            Toast.makeText(this, "Some permissions denied — app may work partially.", Toast.LENGTH_LONG).show()
        }
    }

    // -------------------------------------------------------------------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        requestPermissions()
        startAndBindService()
        setupCaregiverButton()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    // -------------------------------------------------------------------
    private fun bindViews() {
        connectionDot    = findViewById(R.id.connectionDot)
        connectionStatus = findViewById(R.id.connectionStatus)
        locationText     = findViewById(R.id.locationText)
        cameraFeed       = findViewById(R.id.cameraFeed)
        detectionLabel   = findViewById(R.id.detectionLabel)
        statusBar        = findViewById(R.id.statusBar)
        alertText        = findViewById(R.id.alertText)
        caregiverButton  = findViewById(R.id.caregiverButton)
    }

    private fun requestPermissions() {
        permissionLauncher.launch(arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS
        ))
    }

    private fun startAndBindService() {
        val intent = Intent(this, BlindStickService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun setupCaregiverButton() {
        caregiverButton.setOnClickListener {
            startActivity(Intent(this, CaregiverActivity::class.java))
        }
    }

    private fun observeService() {
        val service = blindStickService ?: return

        // Connection state
        lifecycleScope.launch {
            SocketClient.isConnected.collectLatest { connected ->
                runOnUiThread {
                    connectionDot.setBackgroundResource(
                        if (connected) R.drawable.circle_green else R.drawable.circle_red
                    )
                    connectionStatus.text = if (connected) "Connected" else "Connecting…"
                }
            }
        }

        // Address
        lifecycleScope.launch {
            service.let { svc ->
                // Access LocationTracker via reflection-free: we rely on the processed frame location
            }
        }

        // Processed frames
        lifecycleScope.launch {
            service.getProcessedFrameFlow().collectLatest { frame ->
                frame ?: return@collectLatest
                runOnUiThread { updateUI(frame) }
            }
        }
    }

    private fun updateUI(frame: ProcessedFrame) {
        cameraFeed.setImageBitmap(frame.bitmap)
        detectionLabel.text = "${frame.label}  ${String.format("%.1f", frame.distance)}m"
        alertText.text = frame.alertText

        // Status bar color by distance
        val color = when {
            frame.distance < 0.5f  -> Color.parseColor("#FF3B30")   // Red
            frame.distance <= 1.5f -> Color.parseColor("#FF9500")   // Orange
            else                   -> Color.parseColor("#34C759")   // Green
        }
        statusBar.setBackgroundColor(color)
    }
}
