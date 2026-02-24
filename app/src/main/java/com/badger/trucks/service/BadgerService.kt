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
        const val NOTIF_ID          = 1001
        const val ACTION_TOGGLE_TTS = "com.badger.trucks.TOGGLE_TTS"
        const val ACTION_STOP       = "com.badger.trucks.STOP_SERVICE"
        var ttsEnabled = true
        var isRunning  = false
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val knownStatuses = mutableMapOf<String, String?>()
    private val knownDoorStatus = mutableMapOf<String, String?>()
    private val knownPreshift = mutableMapOf<Int, Pair<String?, String?>>()

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        NotificationHelper.createAllChannels(this)
        startForeground(NOTIF_ID, buildServiceNotification())
        tts = TextToSpeech(this, this)
        startRealtimeSync()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_TTS -> {
                ttsEnabled = !ttsEnabled
                updateServiceNotification()
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
        val ttsOn = NotificationPrefsStore.get(this, NotificationPrefsStore.KEY_CHANNEL_TTS)
        if (ttsEnabled && ttsReady && ttsOn) {
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "badger_${System.currentTimeMillis()}")
        }
    }

    private fun canNotify(eventKey: String): Boolean {
        val eventOn   = NotificationPrefsStore.get(this, eventKey)
        val channelOn = NotificationPrefsStore.get(this, NotificationPrefsStore.KEY_CHANNEL_APP)
        return eventOn && channelOn
    }

    private fun pushNotif(channelId: String, title: String, body: String, tag: String? = null) {
        NotificationHelper.postNotification(this, channelId, title, body, tag)
    }

    private fun startRealtimeSync() {
        scope.launch {
            try {
                // Snapshot — no alerts on initial connect
                BadgerRepo.getLiveMovement().forEach { knownStatuses[it.truckNumber] = it.statusName }
                BadgerRepo.getLoadingDoors().forEach { knownDoorStatus[it.doorName] = it.doorStatus }
                BadgerRepo.getStagingDoors().forEach { knownPreshift[it.id] = Pair(it.inFront, it.inBack) }

                val channel = BadgerRepo.realtimeChannel("badger-service-realtime")

                // Truck status
                channel.postgresChangeFlow<PostgresAction>("public") {
                    table = "live_movement"
                }.onEach {
                    try {
                        val updated = BadgerRepo.getLiveMovement()
                        updated.forEach { truck ->
                            val prev = knownStatuses[truck.truckNumber]
                            val curr = truck.statusName
                            if (prev != null && curr != null && prev != curr) {
                                speak("Truck ${truck.truckNumber}, $curr")
                                Log.d("BadgerService", "Truck ${truck.truckNumber}: $prev -> $curr")
                                if (canNotify(NotificationPrefsStore.KEY_TRUCK_STATUS)) {
                                    pushNotif(
                                        channelId = NotificationHelper.CHANNEL_TRUCK_STATUS,
                                        title     = "🚚 Truck ${truck.truckNumber}",
                                        body      = "$prev → $curr${truck.currentLocation?.let { " @ $it" } ?: ""}",
                                        tag       = "truck_${truck.truckNumber}"
                                    )
                                }
                            }
                            knownStatuses[truck.truckNumber] = curr
                        }
                        val currentNums = updated.map { it.truckNumber }.toSet()
                        knownStatuses.keys.filter { it !in currentNums }.forEach { knownStatuses.remove(it) }
                    } catch (e: Exception) {
                        Log.e("BadgerService", "Truck refresh error: ${e.message}")
                    }
                }.launchIn(scope)

                // Door status
                channel.postgresChangeFlow<PostgresAction>("public") {
                    table = "loading_doors"
                }.onEach {
                    try {
                        val updated = BadgerRepo.getLoadingDoors()
                        updated.forEach { door ->
                            val prev = knownDoorStatus[door.doorName]
                            val curr = door.doorStatus
                            if (prev != null && curr != prev && curr.isNotBlank()) {
                                speak("Door ${door.doorName}, $curr")
                                Log.d("BadgerService", "Door ${door.doorName}: $prev -> $curr")
                                if (canNotify(NotificationPrefsStore.KEY_DOOR_STATUS)) {
                                    pushNotif(
                                        channelId = NotificationHelper.CHANNEL_DOOR_STATUS,
                                        title     = "🚪 Door ${door.doorName}",
                                        body      = "$prev → $curr",
                                        tag       = "door_${door.doorName}"
                                    )
                                }
                            }
                            knownDoorStatus[door.doorName] = curr
                        }
                    } catch (e: Exception) {
                        Log.e("BadgerService", "Door refresh error: ${e.message}")
                    }
                }.launchIn(scope)

                // PreShift
                channel.postgresChangeFlow<PostgresAction>("public") {
                    table = "staging_doors"
                }.onEach {
                    try {
                        val updated = BadgerRepo.getStagingDoors()
                        val changes = mutableListOf<String>()
                        updated.forEach { door ->
                            val prev = knownPreshift[door.id]
                            val curr = Pair(door.inFront, door.inBack)
                            if (prev != null && prev != curr) {
                                if (prev.first != door.inFront)
                                    changes.add("${door.doorLabel} front: ${prev.first ?: "empty"} → ${door.inFront ?: "empty"}")
                                if (prev.second != door.inBack)
                                    changes.add("${door.doorLabel} back: ${prev.second ?: "empty"} → ${door.inBack ?: "empty"}")
                            }
                            knownPreshift[door.id] = curr
                        }
                        if (changes.isNotEmpty()) {
                            speak("Preshift updated")
                            Log.d("BadgerService", "PreShift: ${changes.joinToString()}")
                            if (canNotify(NotificationPrefsStore.KEY_PRESHIFT)) {
                                pushNotif(
                                    channelId = NotificationHelper.CHANNEL_PRESHIFT,
                                    title     = "📋 PreShift Updated",
                                    body      = changes.joinToString("\n"),
                                    tag       = "preshift_change"
                                )
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("BadgerService", "PreShift refresh error: ${e.message}")
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

    private fun buildServiceNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
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
        return NotificationCompat.Builder(this, NotificationHelper.CHANNEL_SERVICE)
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

    private fun updateServiceNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildServiceNotification())
    }
}
