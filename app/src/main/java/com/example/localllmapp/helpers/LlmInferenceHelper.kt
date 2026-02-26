// LlmInferenceHelper.kt

package com.example.localllmapp.helpers

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.core.graphics.scale
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import java.io.File

object LlmInferenceHelper {
    private const val TAG = "LlmInferenceHelper"
    private var llmInference: LlmInference? = null
    private var session: LlmInferenceSession? = null
    private val sessionLock = java.util.concurrent.locks.ReentrantLock()
    private var currentMode: ModelMode = ModelMode.NONE

    private enum class ModelMode { NONE, TEXT, MULTIMODAL }

    // --- Initialization ---

    /** Initializes the text-only instruction model. */
    fun initInstructionModel(context: Context): Boolean {
        sessionLock.lock()
        try {
            if (currentMode == ModelMode.TEXT && session != null) return true
            cleanupInternal()

            Log.d(TAG, "Starting instruction model initialization...")
            return try {
                val possiblePaths = listOf(
                    "/data/local/tmp/llm/gemma3-1b-it-int4.task",
                    "/data/local/tmp/gemma3-1b-it-int4.task",
                    "${context.filesDir}/gemma3-1b-it-int4.task",
                    "${context.getExternalFilesDir(null)}/gemma3-1b-it-int4.task"
                )

                var modelPath: String? = null
                for (path in possiblePaths) {
                    Log.d(TAG, "Checking path: $path")
                    if (File(path).exists()) { modelPath = path; break }
                }
                if (modelPath == null) {
                    Log.d(TAG, "Model not found, copying from assets...")
                    modelPath = copyModelFromAssets(context, "gemma3-1b-it-int4.task")
                }
                if (modelPath == null) return false

                val inferenceOptions = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelPath)
                    .setMaxTokens(512)
                    .setPreferredBackend(LlmInference.Backend.CPU)
                    .build()

                llmInference = LlmInference.createFromOptions(context, inferenceOptions)
                session = LlmInferenceSession.createFromOptions(
                    llmInference!!,
                    LlmInferenceSession.LlmInferenceSessionOptions.builder()
                        .setTopK(40)
                        .setTopP(0.95f)
                        .setTemperature(0.7f)
                        .build()
                )
                currentMode = ModelMode.TEXT
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize instruction model", e)
                false
            }
        } finally { sessionLock.unlock() }
    }

    /** Initializes the multimodal (text+image) model. */
    fun initMultiModalModel(context: Context): Boolean {
        sessionLock.lock()
        try {
            if (currentMode == ModelMode.MULTIMODAL && session != null) return true
            cleanupInternal()

            Log.d(TAG, "Starting multimodal model initialization...")
            return try {
                val possiblePaths = listOf(
                    "/data/local/tmp/llm/gemma-3n-E2B-it-int4.task",
                    "/data/local/tmp/gemma-3n-E2B-it-int4.task",
                    "${context.filesDir}/gemma-3n-E2B-it-int4.task",
                    "${context.getExternalFilesDir(null)}/gemma-3n-E2B-it-int4.task"
                )

                var modelPath: String? = null
                for (path in possiblePaths) {
                    Log.d(TAG, "Checking path: $path")
                    if (File(path).exists()) { modelPath = path; break }
                }
                if (modelPath == null) {
                    Log.d(TAG, "Model not found, copying from assets...")
                    modelPath = copyModelFromAssets(context, "gemma-3n-E2B-it-int4.task")
                }
                if (modelPath == null) return false

                val inferenceOptions = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelPath)
                    .setMaxTokens(512)
                    .setMaxNumImages(1)
                    .setPreferredBackend(LlmInference.Backend.CPU)
                    .build()

                llmInference = LlmInference.createFromOptions(context, inferenceOptions)
                session = LlmInferenceSession.createFromOptions(
                    llmInference!!,
                    LlmInferenceSession.LlmInferenceSessionOptions.builder()
                        .setTopK(40)
                        .setTopP(0.9f)
                        .setTemperature(0.3f)
                        .setGraphOptions(GraphOptions.builder().setEnableVisionModality(true).build())
                        .build()
                )
                currentMode = ModelMode.MULTIMODAL
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize multimodal model", e)
                false
            }
        } finally { sessionLock.unlock() }
    }

    /** Copies a model from assets to internal storage if present. */
    private fun copyModelFromAssets(context: Context, modelFileName: String): String? {
        return try {
            val modelFile = File(context.filesDir, modelFileName)
            if (modelFile.exists()) return modelFile.absolutePath
            val assetList = context.assets.list("") ?: emptyArray()
            if (!assetList.contains(modelFileName)) return null
            context.assets.open(modelFileName).use { input ->
                modelFile.outputStream().use { output -> input.copyTo(output) }
            }
            modelFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy model from assets", e)
            null
        }
    }

    // --- Inference ---

    fun generateResponse(prompt: String): String {
        Log.d(TAG, "generateResponse called with prompt: $prompt")
        sessionLock.lock()
        try {
            if (session == null) return "Error: Model not initialized. Please ensure model files are available."
            session?.addQueryChunk(prompt)
            val response = session?.generateResponse() ?: "No response generated"
            return response
        } catch (e: Exception) {
            Log.e(TAG, "Error generating response", e)
            return "Error: ${e.message}"
        } finally { sessionLock.unlock() }
    }

    fun generateMultiModalResponse(prompt: String, bitmap: Bitmap): String {
        Log.d(TAG, "generateMultiModalResponse called")
        sessionLock.lock()
        try {
            // Always start with a fresh session to avoid token accumulation crashes
            cleanupInternal()
            currentMode = ModelMode.NONE
            if (!initMultiModalModel(contextForReinit ?: throw IllegalStateException("Context not set"))) {
                return "Error: Model not initialized. Please ensure model files are available."
            }
            val argbScaled = toArgb8888Scaled(bitmap, 224, 224)
            val mpImage = BitmapImageBuilder(argbScaled).build()
            // Add image first for better grounding
            session?.addImage(mpImage)
            session?.addQueryChunk(prompt)
            val response = session?.generateResponse() ?: "No response generated"
            return response
        } catch (e: Exception) {
            Log.e(TAG, "Error generating multimodal response", e)
            return "⚠️ Failed to process image: ${e.message}"
        } finally { sessionLock.unlock() }
    }

    // Store an application Context once to allow fresh-session reinit inside generateMultiModalResponse
    @Volatile private var contextForReinit: Context? = null
    fun primeContext(ctx: Context) { contextForReinit = ctx.applicationContext }

    // --- Resource Management ---

    fun reset() {
        Log.d(TAG, "Resetting session...")
        sessionLock.lock()
        try { cleanupInternal(); currentMode = ModelMode.NONE } finally { sessionLock.unlock() }
    }

    fun cleanup() {
        Log.d(TAG, "Cleaning up resources...")
        sessionLock.lock()
        try { cleanupInternal(); currentMode = ModelMode.NONE } finally { sessionLock.unlock() }
    }

    private fun cleanupInternal() {
        try { session?.close() } catch (_: Throwable) {}
        session = null
        try { llmInference?.close() } catch (_: Throwable) {}
        llmInference = null
    }

    private fun toArgb8888Scaled(src: Bitmap, targetW: Int, targetH: Int): Bitmap {
        val out = Bitmap.createBitmap(targetW, targetH, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        val paint = android.graphics.Paint().apply { isFilterBitmap = true }
        val srcRect = android.graphics.Rect(0, 0, src.width, src.height)
        val dstRect = android.graphics.Rect(0, 0, targetW, targetH)
        canvas.drawBitmap(src, srcRect, dstRect, paint)
        return out
    }
}


