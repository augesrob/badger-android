package com.badger.trucks.service

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.wearable.Wearable
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.badger.trucks.util.RemoteLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class FCMMessageService : FirebaseMessagingService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        val title = remoteMessage.notification?.title
            ?: remoteMessage.data["title"]
            ?: "Badger Alert"
        val body = remoteMessage.notification?.body
            ?: remoteMessage.data["body"]
            ?: ""

        RemoteLogger.i("FCM", "✅ Received: $title | $body")

        // 1. Show notification on phone
        showNotification(title, body)

        // 2. Forward to watch via Wearable Data Layer — wakes WearMessageListenerService
        scope.launch {
            forwardToWatch(title, body)
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        RemoteLogger.i("FCM", "🔄 FCM Token refreshed: ${token.take(20)}...")
        getSharedPreferences("badger_fcm", Context.MODE_PRIVATE)
            .edit().putString("fcm_token", token).apply()
        scope.launch {
            try {
                com.badger.trucks.data.BadgerRepo.updateFCMToken(token)
                RemoteLogger.i("FCM", "✅ New token sent to backend")
            } catch (e: Exception) {
                RemoteLogger.e("FCM", "Failed to send new token: ${e.message}")
            }
        }
    }

    private suspend fun forwardToWatch(title: String, body: String) {
        try {
            val payload = "$title|$body"
            val nodes = Wearable.getNodeClient(this).connectedNodes.await()
            if (nodes.isEmpty()) {
                RemoteLogger.w("FCM", "No watch nodes connected")
                return
            }
            val msgClient = Wearable.getMessageClient(this)
            for (node in nodes) {
                msgClient.sendMessage(node.id, "/badger/alert", payload.toByteArray(Charsets.UTF_8)).await()
                RemoteLogger.i("FCM", "✅ Forwarded to watch node ${node.displayName}: $payload")
            }
        } catch (e: Exception) {
            RemoteLogger.e("FCM", "Failed to forward to watch: ${e.message}")
        }
    }

    private fun showNotification(title: String, body: String) {
        val notification = NotificationCompat.Builder(this, "badger_alerts")
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify((System.currentTimeMillis() / 1000).toInt(), notification)
        } catch (e: Exception) {
            RemoteLogger.e("FCM", "Failed to show notification: ${e.message}")
        }
    }
}
