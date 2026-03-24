package com.blindstick.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/**
 * OpenCV-based image processing pipeline.
 * Steps:
 *  1. Decode JPEG → Bitmap
 *  2. Bitmap → Mat (RGBA)
 *  3. Grayscale
 *  4. Gaussian Blur (5×5)
 *  5. Canny edge detection (50, 150)
 *  6. Dilate (3×3 kernel, 1 iteration)
 *  7. Find external contours
 *  8. Draw top-5 contours by area in green on original image
 *  9. Return annotated Bitmap
 */
object ImageProcessor {

    private const val TAG = "ImageProcessor"

    /**
     * Process a JPEG [ByteArray] and return an annotated [Bitmap].
     * Returns the decoded original bitmap on any processing error.
     */
    fun process(jpeg: ByteArray): Bitmap {
        val original: Bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            ?: return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

        return try {
            // 1. Bitmap → RGBA Mat
            val rgbaMat = Mat()
            Utils.bitmapToMat(original, rgbaMat)

            // 2. Grayscale
            val gray = Mat()
            Imgproc.cvtColor(rgbaMat, gray, Imgproc.COLOR_RGBA2GRAY)

            // 3. Gaussian Blur
            val blurred = Mat()
            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)

            // 4. Canny edge detection
            val edges = Mat()
            Imgproc.Canny(blurred, edges, 50.0, 150.0)

            // 5. Dilate
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
            val dilated = Mat()
            Imgproc.dilate(edges, dilated, kernel, Point(-1.0, -1.0), 1)

            // 6. Find contours
            val contours = mutableListOf<MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(
                dilated, contours, hierarchy,
                Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
            )

            // 7. Sort by area descending and pick top 5
            contours.sortByDescending { Imgproc.contourArea(it) }
            val top5 = contours.take(5)

            // 8. Draw top-5 contours on original RGBA mat
            val green = Scalar(0.0, 255.0, 0.0, 255.0)
            for (i in top5.indices) {
                Imgproc.drawContours(rgbaMat, top5, i, green, 2)
            }

            // 9. Mat → Bitmap
            val result = original.copy(Bitmap.Config.ARGB_8888, true)
            Utils.matToBitmap(rgbaMat, result)

            // Release native resources
            rgbaMat.release(); gray.release(); blurred.release()
            edges.release(); dilated.release(); kernel.release()
            hierarchy.release()
            contours.forEach { it.release() }

            result
        } catch (e: Exception) {
            android.util.Log.e(TAG, "OpenCV pipeline error: ${e.message}")
            original
        }
    }
}
