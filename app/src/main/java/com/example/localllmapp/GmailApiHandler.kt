package com.example.localllmapp

import android.content.Context
import android.util.Log
import androidx.activity.ComponentActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.gmail.Gmail
import com.google.api.services.gmail.GmailScopes
import com.google.api.services.gmail.model.Message
import com.google.api.services.gmail.model.ModifyMessageRequest
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Date
import android.util.Patterns
import java.util.Base64

data class GmailMessage(
    val id: String,
    val from: String,
    val to: String,
    val subject: String,
    val body: String,
    val timestamp: Long
)

class GmailApiHandler(private val context: Context) {

    companion object {
        private const val TAG = "GmailApiHandler"
        private const val APPLICATION_NAME = "LocalLLMApp"
        private val GMAIL_SCOPES = listOf(
            GmailScopes.GMAIL_READONLY,
            GmailScopes.GMAIL_SEND,
            GmailScopes.GMAIL_MODIFY
        )
    }

    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    private var gmailService: Gmail? = null

    suspend fun initializeGmailApi(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val currentUser = auth.currentUser
                if (currentUser == null) {
                    Log.e(TAG, "No authenticated user")
                    return@withContext false
                }

                val googleAccount = GoogleSignIn.getLastSignedInAccount(context)
                if (googleAccount == null) {
                    Log.e(TAG, "No Google account found")
                    return@withContext false
                }

                if (!hasGmailPermissions(googleAccount)) {
                    Log.e(TAG, "Missing Gmail permissions")
                    return@withContext false
                }

                gmailService = createGmailService(googleAccount)
                storeTokensInFirestore(googleAccount)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing Gmail API", e)
                false
            }
        }
    }

    private fun hasGmailPermissions(account: GoogleSignInAccount): Boolean {
        val grantedScopes = account.grantedScopes
        return GMAIL_SCOPES.all { scope ->
            grantedScopes.any { it.scopeUri == scope }
        }
    }

    private fun createGmailService(account: GoogleSignInAccount): Gmail {
        val credential = GoogleAccountCredential.usingOAuth2(
            context,
            GMAIL_SCOPES
        ).apply {
            selectedAccount = account.account
        }

        return Gmail.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        ).setApplicationName(APPLICATION_NAME).build()
    }

    private suspend fun storeTokensInFirestore(account: GoogleSignInAccount) {
        try {
            val userId = auth.currentUser?.uid ?: return
            val tokenData = hashMapOf(
                "email" to (account.email ?: ""),
                "displayName" to (account.displayName ?: ""),
                "idToken" to (account.idToken ?: ""),
                "serverAuthCode" to (account.serverAuthCode ?: ""),
                "lastUpdated" to Date(),
                "scopes" to account.grantedScopes.map { it.scopeUri }
            )

            firestore.collection("users")
                .document(userId)
                .collection("tokens")
                .document("gmail")
                .set(tokenData)
                .await()

            Log.d(TAG, "Tokens stored in Firestore")
        } catch (e: Exception) {
            Log.e(TAG, "Error storing tokens", e)
        }
    }

    suspend fun requestGmailScopes(activity: ComponentActivity): Boolean {
        return withContext(Dispatchers.Main) {
            try {
                val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                    .requestIdToken(context.getString(R.string.default_web_client_id))
                    .requestEmail()
                    .requestScopes(
                        Scope(GmailScopes.GMAIL_READONLY),
                        Scope(GmailScopes.GMAIL_SEND),
                        Scope(GmailScopes.GMAIL_MODIFY)
                    )
                    .build()

                val googleSignInClient = GoogleSignIn.getClient(activity, gso)
                googleSignInClient.signOut().await()
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error requesting scopes", e)
                false
            }
        }
    }

    fun fetchLatestEmails(callback: (List<GmailMessage>) -> Unit) {
        Thread {
            try {
                val service = gmailService
                if (service == null) {
                    Log.e(TAG, "Gmail service not initialized")
                    callback(emptyList())
                    return@Thread
                }

                val response = service.users().messages()
                    .list("me")
                    .setQ("is:unread in:inbox")
                    .setMaxResults(10)
                    .execute()

                val messages = mutableListOf<GmailMessage>()
                response.messages?.forEach { messageRef ->
                    try {
                        val fullMessage = service.users().messages()
                            .get("me", messageRef.id)
                            .execute()
                        val gmailMessage = parseGmailMessage(fullMessage)
                        messages.add(gmailMessage)
                        logEmailToFirestore(gmailMessage)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error fetching message ${messageRef.id}", e)
                    }
                }
                callback(messages)
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching emails", e)
                callback(emptyList())
            }
        }.start()
    }

    private fun parseGmailMessage(message: Message): GmailMessage {
        val headers = message.payload.headers
        val from = headers.find { it.name == "From" }?.value ?: ""
        val to = headers.find { it.name == "To" }?.value ?: ""
        val subject = headers.find { it.name == "Subject" }?.value ?: ""
        val body = extractBody(message)

        return GmailMessage(
            id = message.id,
            from = from,
            to = to,
            subject = subject,
            body = body,
            timestamp = message.internalDate ?: 0L
        )
    }

    private fun extractBody(message: Message): String {
        return when {
            message.payload.body?.data != null -> {
                String(Base64.getUrlDecoder().decode(message.payload.body.data))
            }

            message.payload.parts != null -> {
                message.payload.parts
                    .firstOrNull { it.mimeType == "text/plain" }
                    ?.body?.data?.let {
                        String(Base64.getUrlDecoder().decode(it))
                    } ?: ""
            }

            else -> ""
        }
    }

    private fun logEmailToFirestore(email: GmailMessage) {
        try {
            val userId = auth.currentUser?.uid ?: return
            val emailLog = hashMapOf(
                "userId" to userId,
                "messageId" to email.id,
                "from" to email.from,
                "subject" to email.subject,
                "timestamp" to Date(email.timestamp),
                "processedAt" to Date()
            )
            firestore.collection("email_logs")
                .add(emailLog)
                .addOnFailureListener { e -> Log.e(TAG, "Error logging email", e) }
        } catch (e: Exception) {
            Log.e(TAG, "Error in logEmailToFirestore", e)
        }
    }

    // Helper: extract a usable single email from things like "Name <email@x.com>"
    private fun normalizeRecipient(raw: String): String {
        val trimmed = raw.trim()
        // If it looks like "Name <email@x.com>"
        val angle = Regex(".*<\\s*([^>\\s]+)\\s*>")
        val m = angle.matchEntire(trimmed)
        val candidate = if (m != null) m.groupValues[1] else trimmed
        return candidate.trim()
    }

    private fun isValidEmail(addr: String): Boolean =
        Patterns.EMAIL_ADDRESS.matcher(addr).matches()

    suspend fun sendEmail(to: String, subject: String, body: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val service = gmailService
                if (service == null) {
                    Log.e(TAG, "Gmail service not initialized")
                    return@withContext false
                }

                // Clean & validate the recipient (avoid "Any", blanks, etc.)
                val toClean = normalizeRecipient(to)
                if (toClean.equals("Any", ignoreCase = true) || toClean.isBlank() || !isValidEmail(toClean)) {
                    Log.e(TAG, "Invalid recipient for To header: '$to'")
                    return@withContext false
                }

                val email = createEmail(toClean, subject, body)
                val sendReq = service.users().messages().send("me", email)
                sendReq.execute()

                Log.d(TAG, "Email sent to $toClean")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error sending email", e)
                false
            }
        }
    }

    suspend fun sendEmailWithImageAttachment(
        to: String,
        subject: String,
        bodyText: String,
        attachmentFilename: String,
        attachmentBytes: ByteArray,
        mimeType: String = "image/jpeg"
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val service = gmailService
                if (service == null) {
                    Log.e(TAG, "Gmail service not initialized")
                    return@withContext false
                }

                val toClean = normalizeRecipient(to)
                if (toClean.equals("Any", ignoreCase = true) || toClean.isBlank() || !isValidEmail(toClean)) {
                    Log.e(TAG, "Invalid recipient for To header: '$to'")
                    return@withContext false
                }

                val boundary = "intraAI_${'$'}{System.currentTimeMillis()}"
                val sb = StringBuilder()
                sb.append("To: ").append(toClean).append("\r\n")
                sb.append("Subject: ").append(subject).append("\r\n")
                sb.append("MIME-Version: 1.0\r\n")
                sb.append("Content-Type: multipart/mixed; boundary=\"").append(boundary).append("\"\r\n\r\n")

                // Body part
                sb.append("--").append(boundary).append("\r\n")
                sb.append("Content-Type: text/plain; charset=UTF-8\r\n\r\n")
                sb.append(bodyText).append("\r\n\r\n")

                // Attachment part (Base64)
                val attachmentB64 = java.util.Base64.getEncoder().encodeToString(attachmentBytes)
                sb.append("--").append(boundary).append("\r\n")
                sb.append("Content-Type: ").append(mimeType).append("; name=\"").append(attachmentFilename).append("\"\r\n")
                sb.append("Content-Transfer-Encoding: base64\r\n")
                sb.append("Content-Disposition: attachment; filename=\"").append(attachmentFilename).append("\"\r\n\r\n")
                sb.append(attachmentB64).append("\r\n\r\n")

                sb.append("--").append(boundary).append("--\r\n")

                val encodedEmail = Base64.getUrlEncoder()
                    .encodeToString(sb.toString().toByteArray())
                    .replace("+", "-")
                    .replace("/", "_")
                    .replace("=", "")

                val message = Message().apply { raw = encodedEmail }
                service.users().messages().send("me", message).execute()
                Log.d(TAG, "Email with attachment sent to ${'$'}toClean")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error sending email with attachment", e)
                false
            }
        }
    }

    private fun createEmail(to: String, subject: String, bodyText: String): Message {
        // Gmail API expects an RFC 5322 message; use CRLF and include basic MIME headers.
        val emailContent = buildString {
            append("To: ").append(to).append("\r\n")
            append("Subject: ").append(subject).append("\r\n")
            append("MIME-Version: 1.0\r\n")
            append("Content-Type: text/plain; charset=UTF-8\r\n")
            append("\r\n")
            append(bodyText)
        }

        val encodedEmail = Base64.getUrlEncoder()
            .encodeToString(emailContent.toByteArray())
            .replace("+", "-")
            .replace("/", "_")
            .replace("=", "")

        return Message().apply { raw = encodedEmail }
    }

    suspend fun markMessageAsRead(messageId: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val service = gmailService
                if (service == null) {
                    Log.e(TAG, "Gmail service not initialized")
                    return@withContext false
                }

                val request = ModifyMessageRequest().apply {
                    removeLabelIds = listOf("UNREAD")
                }
                service.users().messages().modify("me", messageId, request).execute()
                Log.d(TAG, "Marked message $messageId as READ")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to mark message as read", e)
                false
            }
        }
    }
}
