package com.badger.trucks.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import com.badger.trucks.MainActivity
import com.badger.trucks.data.BadgerRepo
import com.badger.trucks.voice.PushToTalkManager
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.Locale

class BadgerService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val NOTIF_ID           = 1001
        const val ACTION_TOGGLE_TTS  = "com.badger.trucks.TOGGLE_TTS"
        const val ACTION_PTT_START   = "com.badger.trucks.PTT_START"
        const val ACTION_PTT_STOP    = "com.badger.trucks.PTT_STOP"
        const val ACTION_STOP        = "com.badger.trucks.STOP_SERVICE"

        var ttsEnabled = true
        var isRunning  = false

        // UI observes these to show PTT state without holding a reference to PTT manager
        private val _pttRecording = MutableStateFlow(false)
        private val _pttIncoming  = MutableStateFlow(false)
        val pttRecording: StateFlow<Boolean> = _pttRecording.asStateFlow()
        val pttIncoming:  StateFlow<Boolean> = _pttIncoming.asStateFlow()
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var pttManager: PushToTalkManager? = null

    // State snapshots for change detection
    private val knownStatuses   = mutableMapOf<String, String?>()
    private val knownDoorStatus = mutableMapOf<String, String?>()
    private val knownPreshift   = mutableMapOf<Int, Pair<String?, String?>>()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        isRunning = true

        // Acquire PARTIAL_WAKE_LOCK — keeps CPU running when screen is off.
        // This is the key fix: without this, coroutines pause and channels drop.
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "badger:ptt_wakelock"
        ).also { it.acquire() }
        Log.d("BadgerService", "WakeLock acquired")

        NotificationHelper.createAllChannels(this)
        startForeground(NOTIF_ID, buildServiceNotification())

        tts = TextToSpeech(this, this)

        // PTT is owned by the service — survives screen off and background
        pttManager = PushToTalkManager(this, scope).also { mgr ->
            mgr.onIncoming = {
                _pttIncoming.value = true
                // Also fire a heads-up notification so user sees it even on lock screen
                NotificationHelper.postNotification(
                    context   = this,
                    channelId = NotificationHelper.CHANNEL_SYSTEM,
                    title     = "📻 Incoming PTT",
                    body      = "Someone is talking on the radio",
                    tag       = "ptt_incoming"
                )
            }
            mgr.onDone = {
                _pttIncoming.value = false
            }
            mgr.startListening()
        }

        startRealtimeSync()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_TTS -> {
                ttsEnabled = !ttsEnabled
                updateServiceNotification()
                if (ttsEnabled) speak("Text to speech enabled")
            }
            // PTT record start/stop triggered from UI via Intent
            ACTION_PTT_START -> {
                _pttRecording.value = true
                pttManager?.startRecording()
            }
            ACTION_PTT_STOP -> {
                _pttRecording.value = false
                pttManager?.stopRecording()
            }
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        _pttRecording.value = false
        _pttIncoming.value  = false
        pttManager?.destroy()
        tts?.stop(); tts?.shutdown()
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        Log.d("BadgerService", "WakeLock released")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            ttsReady = true
            speak("Badger live monitoring active")
        }
    }

    // ── TTS ───────────────────────────────────────────────────────────────────

    private fun speak(text: String) {
        val ttsOn = NotificationPrefsStore.get(this, NotificationPrefsStore.KEY_CHANNEL_TTS)
        if (ttsEnabled && ttsReady && ttsOn) {
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "badger_${System.currentTimeMillis()}")
        }
    }

    // ── Push notification helpers ─────────────────────────────────────────────

    private fun canNotify(eventKey: String): Boolean {
        val eventOn   = NotificationPrefsStore.get(this, eventKey)
        val channelOn = NotificationPrefsStore.get(this, NotificationPrefsStore.KEY_CHANNEL_APP)
        return eventOn && channelOn
    }

    private fun pushNotif(channelId: String, title: String, body: String, tag: String? = null) {
        NotificationHelper.postNotification(this, channelId, title, body, tag)
    }

    // ── Realtime data sync ────────────────────────────────────────────────────

    private fun startRealtimeSync() {
        scope.launch {
            try {
                // Snapshot current state — don't alert on first connect
                BadgerRepo.getLiveMovement().forEach { knownStatuses[it.truckNumber] = it.statusName }
                BadgerRepo.getLoadingDoors().forEach { knownDoorStatus[it.doorName]  = it.doorStatus }
                BadgerRepo.getStagingDoors().forEach { knownPreshift[it.id]          = Pair(it.inFront, it.inBack) }

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
                                if (canNotify(NotificationPrefsStore.KEY_TRUCK_STATUS)) {
                                    pushNotif(
                                        NotificationHelper.CHANNEL_TRUCK_STATUS,
                                        "🚚 Truck ${truck.truckNumber}",
                                        "$prev → $curr${truck.currentLocation?.let { " @ $it" } ?: ""}",
                                        "truck_${truck.truckNumber}"
                                    )
                                }
                            }
                            knownStatuses[truck.truckNumber] = curr
                        }
                        val curr = updated.map { it.truckNumber }.toSet()
                        knownStatuses.keys.filter { it !in curr }.forEach { knownStatuses.remove(it) }
                    } catch (e: Exception) { Log.e("BadgerService", "Truck refresh error: ${e.message}") }
                }.launchIn(scope)

                // Door status
                channel.postgresChangeFlow<PostgresAction>("public") {
                    table = "loading_doors"
                }.onEach {
                    try {
                        BadgerRepo.getLoadingDoors().forEach { door ->
                            val prev = knownDoorStatus[door.doorName]
                            val curr = door.doorStatus
                            if (prev != null && curr != prev && curr.isNotBlank()) {
                                speak("Door ${door.doorName}, $curr")
                                if (canNotify(NotificationPrefsStore.KEY_DOOR_STATUS)) {
                                    pushNotif(
                                        NotificationHelper.CHANNEL_DOOR_STATUS,
                                        "🚪 Door ${door.doorName}",
                                        "$prev → $curr",
                                        "door_${door.doorName}"
                                    )
                                }
                            }
                            knownDoorStatus[door.doorName] = curr
                        }
                    } catch (e: Exception) { Log.e("BadgerService", "Door refresh error: ${e.message}") }
                }.launchIn(scope)

                // PreShift
                channel.postgresChangeFlow<PostgresAction>("public") {
                    table = "staging_doors"
                }.onEach {
                    try {
                        val changes = mutableListOf<String>()
                        BadgerRepo.getStagingDoors().forEach { door ->
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
                            if (canNotify(NotificationPrefsStore.KEY_PRESHIFT)) {
                                pushNotif(
                                    NotificationHelper.CHANNEL_PRESHIFT,
                                    "📋 PreShift Updated",
                                    changes.joinToString("\n"),
                                    "preshift_change"
                                )
                            }
                        }
                    } catch (e: Exception) { Log.e("BadgerService", "PreShift refresh error: ${e.message}") }
                }.launchIn(scope)

                channel.subscribe()
                Log.d("BadgerService", "Realtime subscribed")

            } catch (e: Exception) {
                Log.e("BadgerService", "Realtime setup error: ${e.message}")
                delay(10_000)
                startRealtimeSync()
            }
        }
    }

    // ── Foreground notification ───────────────────────────────────────────────

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
            .setContentText("Monitoring • PTT ready • TTS ${if (ttsEnabled) "ON 🔊" else "OFF 🔇"}")
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
