package com.badger.trucks.service

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.google.android.gms.wearable.Wearable
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
        
        // Extract title and body
        val title = remoteMessage.notification?.title ?: "Badger Alert"
        val body = remoteMessage.notification?.body ?: ""
        
        // Also check data payload
        val data = remoteMessage.data
        val dataTitle = data["title"] ?: title
        val dataBody = data["body"] ?: body
        
        RemoteLogger.i("FCM", "Title: $dataTitle | Body: $dataBody")
        
        // Show notification on phone
        showNotification(dataTitle, dataBody)
        
        // Forward to paired watch
        forwardToWatch(dataTitle, dataBody, data)
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
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.notify(notificationId, notification)
            RemoteLogger.i("FCM", "✅ Phone notification shown")
        } catch (e: Exception) {
            RemoteLogger.e("FCM", "Failed to show notification: ${e.message}")
        }
    }
    
    private fun forwardToWatch(title: String, body: String, data: Map<String, String>) {
        scope.launch {
            try {
                // Prepare payload for watch
                val payload = mapOf(
                    "title" to title,
                    "body" to body,
                    "timestamp" to System.currentTimeMillis().toString()
                ).toMutableMap()
                
                // Add any extra data fields
                payload.putAll(data.filterKeys { it !in listOf("title", "body") })
                
                // Send to watch via Wearable Data Layer
                val dataClient = Wearable.getDataClient(this@FCMMessageService)
                val request = com.google.android.gms.wearable.PutDataRequest.create("/badger/notification")
                
                // Add data items
                payload.forEach { (key, value) ->
                    request.dataMap.putString(key, value)
                }
                request.dataMap.putLong("timestamp", System.currentTimeMillis())
                
                dataClient.putDataItem(request).addOnSuccessListener {
                    RemoteLogger.i("FCM", "✅ Forwarded to watch via Wearable API")
                }.addOnFailureListener { e ->
                    RemoteLogger.e("FCM", "❌ Failed to forward to watch: ${e.message}")
                }
            } catch (e: Exception) {
                RemoteLogger.e("FCM", "Error forwarding to watch: ${e.message}")
            }
        }
    }
}
