package com.badger.trucks.service

import android.app.NotificationManager
import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.wearable.Wearable
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.badger.trucks.util.RemoteLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Locale

class FCMMessageService : FirebaseMessagingService(), TextToSpeech.OnInitListener {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingText: String? = null

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            ttsReady = true
            pendingText?.let { text ->
                pendingText = null
                tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "fcm_${System.currentTimeMillis()}")
            }
        } else {
            RemoteLogger.e("FCM", "TTS init failed: $status")
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        val title = remoteMessage.notification?.title
            ?: remoteMessage.data["title"]
            ?: "Badger Alert"
        val body = remoteMessage.notification?.body
            ?: remoteMessage.data["body"]
            ?: ""

        RemoteLogger.i("FCM", "✅ Received: $title | $body")

        // 1. Speak via TTS — if BadgerService is already running it handles its own TTS
        //    via Realtime, but FCM is the guaranteed delivery path so we always speak here too.
        //    BadgerService deduplicates via knownStatuses so the Realtime path won't double-speak.
        val ttsText = if (body.isNotBlank()) "$title, $body" else title
        speakNow(ttsText)

        // 2. Show notification on phone
        showNotification(title, body)

        // 3. Forward to watch via Wearable Data Layer — wakes WearMessageListenerService
        scope.launch {
            forwardToWatch(title, body)
        }
    }

    private fun speakNow(text: String) {
        try {
            if (ttsReady && tts != null) {
                tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "fcm_${System.currentTimeMillis()}")
                RemoteLogger.i("FCM", "🔊 TTS: $text")
            } else {
                // TTS not ready yet — store and speak once onInit fires
                pendingText = text
                RemoteLogger.i("FCM", "🔊 TTS pending (not ready yet): $text")
            }
        } catch (e: Exception) {
            RemoteLogger.e("FCM", "TTS speak error: ${e.message}")
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

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
