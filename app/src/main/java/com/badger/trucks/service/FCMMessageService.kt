package com.badger.trucks.service

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.badger.trucks.util.RemoteLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class FCMMessageService : FirebaseMessagingService() {
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        
        RemoteLogger.i("FCM", "📨 Notification received")
        
        // Extract title and body from notification or data payload
        val title = remoteMessage.notification?.title ?: remoteMessage.data["title"] ?: "Badger Alert"
        val body = remoteMessage.notification?.body ?: remoteMessage.data["body"] ?: ""
        
        RemoteLogger.i("FCM", "Title: $title | Body: $body")
        
        // Show notification on phone
        showNotification(title, body)
    }
    
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        RemoteLogger.i("FCM", "🔄 FCM Token refreshed: ${token.substring(0, minOf(20, token.length))}...")
        
        // Save new token
        getSharedPreferences("badger_fcm", Context.MODE_PRIVATE)
            .edit().putString("fcm_token", token).apply()
        
        // Send to backend
        scope.launch {
            try {
                com.badger.trucks.data.BadgerRepo.updateFCMToken(token)
                RemoteLogger.i("FCM", "✅ New token sent to backend")
            } catch (e: Exception) {
                RemoteLogger.e("FCM", "Failed to send new token: ${e.message}")
            }
        }
    }
    
    private fun showNotification(title: String, body: String) {
        val notificationId = (System.currentTimeMillis() / 1000).toInt()
        
        val notification = NotificationCompat.Builder(this, "badger_alerts")
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(notificationId, notification)
            RemoteLogger.i("FCM", "✅ Phone notification shown")
        } catch (e: Exception) {
            RemoteLogger.e("FCM", "Failed to show notification: ${e.message}")
        }
    }
}
