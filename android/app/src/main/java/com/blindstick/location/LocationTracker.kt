package com.blindstick.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.util.Log
import com.google.android.gms.location.*
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Tracks GPS location using FusedLocationProviderClient.
 * Exposes current [Location] and reverse-geocoded address as [StateFlow]s.
 * Appends location history to location_log.txt in external files dir.
 */
class LocationTracker(private val context: Context) {

    companion object {
        private const val TAG = "LocationTracker"
        private const val LOG_FILENAME = "location_log.txt"
    }

    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)
    private val geocoder = Geocoder(context, Locale.ENGLISH)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)
    private val logFile: File =
        File(context.getExternalFilesDir(null), LOG_FILENAME)

    private val _location = MutableStateFlow<Location?>(null)
    val location: StateFlow<Location?> = _location

    private val _address = MutableStateFlow("")
    val address: StateFlow<String> = _address

    private val locationRequest: LocationRequest = LocationRequest.Builder(
        Priority.PRIORITY_HIGH_ACCURACY, 3_000L
    ).apply {
        setMinUpdateIntervalMillis(1_000L)
        setMinUpdateDistanceMeters(2f)
    }.build()

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            _location.value = loc
            resolveAddress(loc)
            appendToLog(loc)
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        try {
            fusedClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                context.mainLooper
            )
            Log.i(TAG, "Location updates started")
        } catch (e: SecurityException) {
            Log.w(TAG, "Location permission denied: ${e.message}")
        }
    }

    fun stop() {
        fusedClient.removeLocationUpdates(locationCallback)
        Log.i(TAG, "Location updates stopped")
    }

    // -------------------------------------------------------------------

    private fun resolveAddress(loc: Location) {
        try {
            @Suppress("DEPRECATION")
            val results = geocoder.getFromLocation(loc.latitude, loc.longitude, 1)
            if (!results.isNullOrEmpty()) {
                _address.value = results[0].getAddressLine(0) ?: ""
            }
        } catch (e: Exception) {
            Log.w(TAG, "Geocoder error: ${e.message}")
        }
    }

    private fun appendToLog(loc: Location) {
        try {
            val addr = _address.value.ifBlank { "N/A" }
            val line = "[${dateFormat.format(Date())}] LAT: %.4f LON: %.4f ADDR: %s\n"
                .format(loc.latitude, loc.longitude, addr)
            logFile.appendText(line)
        } catch (e: Exception) {
            Log.w(TAG, "Log write error: ${e.message}")
        }
    }
}
