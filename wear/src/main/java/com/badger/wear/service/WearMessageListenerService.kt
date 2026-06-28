package com.badger.wear.service

import android.app.NotificationManager
import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import com.badger.wear.WearApp
import com.badger.wear.util.WearLogger
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Receives messages from the phone app via the Wearable Data Layer.
 * Wear OS keeps this service dormant and wakes it automatically when a message
 * arrives on the /badger/alert path — no watch app needs to be open or running.
 *
 * Message format (UTF-8 string): "title|body"
 * e.g. "Door 13A|Loading" or "Truck 42|In Door"
 */
class WearMessageListenerService : WearableListenerService(), TextToSpeech.OnInitListener {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingText: String? = null

    override fun onCreate() {
        super.onCreate()
        WearLogger.init(this)
        tts = TextToSpeech(this, this)
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != "/badger/alert") return

        val payload = String(event.data, Charsets.UTF_8)
        val parts   = payload.split("|", limit = 2)
        val title   = parts.getOrElse(0) { "Badger" }
        val body    = parts.getOrElse(1) { "" }
        val ttsText = if (body.isNotBlank()) "$title, $body" else title

        WearLogger.i("WearMsgListener", "Received: $payload")

        // Show notification on watch
        showNotification(title, body)

        // Speak via TTS
        if (ttsReady) {
            speak(ttsText)
        } else {
            pendingText = ttsText
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            ttsReady = true
            WearLogger.i("WearMsgListener", "TTS ready")
            // Speak anything that arrived before TTS finished initializing
            pendingText?.let {
                scope.launch {
                    delay(300L)
                    speak(it)
                    pendingText = null
                }
            }
        } else {
            WearLogger.e("WearMsgListener", "TTS init failed: $status")
        }
    }

    private fun speak(text: String) {
        try {
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "badger_${System.currentTimeMillis()}")
            WearLogger.i("WearMsgListener", "TTS: $text")
        } catch (e: Exception) {
            WearLogger.e("WearMsgListener", "TTS speak error: ${e.message}")
        }
    }

    private fun showNotification(title: String, body: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(
                (title + body).hashCode(),
                NotificationCompat.Builder(this, WearApp.CHANNEL_ALERTS)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .build()
            )
        } catch (e: Exception) {
            WearLogger.e("WearMsgListener", "Notification error: ${e.message}")
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
