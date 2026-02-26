package com.example.localllmapp.photos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Detects faces and produces embeddings using a bundled TFLite model (to be provided by the user).
 */
object FaceEmbeddingHelper {
    private const val TAG = "FaceEmbeddingHelper"
    private var interpreter: Interpreter? = null

    fun loadModel(context: Context, assetPath: String = "face_embedding.tflite"): Boolean {
        return try {
            if (interpreter != null) return true
            val fd = context.assets.openFd(assetPath)
            val input = fd.createInputStream().channel
            val mapped = input.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, fd.length)
            interpreter = Interpreter(mapped)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load face model", e)
            false
        }
    }

    fun detectFaces(bitmap: Bitmap, highAccuracy: Boolean = false, onResult: (List<Face>) -> Unit) {
        val options = if (highAccuracy) {
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .build()
        } else {
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .build()
        }
        val detector = FaceDetection.getClient(options)
        val image = InputImage.fromBitmap(bitmap, 0)
        detector.process(image)
            .addOnSuccessListener { faces -> onResult(faces) }
            .addOnFailureListener { _ -> onResult(emptyList()) }
            .addOnCompleteListener { detector.close() }
    }

    fun embedCroppedFace(context: Context, bitmap: Bitmap, box: Rect): FloatArray? {
        val faceBitmap = crop(bitmap, box)
        if (interpreter == null && !loadModel(context)) return null
        val input = preprocess(faceBitmap, 112, 112)
        val output = Array(1) { FloatArray(128) }
        return try {
            interpreter?.run(input, output)
            l2normalize(output[0])
        } catch (e: Exception) {
            Log.e(TAG, "Embedding failed", e)
            null
        }
    }

    private fun crop(src: Bitmap, rect: Rect): Bitmap {
        val x = rect.left.coerceAtLeast(0)
        val y = rect.top.coerceAtLeast(0)
        val w = rect.width().coerceAtMost(src.width - x)
        val h = rect.height().coerceAtMost(src.height - y)
        return Bitmap.createBitmap(src, x, y, w, h)
    }

    private fun preprocess(bmp: Bitmap, targetW: Int, targetH: Int): ByteBuffer {
        val resized = Bitmap.createScaledBitmap(bmp, targetW, targetH, true)
        val buf = ByteBuffer.allocateDirect(1 * targetW * targetH * 3 * 4)
        buf.order(ByteOrder.nativeOrder())
        val pixels = IntArray(targetW * targetH)
        resized.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)
        for (p in pixels) {
            val r = (p shr 16 and 0xFF) / 255f
            val g = (p shr 8 and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            buf.putFloat(r)
            buf.putFloat(g)
            buf.putFloat(b)
        }
        buf.rewind()
        return buf
    }

    private fun l2normalize(vec: FloatArray): FloatArray {
        var sum = 0f
        for (v in vec) sum += v * v
        val norm = kotlin.math.sqrt(sum)
        if (norm > 0f) for (i in vec.indices) vec[i] /= norm
        return vec
    }
}


