package com.lumina.blindstick.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

class LocationTracker(private val context: Context) {
    private val fusedLocationClient: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)
    private val geocoder = Geocoder(context, Locale.getDefault())
    private val logFile = File(context.filesDir, "location_log.txt")
    private val scope = CoroutineScope(Dispatchers.IO)

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            for (location in locationResult.locations) {
                logLocation(location.latitude, location.longitude)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startTracking() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000)
            .setMinUpdateIntervalMillis(3000)
            .build()
            
        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
    }

    fun stopTracking() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    private fun logLocation(lat: Double, lng: Double) {
        scope.launch {
            try {
                // Reverse geocoding
                val addresses = geocoder.getFromLocation(lat, lng, 1)
                val streetName = if (!addresses.isNullOrEmpty()) addresses[0].getAddressLine(0) else "Unknown Address"
                
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                val timestamp = sdf.format(Date())
                
                val logEntry = "$timestamp, Lat: $lat, Lng: $lng, Address: $streetName\n"
                
                FileWriter(logFile, true).use {
                    it.append(logEntry)
                }
                
            } catch (e: Exception) {
                Log.e("LocationTracker", "Error logging location", e)
            }
        }
    }
}
