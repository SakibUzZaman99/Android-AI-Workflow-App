package com.example.localllmapp

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Date
import java.util.concurrent.TimeUnit

data class TelegramMessage(
    val id: String,
    val from: String,
    val chatId: String,
    val subject: String,
    val text: String,
    val timestamp: Long
)

class TelegramApiHandler(private val context: Context) {

    companion object {
        private const val TAG = "TelegramApiHandler"
        private const val BOT_TOKEN = "8320758434:AAGOU8XuLlKJLPJbvnC5TSP_w5sYCO5-qTc"
        private const val APPLICATION_NAME = "LocalLLMApp"
        private const val TELEGRAM_API_BASE = "https://api.telegram.org/bot$BOT_TOKEN"
    }

    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun initializeTelegramApi(): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$TELEGRAM_API_BASE/getMe").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext false
                val ok = JSONObject(resp.body?.string()).optBoolean("ok", false)
                ok
            }
        } catch (e: Exception) {
            Log.e(TAG, "init error", e)
            false
        }
    }

    suspend fun fetchLatestMessages(): List<TelegramMessage> = withContext(Dispatchers.IO) {
        try {
            val snapshot = firestore.collection("telegram_messages")
                .whereEqualTo("processed", false)
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .limit(10)
                .get()
                .await()

            snapshot.documents.mapNotNull { doc ->
                try {
                    TelegramMessage(
                        id = doc.id,
                        from = doc.getString("phone_number") ?: "Unknown",
                        chatId = doc.getString("chat_id") ?: "",
                        subject = "Message from bot user",
                        text = doc.getString("text") ?: "",
                        timestamp = doc.getTimestamp("timestamp")?.toDate()?.time ?: 0L
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Parse error ${doc.id}", e)
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetch firestore error", e)
            emptyList()
        }
    }

    suspend fun markAsProcessed(messageId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            firestore.collection("telegram_messages").document(messageId).update("processed", true).await()
            true
        } catch (e: Exception) {
            Log.e(TAG, "mark processed error", e)
            false
        }
    }

    // New signature: send by chatId with subject/body
    suspend fun sendMessage(chatId: String, subject: String, body: String): Boolean = withContext(Dispatchers.IO) {
        try {
            if (chatId.isBlank() || chatId == "Any") return@withContext false
            val effectiveText = if (subject.isNotBlank()) "$subject\n\n$body" else body
            val json = JSONObject().apply {
                put("chat_id", chatId)
                put("text", effectiveText)
            }
            val req = Request.Builder()
                .url("$TELEGRAM_API_BASE/sendMessage")
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext false
                JSONObject(resp.body?.string()).optBoolean("ok", false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "send error", e)
            false
        }
    }

    // Backward-compatible old signature used elsewhere in the app
    suspend fun sendMessage(phoneNumber: String, message: String, useMasterBot: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        try {
            // Attempt to route via chatId if available in mappings collection
            val mapping = firestore.collection("telegram_mappings").document(phoneNumber).get().await()
            val chatId = mapping.getLong("chatId")?.toString()
            if (!chatId.isNullOrBlank()) {
                return@withContext sendMessage(chatId, subject = "", body = message)
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "legacy send error", e)
            false
        }
    }
}