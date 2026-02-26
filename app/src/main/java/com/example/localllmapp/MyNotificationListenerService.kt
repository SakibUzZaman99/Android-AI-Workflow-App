package com.example.localllmapp

import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MyNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "NotificationListener"
        private const val GMAIL_PACKAGE = "com.google.android.gm"
        private const val TELEGRAM_PACKAGE = "org.telegram.messenger"
        private const val DEBOUNCE_MS = 3000L
    }

    private lateinit var workflowExecutor: WorkflowExecutor
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lastTrigger = mutableMapOf<String, Long>() // per-app debounce

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "NotificationListenerService created")
        workflowExecutor = WorkflowExecutor(this) // root package class
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn ?: return)

        val appName = when (sbn.packageName) {
            GMAIL_PACKAGE -> "Gmail"
            TELEGRAM_PACKAGE -> "Telegram"
            else -> return // ignore other apps
        }

        // Debounce bursts (e.g., Gmail summary + individual messages arriving together)
        val now = SystemClock.elapsedRealtime()
        val last = lastTrigger[appName] ?: 0L
        if (now - last < DEBOUNCE_MS) {
            Log.d(TAG, "Debounced $appName trigger")
            return
        }
        lastTrigger[appName] = now

        Log.d(TAG, "$appName notification detected")
        serviceScope.launch {
            try {
                // No notification extras parsing here: the executor/handlers fetch content
                workflowExecutor.processNotification(appName = appName)
            } catch (t: Throwable) {
                Log.e(TAG, "Executor failed for $appName", t)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        workflowExecutor.cleanup()
        Log.d(TAG, "NotificationListenerService destroyed")
    }
}
