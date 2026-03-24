package com.blindstick

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.blindstick.service.BlindStickService
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * Caregiver map activity.
 * Binds to [BlindStickService] to observe location + processed frames.
 * Shows Google Maps with live user marker and details panel below.
 */
class CaregiverActivity : AppCompatActivity(), OnMapReadyCallback {

    private var blindStickService: BlindStickService? = null
    private var isBound = false
    private var googleMap: GoogleMap? = null
    private var firstFix = true
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.ENGLISH)

    private lateinit var tvAddress: TextView
    private lateinit var tvLatLng: TextView
    private lateinit var tvLastAlert: TextView
    private lateinit var tvLastUpdate: TextView
    private lateinit var btnShare: android.widget.Button

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_caregiver)

        tvAddress    = findViewById(R.id.tvAddress)
        tvLatLng     = findViewById(R.id.tvLatLng)
        tvLastAlert  = findViewById(R.id.tvLastAlert)
        tvLastUpdate = findViewById(R.id.tvLastUpdate)
        btnShare     = findViewById(R.id.btnShare)

        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.mapFragment) as SupportMapFragment
        mapFragment.getMapAsync(this)

        val intent = Intent(this, BlindStickService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) { unbindService(serviceConnection); isBound = false }
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        map.uiSettings.isZoomControlsEnabled = true
    }

    // -------------------------------------------------------------------

    private fun observeService() {
        val service = blindStickService ?: return

        // Processed frame → location + alert
        lifecycleScope.launch {
            service.getProcessedFrameFlow().collectLatest { frame ->
                frame ?: return@collectLatest
                val latLng = frame.location ?: return@collectLatest
                runOnUiThread {
                    updateMap(latLng)
                    tvLastAlert.text = "Last alert: ${frame.alertText}"
                    tvLastUpdate.text = "Updated: ${timeFormat.format(Date())}"
                    setupShareButton(latLng)
                }
            }
        }
    }

    private fun updateMap(latLng: LatLng) {
        val map = googleMap ?: return
        map.clear()
        map.addMarker(MarkerOptions().position(latLng).title("User Location"))
        tvLatLng.text = "%.4f, %.4f".format(latLng.latitude, latLng.longitude)

        if (firstFix) {
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 16f))
            firstFix = false
        } else {
            map.animateCamera(CameraUpdateFactory.newLatLng(latLng))
        }
    }

    private fun setupShareButton(latLng: LatLng) {
        btnShare.setOnClickListener {
            val url = "https://maps.google.com/?q=${latLng.latitude},${latLng.longitude}"
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, url)
            }
            startActivity(Intent.createChooser(shareIntent, "Share Location"))
        }
    }
}
