package com.badger.trucks.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import com.badger.trucks.MainActivity
import com.badger.trucks.data.BadgerRepo
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.Locale

class BadgerService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val CHANNEL_ID = "badger_service"
        const val NOTIF_ID = 1001
        const val ACTION_TOGGLE_TTS = "com.badger.trucks.TOGGLE_TTS"
        const val ACTION_STOP = "com.badger.trucks.STOP_SERVICE"
        var ttsEnabled = true
        var isRunning = false
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val knownStatuses = mutableMapOf<String, String?>()

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        tts = TextToSpeech(this, this)
        startRealtimeSync()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_TTS -> {
                ttsEnabled = !ttsEnabled
                updateNotification()
                if (ttsEnabled) speak("Text to speech enabled")
            }
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        tts?.stop()
        tts?.shutdown()
        scope.cancel()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            ttsReady = true
            speak("Badger live monitoring active")
        }
    }

    private fun speak(text: String) {
        if (ttsEnabled && ttsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "badger_${System.currentTimeMillis()}")
        }
    }

    private fun startRealtimeSync() {
        scope.launch {
            try {
                // Snapshot current state — don't announce on connect
                val initial = BadgerRepo.getLiveMovement()
                initial.forEach { knownStatuses[it.truckNumber] = it.statusName }

                val channel = BadgerRepo.realtimeChannel("badger-service-realtime")
                channel.postgresChangeFlow<PostgresAction>("public") {
                    table = "live_movement"
                }.onEach {
                    try {
                        val updated = BadgerRepo.getLiveMovement()
                        updated.forEach { truck ->
                            val prev = knownStatuses[truck.truckNumber]
                            val curr = truck.statusName
                            if (prev != null && curr != null && prev != curr) {
                                speak("Truck ${truck.truckNumber}, ${curr}")
                                Log.d("BadgerService", "TTS: Truck ${truck.truckNumber} $prev -> $curr")
                            }
                            knownStatuses[truck.truckNumber] = curr
                        }
                    } catch (e: Exception) {
                        Log.e("BadgerService", "Refresh error: ${e.message}")
                    }
                }.launchIn(scope)

                channel.subscribe()
                Log.d("BadgerService", "Realtime channel subscribed")
            } catch (e: Exception) {
                Log.e("BadgerService", "Realtime setup error: ${e.message}")
                delay(10_000)
                startRealtimeSync()
            }
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val ttsToggleIntent = PendingIntent.getService(
            this, 1,
            Intent(this, BadgerService::class.java).apply { action = ACTION_TOGGLE_TTS },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 2,
            Intent(this, BadgerService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🦡 Badger Live")
            .setContentText("Monitoring • TTS ${if (ttsEnabled) "ON 🔊" else "OFF 🔇"}")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_btn_speak_now, if (ttsEnabled) "🔊 TTS ON" else "🔇 TTS OFF", ttsToggleIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification())
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Badger Live Monitor",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Live truck status monitoring with TTS announcements"
            setShowBadge(false)
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }
}
