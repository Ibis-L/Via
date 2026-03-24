package com.blindstick.model

import android.graphics.Bitmap
import com.google.android.gms.maps.model.LatLng

/**
 * Raw packet received from the Raspberry Pi over TCP.
 */
data class ParsedFrame(
    val jpeg: ByteArray,
    val distance: Float,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ParsedFrame) return false
        return distance == other.distance && jpeg.contentEquals(other.jpeg)
    }

    override fun hashCode(): Int {
        var result = jpeg.contentHashCode()
        result = 31 * result + distance.hashCode()
        return result
    }
}

/**
 * Fully processed frame after the CV + TFLite + sensor-fusion pipeline.
 */
data class ProcessedFrame(
    val bitmap: Bitmap,
    val label: String,
    val distance: Float,
    val alertText: String,
    val location: LatLng?,
)
