package com.example.localllmapp.photos

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.util.Log
import com.example.localllmapp.GmailApiHandler
import com.example.localllmapp.helpers.LlmInferenceHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Observes MediaStore for new images and processes them according to instruction-driven workflows.
 * Minimal state is kept in workflow_*.json (photoBaselineTs; optionally lastSummaryTs if needed later).
 */
object PhotosWorkflowManager {
    private const val TAG = "PhotosWorkflowManager"
    private val started = AtomicBoolean(false)
    private var handlerThread: HandlerThread? = null
    private var observer: ContentObserver? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isProcessing = java.util.concurrent.atomic.AtomicBoolean(false)
    private val pendingUris: ConcurrentLinkedQueue<Uri> = ConcurrentLinkedQueue()

    fun ensureStarted(context: Context) {
        // Only start if at least one Photos workflow exists
        if (loadPhotoWorkflows(context).isEmpty()) {
            Log.d(TAG, "No Photos workflows found; not starting observer")
            return
        }
        if (started.getAndSet(true)) return
        val resolver = context.contentResolver
        handlerThread = HandlerThread("photos-observer").apply { start() }
        val handler = Handler(handlerThread!!.looper)
        observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                if (uri != null) {
                    pendingUris.add(uri)
                    scope.launch { drainQueue(context) }
                }
            }
        }
        resolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            observer as ContentObserver
        )
        // No initial sweep; only process truly new images when they arrive
    }

    fun refresh(context: Context) {
        if (loadPhotoWorkflows(context).isEmpty()) {
            stop(context)
        } else if (!started.get()) {
            ensureStarted(context)
        }
    }

    fun stop(context: Context) {
        if (!started.getAndSet(false)) return
        try { observer?.let { context.contentResolver.unregisterContentObserver(it) } } catch (_: Throwable) {}
        try { handlerThread?.quitSafely() } catch (_: Throwable) {}
        handlerThread = null
        observer = null
    }

    private fun loadPhotoWorkflows(context: Context): List<WorkflowSpec> {
        val files = context.filesDir.listFiles { f ->
            f.name.startsWith("workflow_") && f.name.endsWith(".json")
        } ?: return emptyList()
        val list = mutableListOf<WorkflowSpec>()
        for (f in files) {
            runCatching {
                val json = JSONObject(f.readText())
                if (json.optString("source") == "Photos" && json.optBoolean("active", true)) {
                    val baseline = json.optLong("photoBaselineTs", 0L)
                    val last = json.optLong("photoLastProcessedTs", 0L)
                    val dest = json.optString("destination")
                    val destAcct = json.optString("destinationAccount")
                    val instructions = json.optString("instructions")
                    val bootstrap = json.optInt("photoBootstrapCount", 0)
                    val person = json.optString("photoPersonName", "")
                    val embeds = json.optJSONArray("photoPersonEmbeddings")
                    val vectors = mutableListOf<FloatArray>()
                    if (embeds != null) {
                        for (i in 0 until embeds.length()) {
                            val arr = embeds.getJSONArray(i)
                            val v = FloatArray(arr.length()) { idx -> arr.getDouble(idx).toFloat() }
                            vectors += v
                        }
                    }
                    list += WorkflowSpec(f.name, baseline, last, dest, destAcct, instructions, person, vectors, bootstrap)
                }
            }
        }
        return list
    }

    private data class WorkflowSpec(
        val fileName: String,
        val baselineTs: Long,
        val lastProcessedTs: Long,
        val destination: String,
        val destinationAccount: String,
        val instructions: String,
        val personName: String,
        val personEmbeddings: List<FloatArray>,
        val bootstrapCount: Int
    )

    private suspend fun processNewPhotos(context: Context) {
        val workflows = loadPhotoWorkflows(context)
        if (workflows.isEmpty()) {
            Log.d(TAG, "No Photos workflows; stopping observer")
            stop(context)
            return
        }

        // Prepare Gmail once if any workflow targets Gmail
        val gmail = GmailApiHandler(context)
        val gmailReady = gmail.initializeGmailApi()

        // Permission check (Android 13+)
        val hasPerm = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.READ_MEDIA_IMAGES
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPerm) return

        // Deprecated in real-time mode
    }

    private suspend fun drainQueue(context: Context) {
        if (!isProcessing.compareAndSet(false, true)) return
        try {
            while (true) {
                val uri = pendingUris.poll() ?: break
                runCatching { processNewPhotoUri(context, uri) }
                    .onFailure { t -> Log.e(TAG, "Error processing queued uri=$uri", t) }
            }
        } finally {
            isProcessing.set(false)
            if (pendingUris.isNotEmpty()) scope.launch { drainQueue(context) }
        }
    }

    private suspend fun processNewPhotoUri(context: Context, uri: Uri) {
        val workflows = loadPhotoWorkflows(context)
        if (workflows.isEmpty()) return

        val gmail = GmailApiHandler(context)
        val gmailReady = gmail.initializeGmailApi()

        val hasPerm = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.READ_MEDIA_IMAGES
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPerm) return

        // Resolve timestamps of this uri
        val item = querySingle(resolver = context.contentResolver, uri = uri) ?: return

        workflows.forEach { wf ->
            val gateTs = maxOf(wf.baselineTs, wf.lastProcessedTs)
            if (item.takenOrAddedMs <= gateTs) return@forEach

            // Ensure multimodal model and prime context for safe reinit
            com.example.localllmapp.helpers.LlmInferenceHelper.primeContext(context)
            if (!ensureMultiModalInitialized(context)) return@forEach

            val results = mutableListOf<String>()
            var maxTs = gateTs
            val res = runCatching {
                val bmp = decodeScaledBitmap(context, item.uri)
                if (bmp != null) {
                    if (wf.personEmbeddings.isNotEmpty()) {
                        val match = matchPerson(context, bmp, wf.personEmbeddings)
                        if (match) {
                            results += "Matched ${'$'}{wf.personName} -> ${item.uri}"
                            if ((wf.destination.equals("Google", true) || wf.destination.equals("Gmail", true)) && gmailReady) {
                                context.contentResolver.openInputStream(item.uri)?.use { imgIn ->
                                    val bytes = imgIn.readBytes()
                                    val subject = "Photo matched: ${wf.personName}"
                                    val body = "Automatically forwarding a photo matched to ${wf.personName}."
                                    val name = "photo_${System.currentTimeMillis()}.jpg"
                                    gmail.sendEmailWithImageAttachment(wf.destinationAccount, subject, body, name, bytes, "image/jpeg")
                                }
                            }
                            ""
                        } else null
                    } else {
                        val augmented = buildDecisionSuffix(wf.instructions)
                        val raw = try {
                            kotlinx.coroutines.withTimeout(7000) {
                                LlmInferenceHelper.generateMultiModalResponse(augmented, bmp as android.graphics.Bitmap)
                            }
                        } catch (_: Throwable) { null }
                        val decision = parseDecision(raw)
                        if (decision.shouldForward) {
                            if ((wf.destination.equals("Google", true) || wf.destination.equals("Gmail", true)) && gmailReady) {
                                context.contentResolver.openInputStream(item.uri)?.use { imgIn ->
                                    val bytes = imgIn.readBytes()
                                    val subject = "Photo matched"
                                    val body = buildString {
                                        append("Auto-forwarded based on instruction. Reason: ")
                                        append(decision.reason ?: "")
                                        decision.parse?.let { p -> append("\n").append(p) }
                                    }.trim()
                                    val name = "photo_${System.currentTimeMillis()}.jpg"
                                    gmail.sendEmailWithImageAttachment(wf.destinationAccount, subject, body, name, bytes, "image/jpeg")
                                }
                            }
                            "FORWARDED"
                        } else null
                    }
                } else null
            }.getOrNull()
            if (!res.isNullOrBlank()) results += res
            if (item.takenOrAddedMs > maxTs) maxTs = item.takenOrAddedMs

            // We already send attachments inline when detection succeeds; optional summary disabled
            // Persist last processed timestamp to avoid duplicates next run, regardless of send
            updateWorkflowLastProcessed(context, wf.fileName, maxTs)
        }
    }

    private fun ensureMultiModalInitialized(context: Context): Boolean {
        return try { LlmInferenceHelper.initMultiModalModel(context) } catch (_: Throwable) { false }
    }

    private fun matchPerson(context: Context, bmp: android.graphics.Bitmap, enrolled: List<FloatArray>): Boolean {
        var found = false
        val latch = java.util.concurrent.CountDownLatch(1)
        FaceEmbeddingHelper.detectFaces(bmp, highAccuracy = false) { faces ->
            try {
                for (f in faces) {
                    val vec = FaceEmbeddingHelper.embedCroppedFace(context, bmp, f.boundingBox) ?: continue
                    for (en in enrolled) {
                        val sim = cosineSimilarity(vec, en)
                        if (sim >= 0.65f) { // high recall threshold
                            found = true
                            break
                        }
                    }
                    if (found) break
                }
            } finally {
                latch.countDown()
            }
        }
        latch.await()
        return found
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var na = 0f
        var nb = 0f
        val n = kotlin.math.min(a.size, b.size)
        for (i in 0 until n) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        val denom = (kotlin.math.sqrt(na.toDouble()) * kotlin.math.sqrt(nb.toDouble())).toFloat()
        return if (denom > 0f) dot / denom else 0f
    }

    private fun buildDecisionSuffix(instr: String): String {
        val suffix = """

SYSTEM: The image is already provided. Decide if it satisfies the user's instruction below. Use high recall.
Output EXACTLY these lines (if not applicable, leave empty after the colon, but still print the key):
DECISION: YES|NO
REASON: <short phrase why>
PARSE: <optional single-line key=value pairs>

User instruction: $instr
""".trimIndent()
        return suffix
    }

    private fun extractParseLine(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val line = text.lines().firstOrNull { it.trim().startsWith("PARSE:") }
        return line?.trim()
    }

    private data class Decision(val shouldForward: Boolean, val reason: String?, val parse: String?)

    private fun parseDecision(text: String?): Decision {
        if (text.isNullOrBlank()) return Decision(false, null, null)
        var decision: String? = null
        var reason: String? = null
        var parse: String? = null
        text.lines().forEach { line ->
            val t = line.trim()
            when {
                t.startsWith("DECISION:", ignoreCase = true) -> decision = t.substringAfter(":").trim()
                t.startsWith("REASON:", ignoreCase = true) -> reason = t.substringAfter(":").trim()
                t.startsWith("PARSE:", ignoreCase = true) -> parse = t.trim()
            }
        }
        return Decision(decision.equals("YES", ignoreCase = true), reason, parse)
    }

    private data class ImageItem(val uri: Uri, val takenOrAddedMs: Long)

    private fun querySingle(resolver: ContentResolver, uri: Uri): ImageItem? {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED
        )
        resolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val takenIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val addedIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val taken = cursor.getLong(takenIdx)
                val added = cursor.getLong(addedIdx) * 1000L
                val ts = maxOf(taken, added)
                return ImageItem(uri, ts)
            }
        }
        return null
    }

    private fun queryImagesAfter(resolver: ContentResolver, baselineTs: Long, limit: Int): List<ImageItem> {
        val list = mutableListOf<ImageItem>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED
        )
        val selection = "(${MediaStore.Images.Media.DATE_TAKEN}>=? OR ${MediaStore.Images.Media.DATE_ADDED}>=?)"
        val args = arrayOf(baselineTs.toString(), (baselineTs / 1000L).toString())
        val sort = MediaStore.Images.Media.DATE_ADDED + " DESC"
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        resolver.query(uri, projection, selection, args, sort)?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val takenIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val addedIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            var count = 0
            while (cursor.moveToNext() && count < limit) {
                val id = cursor.getLong(idIdx)
                val contentUri = Uri.withAppendedPath(uri, id.toString())
                val taken = cursor.getLong(takenIdx)
                val added = cursor.getLong(addedIdx) * 1000L
                val ts = maxOf(taken, added)
                list += ImageItem(contentUri, ts)
                count++
            }
        }
        return list
    }

    private fun queryMostRecent(resolver: ContentResolver, limit: Int): List<ImageItem> {
        val list = mutableListOf<ImageItem>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED
        )
        val sort = MediaStore.Images.Media.DATE_ADDED + " DESC"
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        resolver.query(uri, projection, null, null, sort)?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val takenIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val addedIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            var count = 0
            while (cursor.moveToNext() && count < limit) {
                val id = cursor.getLong(idIdx)
                val contentUri = Uri.withAppendedPath(uri, id.toString())
                val taken = cursor.getLong(takenIdx)
                val added = cursor.getLong(addedIdx) * 1000L
                val ts = maxOf(taken, added)
                list += ImageItem(contentUri, ts)
                count++
            }
        }
        return list
    }

    private fun decodeScaledBitmap(context: Context, uri: Uri): android.graphics.Bitmap? {
        return try {
            val opts1 = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts1) }
            val w = opts1.outWidth; val h = opts1.outHeight
            if (w <= 0 || h <= 0) return null
            var sample = 1
            var tw = w; var th = h
            while (tw / 2 >= 512 && th / 2 >= 512) { tw /= 2; th /= 2; sample *= 2 }
            val opts2 = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888 }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts2) }
        } catch (t: Throwable) {
            Log.w(TAG, "decodeScaledBitmap failed for $uri", t)
            null
        }
    }

    private fun updateWorkflowLastProcessed(context: Context, fileName: String, lastTs: Long) {
        runCatching {
            val file = context.filesDir.listFiles { f -> f.name == fileName }?.firstOrNull() ?: return
            val json = JSONObject(file.readText())
            json.put("photoLastProcessedTs", lastTs)
            file.writeText(json.toString())
        }
    }
}


