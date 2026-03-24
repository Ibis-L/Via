package com.lumina.blindstick

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.lumina.blindstick.services.BlindStickService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var cameraPreview: ImageView
    private lateinit var logText: TextView
    private lateinit var startServiceBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        cameraPreview = findViewById(R.id.cameraPreview)
        logText = findViewById(R.id.logText)
        startServiceBtn = findViewById(R.id.startServiceBtn)

        requestPermissions()

        startServiceBtn.setOnClickListener {
            val intent = Intent(this, BlindStickService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }

        // Collect UI StateFlows concurrently
        lifecycleScope.launch {
            BlindStickService.latestFrame.collectLatest { bitmap ->
                if (bitmap != null) {
                    cameraPreview.setImageBitmap(bitmap)
                }
            }
        }

        lifecycleScope.launch {
            BlindStickService.latestLog.collectLatest { log ->
                logText.text = "Status: $log"
            }
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 100)
        }
    }
}
