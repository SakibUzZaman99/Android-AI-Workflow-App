package com.example.localllmapp

import android.content.Context
import android.util.Log
import com.example.localllmapp.helpers.LlmInferenceHelper
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QuerySnapshot
import kotlinx.coroutines.*
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.File
import java.util.Date
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * WorkflowExecutor handles the complete workflow pipeline:
 * 1) Load workflows (local + Firestore)
 * 2) Fetch content from source (Gmail/Telegram handlers)
 * 3) Process with LLM
 * 4) Send to destination
 */
class WorkflowExecutor(private val context: Context) {

    companion object {
        private const val TAG = "WorkflowExecutor"
        private const val PROMPT_TEMPLATE = """
            You are an assistant that converts an incoming message into a concise email according to the user's instructions.

            Output format (STRICT):
            - Return EXACTLY two lines wrapped between the markers BEGIN and END (uppercase), like this:
            BEGIN
            Subject: <short, specific subject you write>
            Body: <final email body only; no preface, no extra commentary>
            END
            - No other text before BEGIN or after END; no markdown, no quotes, no code fences.

            User Instructions: %s

            Original Message:
            From: %s
            Subject/Title: %s
            Content: %s
        """
    }

    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    private val gmailHandler = GmailApiHandler(context)
    private val telegramHandler = TelegramApiHandler(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // --- Data models ---
    data class Workflow(
        val source: String,
        val sourceAccount: String,
        val destination: String,
        val destinationAccount: String,
        val instructions: String,
        val geoLatitude: Double? = null,
        val geoLongitude: Double? = null,
        val geoRadiusMeters: Float? = null,
        val active: Boolean = true
    )

    data class MessageContent(
        val id: String,
        val from: String,
        val to: String,
        val subject: String,
        val body: String,
        val timestamp: Long
    )

    data class ProcessedMessage(
        val originalContent: MessageContent,
        val processedSubject: String,
        val processedBody: String,
        val instructions: String
    )

    data class ProcessingResult(
        val success: Boolean,
        val message: String? = null,
        val error: String? = null
    )

    /**
     * Entry point from the notification listener. Only appName is required.
     */
    fun processNotification(
        appName: String,
        notificationSender: String? = null,  // reserved for future
        notificationTitle: String? = null    // reserved for future
    ) {
        scope.launch {
            try {
                Log.d(TAG, "Processing notification from $appName")
                val workflows = loadMatchingWorkflows(appName)

                if (workflows.isEmpty()) {
                    Log.d(TAG, "No matching workflows found for $appName")
                    return@launch
                }

                workflows.forEach { wf ->
                    processWorkflow(wf, notificationTitle)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing notification", e)
            }
        }
    }

    // --- Core workflow steps ---

    private suspend fun processWorkflow(workflow: Workflow, notificationHint: String? = null) {
        try {
            Log.d(TAG, "Executing workflow: ${workflow.source} -> ${workflow.destination}")

            // 1) Fetch content from source
            val content = fetchSourceContent(workflow.source, workflow.sourceAccount, notificationHint)
            if (content == null) {
                Log.e(TAG, "Failed to fetch content from ${workflow.source}")
                logWorkflowExecution(workflow, false, "Failed to fetch source content")
                return
            }

            // 2) Process with LLM (or default message for Maps when no instructions)
            val processedContent = if (workflow.source == "Maps" && workflow.instructions.isBlank()) {
                // Default subject/body when instructions are empty
                ProcessedMessage(
                    originalContent = content,
                    processedSubject = "Arrival Update",
                    processedBody = "I'm coming home.",
                    instructions = workflow.instructions
                )
            } else {
                val pc = processWithLLM(content, workflow.instructions)
                if (pc == null) {
                    // Fallback, especially helpful for geofence path during testing
                    Log.w(TAG, "LLM failed; using default message")
                    ProcessedMessage(
                        originalContent = content,
                        processedSubject = "Location Update",
                        processedBody = "I'm within the specified area.",
                        instructions = workflow.instructions
                    )
                } else pc
            }

            // 3) Send to destination
            val result = sendToDestination(workflow.destination, workflow.destinationAccount, processedContent)

            // 4) Log execution
            logWorkflowExecution(workflow, result.success, result.message ?: result.error)
        } catch (e: Exception) {
            Log.e(TAG, "Error in workflow execution", e)
            logWorkflowExecution(workflow, false, e.message)
        }
    }

    private suspend fun fetchSourceContent(
        source: String,
        sourceAccount: String,
        notificationHint: String? = null
    ): MessageContent? {
        return when (source) {
            "Google", "Gmail" -> fetchGmailContent(sourceAccount, notificationHint)
            "Maps" -> contentFromGeofenceHint(notificationHint)
            "Photos" -> contentFromPhotosHint()
            "Telegram" -> fetchTelegramContent(sourceAccount, notificationHint)
            else -> null
        }
    }

    // --- Gmail fetch ---

    private suspend fun fetchGmailContent(
        sourceAccount: String,
        subjectHint: String? = null
    ): MessageContent? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting Gmail content fetch...")

            if (!gmailHandler.initializeGmailApi()) {
                Log.e(TAG, "Failed to initialize Gmail API")
                return@withContext null
            }

            Log.d(TAG, "Gmail API initialized, fetching emails...")

            val messages = suspendCancellableCoroutine<List<GmailMessage>> { cont ->
                gmailHandler.fetchLatestEmails { emails ->
                    Log.d(TAG, "Received ${emails.size} emails from Gmail API")
                    cont.resume(emails) { }
                }
            }

            val filtered = if (sourceAccount != "Any" && sourceAccount.isNotEmpty()) {
                messages.filter { it.to.contains(sourceAccount) || it.from.contains(sourceAccount) }
            } else {
                messages
            }

            val target = if (!subjectHint.isNullOrBlank()) {
                filtered.firstOrNull { it.subject.contains(subjectHint, ignoreCase = true) }
                    ?: filtered.firstOrNull()
            } else {
                filtered.firstOrNull()
            }

            target?.let {
                Log.d(TAG, "Found target message: ${it.subject}")
                MessageContent(
                    id = it.id,
                    from = it.from,
                    to = it.to,
                    subject = it.subject,
                    body = it.body,
                    timestamp = it.timestamp
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching Gmail content", e)
            null
        }
    }

    // --- Telegram fetch (placeholder) ---

    private suspend fun fetchTelegramContent(
        sourceAccount: String,
        messageHint: String? = null
    ): MessageContent? {
        Log.w(TAG, "Telegram content fetching not yet implemented")
        return null
    }

    // --- Maps / Geofence content (trigger only) ---
    // When a geofence event occurs, we pass a hint via notificationHint to match the workflow
    private fun contentFromGeofenceHint(hint: String?): MessageContent? {
        // We fabricate a minimal message content that can be passed into the LLM pipeline
        if (hint.isNullOrBlank()) return null
        return MessageContent(
            id = "geofence-" + System.currentTimeMillis(),
            from = "Geofence",
            to = "",
            subject = hint,
            body = hint,
            timestamp = System.currentTimeMillis()
        )
    }

    // --- Photos content (trigger-only placeholder) ---
    private fun contentFromPhotosHint(): MessageContent? {
        return MessageContent(
            id = "photos-" + System.currentTimeMillis(),
            from = "Photos",
            to = "",
            subject = "Photos Trigger",
            body = "New photos detected",
            timestamp = System.currentTimeMillis()
        )
    }

    // --- Public entrypoint for Geofence events ---
    suspend fun processGeofenceEvent(latitude: Double, longitude: Double, transition: String) {
        try {
            val all = loadAllWorkflows()
            Log.d(TAG, "Checking ${all.size} workflows for geofence match at $latitude,$longitude")
            val matching = all.filter { wf ->
                wf.active && wf.source == "Maps" &&
                        wf.geoLatitude != null && wf.geoLongitude != null && wf.geoRadiusMeters != null &&
                        isWithinRadius(latitude, longitude, wf.geoLatitude, wf.geoLongitude, wf.geoRadiusMeters)
            }
            Log.d(TAG, "Matched ${matching.size} geofence workflows")
            if (matching.isEmpty()) {
                Log.d(TAG, "No matching geofence workflows for location: $latitude,$longitude")
                return
            }

            matching.forEach { wf ->
                // Avoid spamming: for DWELL, only send if last sent > 15 minutes ago for same workflow
                // (Simple heuristic omitted for brevity; can add persistent throttling if needed)
                processWorkflow(wf, notificationHint = "Geofence $transition")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error processing geofence event", t)
        }
    }

    // ID-based processing for reliable EXIT handling
    suspend fun processGeofenceEventByIds(requestIds: List<String>, transition: String) {
        try {
            val all = loadAllWorkflows()
            val idSet = requestIds.toSet()
            // We used file name as requestId when registering geofence
            val matching = all.filter { wf ->
                wf.active && wf.source == "Maps" &&
                        idSet.contains(workflowLocalFileIdFor(wf))
            }
            Log.d(TAG, "processGeofenceEventByIds matched ${matching.size} workflows for ids=$requestIds")
            matching.forEach { wf ->
                processWorkflow(wf, notificationHint = "Geofence $transition")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error processing geofence event by ids", t)
        }
    }

    private fun workflowLocalFileIdFor(workflow: Workflow): String? {
        // Best effort: derive a stable ID from local storage by recomputing filename pattern.
        // Since we don't store filename in Workflow, match by nearest geo lat/lng/radius to existing local files.
        return try {
            val files = context.filesDir.listFiles { f ->
                f.name.startsWith("workflow_") && f.name.endsWith(".json")
            } ?: return null
            val targetLat = workflow.geoLatitude
            val targetLng = workflow.geoLongitude
            val targetR = workflow.geoRadiusMeters
            var best: Pair<String, Double>? = null
            for (f in files) {
                runCatching {
                    val json = JSONObject(f.readText())
                    val lat = json.optDouble("geoLatitude", Double.NaN)
                    val lng = json.optDouble("geoLongitude", Double.NaN)
                    val rad = json.optDouble("geoRadiusMeters", Double.NaN)
                    if (!lat.isNaN() && !lng.isNaN() && !rad.isNaN() &&
                        targetLat != null && targetLng != null && targetR != null) {
                        val dLat = (lat - targetLat)
                        val dLng = (lng - targetLng)
                        val dR = (rad - targetR).toDouble()
                        val score = dLat * dLat + dLng * dLng + dR * dR
                        if (best == null || score < best!!.second) {
                            best = f.name to score
                        }
                    }
                }
            }
            best?.first
        } catch (_: Throwable) {
            null
        }
    }

    private fun isWithinRadius(
        lat: Double,
        lng: Double,
        centerLat: Double?,
        centerLng: Double?,
        radiusMeters: Float?
    ): Boolean {
        if (centerLat == null || centerLng == null || radiusMeters == null) return false
        val results = FloatArray(1)
        android.location.Location.distanceBetween(lat, lng, centerLat, centerLng, results)
        return results[0] <= radiusMeters
    }

    // --- LLM processing ---

    private suspend fun processWithLLM(
        content: MessageContent,
        instructions: String
    ): ProcessedMessage? = withContext(Dispatchers.Main) {
        try {
            if (!LlmInferenceHelper.initInstructionModel(context)) {
                Log.e(TAG, "Failed to initialize LLM")
                return@withContext null
            }

            val prompt = String.format(
                PROMPT_TEMPLATE,
                instructions,
                content.from,
                content.subject,
                content.body
            )

            val response = withContext(Dispatchers.IO) {
                LlmInferenceHelper.generateResponse(prompt)
            }

            val cleaned = extractBetweenMarkers(response)
            val (subj, body) = parseLlmEmailResponse(cleaned)

            ProcessedMessage(
                originalContent = content,
                processedSubject = subj,
                processedBody = body,
                instructions = instructions
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error processing with LLM", e)
            null
        }
    }

    // --- Destination sending ---

    private suspend fun sendToDestination(
        destination: String,
        destinationAccount: String,
        content: ProcessedMessage
    ): ProcessingResult {
        return when (destination) {
            "Google", "Gmail" -> sendToGmail(destinationAccount, content)
            "Telegram" -> sendToTelegram(destinationAccount, content)
            else -> ProcessingResult(false, error = "Unknown destination: $destination")
        }
    }

    private suspend fun sendToGmail(
        destinationEmail: String,
        content: ProcessedMessage
    ): ProcessingResult {
        return try {
            // Ensure Gmail API is initialized for non-Gmail sources (e.g., Maps-triggered)
            val gmailReady = gmailHandler.initializeGmailApi()
            if (!gmailReady) {
                ProcessingResult(false, error = "Gmail not initialized or missing permissions")
            } else {
                val subject = content.processedSubject.ifBlank { "Processed: ${content.originalContent.subject}" }
                val body = content.processedBody

                val success = gmailHandler.sendEmail(destinationEmail, subject, body)
                if (success) {
                    // Mark source message as READ to avoid re-processing
                    runCatching { gmailHandler.markMessageAsRead(content.originalContent.id) }
                        .onFailure { Log.w(TAG, "Failed to mark source message as read", it) }
                    ProcessingResult(true, "Email sent successfully to $destinationEmail")
                } else {
                    ProcessingResult(false, error = "Failed to send email")
                }
            }
        } catch (e: Exception) {
            ProcessingResult(false, error = e.message)
        }
    }

    private suspend fun sendToTelegram(
        phoneNumber: String,
        content: ProcessedMessage
    ): ProcessingResult = try {
        val message = """
            📧 Processed Message
            From: ${content.originalContent.from}
            Subject: ${content.originalContent.subject}
            
            ${content.processedBody}
        """.trimIndent()

        val success = telegramHandler.sendMessage(phoneNumber, message, useMasterBot = true)
        if (success) ProcessingResult(true, "Message sent to Telegram: $phoneNumber")
        else ProcessingResult(false, error = "Failed to send Telegram message")
    } catch (e: Exception) {
        ProcessingResult(false, error = e.message)
    }

    // --- Workflows loading (local + Firestore) ---

    private suspend fun loadMatchingWorkflows(appName: String): List<Workflow> {
        val all = loadAllWorkflows()
        return all.filter {
            it.active && (
                    it.source == appName ||
                            (appName == "Gmail" && it.source == "Google") ||
                            (appName == "Telegram" && it.source == "Telegram")
                    )
        }
    }

    private suspend fun loadAllWorkflows(): List<Workflow> {
        val local = loadLocalWorkflows()
        val remote = loadWorkflowsFromFirestore()
        return (local + remote)
            .distinctBy { Triple(it.source, it.destination, it.instructions) }
            .filter { it.active }
    }

    private fun loadLocalWorkflows(): List<Workflow> {
        val workflows = mutableListOf<Workflow>()
        try {
            val files = context.filesDir.listFiles { f ->
                f.name.startsWith("workflow_") && f.name.endsWith(".json")
            }
            files?.forEach { file ->
                try {
                    val json = JSONObject(file.readText())
                    Log.d(TAG, "Loaded workflow file=${file.name} src=${json.optString("source")} lat=${json.optDouble("geoLatitude", Double.NaN)} lng=${json.optDouble("geoLongitude", Double.NaN)} radius=${json.optDouble("geoRadiusMeters", Double.NaN)}")
                    workflows.add(
                        Workflow(
                            source = json.getString("source"),
                            sourceAccount = json.getString("sourceAccount"),
                            destination = json.getString("destination"),
                            destinationAccount = json.getString("destinationAccount"),
                            instructions = json.getString("instructions"),
                            geoLatitude = json.optDouble("geoLatitude").let { if (it.isNaN()) null else it },
                            geoLongitude = json.optDouble("geoLongitude").let { if (it.isNaN()) null else it },
                            geoRadiusMeters = json.optDouble("geoRadiusMeters").let { if (it.isNaN()) null else it.toFloat() },
                            active = json.optBoolean("active", true)
                        )
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing workflow file: ${file.name}", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading workflows", e)
        }
        return workflows
    }

    private suspend fun loadWorkflowsFromFirestore(): List<Workflow> = withContext(Dispatchers.IO) {
        try {
            val userId = auth.currentUser?.uid ?: return@withContext emptyList()
            val snapshot: QuerySnapshot = suspendCancellableCoroutine { cont ->
                firestore.collection("workflows")
                    .whereEqualTo("userId", userId)
                    .whereEqualTo("active", true)
                    .get()
                    .addOnSuccessListener { cont.resume(it) { } }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }

            snapshot.documents.mapNotNull { doc ->
                try {
                    Workflow(
                        source = doc.getString("source") ?: return@mapNotNull null,
                        sourceAccount = doc.getString("sourceAccount") ?: "Any",
                        destination = doc.getString("destination") ?: return@mapNotNull null,
                        destinationAccount = doc.getString("destinationAccount") ?: "",
                        instructions = doc.getString("instructions") ?: "",
                        geoLatitude = doc.getDouble("geoLatitude"),
                        geoLongitude = doc.getDouble("geoLongitude"),
                        geoRadiusMeters = doc.getDouble("geoRadiusMeters")?.toFloat(),
                        active = doc.getBoolean("active") ?: true
                    )
                } catch (_: Exception) {
                    null
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Error loading Firestore workflows (continuing with local only)", t)
            emptyList()
        }
    }

    // --- Logging ---

    private fun logWorkflowExecution(
        workflow: Workflow,
        success: Boolean,
        message: String?
    ) {
        try {
            val userId = auth.currentUser?.uid ?: return

            val log = hashMapOf(
                "userId" to userId,
                "workflow" to hashMapOf(
                    "source" to workflow.source,
                    "destination" to workflow.destination,
                    "instructions" to workflow.instructions
                ),
                "success" to success,
                "message" to (message ?: ""),
                "timestamp" to Date()
            )

            firestore.collection("workflow_logs")
                .add(log)
                .addOnFailureListener { e ->
                    Log.w(TAG, "Error logging workflow execution (non-critical)", e)
                }
        } catch (e: Exception) {
            Log.w(TAG, "Error in logWorkflowExecution (non-critical)", e)
        }
    }

    /** Cleanup resources */
    fun cleanup() {
        scope.cancel()
    }

    // --- Helpers ---

    private fun extractBetweenMarkers(raw: String): String {
        val start = raw.indexOf("BEGIN")
        val end = raw.lastIndexOf("END")
        return if (start != -1 && end != -1 && end > start) raw.substring(start + 5, end).trim() else raw.trim()
    }

    private fun parseLlmEmailResponse(raw: String): Pair<String, String> {
        // Expect exactly two lines starting with "Subject:" and "Body:". Be tolerant to whitespace.
        val subjectRegex = Regex("(?im)^Subject:\\s*(.*)")
        val bodyRegex = Regex("(?im)^Body:\\s*(.*)")

        val subject = subjectRegex.find(raw)?.groupValues?.getOrNull(1)?.trim() ?: ""

        // Body may span multiple lines; capture from Body: to end if formatted as first line then content
        val bodyStart = bodyRegex.find(raw)?.range?.last?.plus(1)
        val body = if (bodyStart != null && bodyStart < raw.length) {
            raw.substring(bodyStart).trim()
        } else {
            // Fallback: if only one-line body provided after Body:
            bodyRegex.find(raw)?.groupValues?.getOrNull(1)?.trim() ?: raw.trim()
        }

        return Pair(subject, body)
    }

    // Signature appending removed per user request; send body as-is
}
