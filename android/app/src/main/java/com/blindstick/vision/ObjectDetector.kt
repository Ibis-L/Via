package com.blindstick.vision

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * MobileNet V1 1.0 224 quantized TFLite object detector.
 * Model: assets/mobilenet_v1_1.0_224_quant.tflite
 * Labels: assets/labels.txt  (1000 ImageNet lines)
 *
 * Returns Pair(label, confidence). Falls back to ("obstacle", 1.0f) when
 * confidence < 60%.
 */
class ObjectDetector(private val context: Context) {

    companion object {
        private const val TAG = "ObjectDetector"
        private const val MODEL_FILE = "mobilenet_v1_1.0_224_quant.tflite"
        private const val LABELS_FILE = "labels.txt"
        private const val INPUT_SIZE = 224
        private const val CONFIDENCE_THRESHOLD = 0.60f
        // Quantised model: pixel value byte → float via / 255f
        private const val IMAGE_MEAN = 0f
        private const val IMAGE_STD = 255f
    }

    private val interpreter: Interpreter by lazy { loadInterpreter() }
    private val labels: List<String> by lazy { loadLabels() }

    // -------------------------------------------------------------------

    /**
     * Run inference on a [Bitmap].
     * @return Pair(label, confidence)
     */
    fun classify(bitmap: Bitmap): Pair<String, Float> {
        return try {
            val resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
            val inputBuffer = bitmapToByteBuffer(resized)

            // Output: 1 × 1001 float array (index 0 = background)
            val output = Array(1) { FloatArray(1001) }
            interpreter.run(inputBuffer, output)

            val scores = output[0]
            // Search indices 1..1000 (skip background at 0)
            var maxIdx = 1
            var maxScore = scores[1]
            for (i in 2..1000) {
                if (scores[i] > maxScore) {
                    maxScore = scores[i]
                    maxIdx = i
                }
            }

            val label = if (maxIdx - 1 < labels.size) labels[maxIdx - 1] else "object"
            if (maxScore >= CONFIDENCE_THRESHOLD) {
                Pair(label, maxScore)
            } else {
                Pair("obstacle", 1.0f)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Inference error: ${e.message}")
            Pair("obstacle", 1.0f)
        }
    }

    fun close() {
        interpreter.close()
    }

    // -------------------------------------------------------------------

    private fun loadInterpreter(): Interpreter {
        val model: MappedByteBuffer = loadModelFile()
        val options = Interpreter.Options().apply {
            setNumThreads(2)
        }
        return Interpreter(model, options)
    }

    private fun loadModelFile(): MappedByteBuffer {
        val assetFd = context.assets.openFd(MODEL_FILE)
        val inputStream = FileInputStream(assetFd.fileDescriptor)
        val channel: FileChannel = inputStream.channel
        return channel.map(FileChannel.MapMode.READ_ONLY, assetFd.startOffset, assetFd.declaredLength)
    }

    private fun loadLabels(): List<String> =
        context.assets.open(LABELS_FILE).bufferedReader().readLines().map { it.trim() }

    private fun bitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        // For quantised model: use UINT8 (1 byte per channel)
        val buf = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3)
        buf.order(ByteOrder.nativeOrder())
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (pixel in pixels) {
            buf.put(((pixel shr 16) and 0xFF).toByte()) // R
            buf.put(((pixel shr 8) and 0xFF).toByte())  // G
            buf.put((pixel and 0xFF).toByte())           // B
        }
        buf.rewind()
        return buf
    }
}
