package com.badger.trucks.service

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import com.badger.trucks.R
import com.badger.trucks.util.RemoteLogger
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class FCMMessageService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        try {
            RemoteLogger.i("FCM", "Message received from: ${message.from}")

            // Extract notification data
            val title = message.notification?.title ?: message.data["title"] ?: "Badger Notification"
            val body = message.notification?.body ?: message.data["body"] ?: ""

            RemoteLogger.i("FCM", "📬 Notification: $title - $body")

            // Show notification
            showNotification(title, body)
        } catch (e: Exception) {
            RemoteLogger.e("FCM", "Error handling message: ${e.message}")
        }
    }

    override fun onNewToken(token: String) {
        try {
            RemoteLogger.i("FCM", "🔑 New FCM token: ${token.take(20)}...")
            
            // Save token to SharedPreferences for later use
            val prefs = getSharedPreferences("badger_fcm", Context.MODE_PRIVATE)
            prefs.edit().putString("fcm_token", token).apply()
            
            // TODO: Send token to your backend server so it can send notifications
            // Example: BadgerRepo.updateFCMToken(token)
            
        } catch (e: Exception) {
            RemoteLogger.e("FCM", "Error saving token: ${e.message}")
        }
    }

    private fun showNotification(title: String, body: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notifId = System.currentTimeMillis().toInt()
            
            val notification = NotificationCompat.Builder(this, NotificationHelper.CHANNEL_TRUCK_STATUS)
                .setContentTitle(title)
                .setContentText(body)
                .setSmallIcon(R.drawable.badger_logo)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            
            nm.notify(notifId, notification)
            RemoteLogger.i("FCM", "✅ Notification shown: $title")
        } catch (e: Exception) {
            RemoteLogger.e("FCM", "Failed to show notification: ${e.message}")
        }
    }
}
