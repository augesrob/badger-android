package com.badger.wear.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import com.badger.wear.BuildConfig
import com.badger.wear.WearApp
import com.badger.wear.WearDoor
import com.badger.wear.WearMainActivity
import com.badger.wear.WearStatus
import com.badger.wear.WearTruck
import com.badger.wear.updater.WearUpdateInfo
import com.badger.wear.updater.WearUpdater
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.Locale

class WearService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val NOTIF_ID          = 2001
        const val NOTIF_UPDATE_ID   = 2002
        const val ACTION_STOP       = "com.badger.wear.STOP"
        const val ACTION_PTT_START  = "com.badger.wear.PTT_START"
        const val ACTION_PTT_STOP   = "com.badger.wear.PTT_STOP"
        const val ACTION_INSTALL_UPDATE = "com.badger.wear.INSTALL_UPDATE"

        var isRunning = false

        private val _trucks   = MutableStateFlow<List<WearTruck>>(emptyList())
        private val _doors    = MutableStateFlow<List<WearDoor>>(emptyList())
        private val _statuses = MutableStateFlow<List<WearStatus>>(emptyList())
        private val _pttActive = MutableStateFlow(false)

        val trucks:    StateFlow<List<WearTruck>>  = _trucks.asStateFlow()
        val doors:     StateFlow<List<WearDoor>>   = _doors.asStateFlow()
        val statuses:  StateFlow<List<WearStatus>> = _statuses.asStateFlow()
        val pttActive: StateFlow<Boolean>          = _pttActive.asStateFlow()
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var realtimeJob: Job? = null

    private val supabase by lazy {
        createSupabaseClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY) {
            install(Postgrest)
            install(Realtime)
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startForeground(NOTIF_ID, buildNotification("Badger Watch — connecting..."))
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "badger:wear_wakelock").also { it.acquire() }
        tts = TextToSpeech(this, this)
        startRealtime()
        scope.launch { checkForUpdate() }
        Log.i("WearService", "Service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP      -> stopClean()
            ACTION_PTT_START -> startPtt()
            ACTION_PTT_STOP  -> stopPtt()
            ACTION_INSTALL_UPDATE -> {
                val url     = intent.getStringExtra("downloadUrl") ?: return START_STICKY
                val tagName = intent.getStringExtra("tagName") ?: return START_STICKY
                val ver     = intent.getIntExtra("versionCode", 0)
                scope.launch {
                    WearUpdater.downloadAndInstall(
                        this@WearService,
                        WearUpdateInfo(ver, tagName, url)
                    ) { msg -> Log.i("WearService", "Update: $msg") }
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        stopClean()
        super.onDestroy()
    }

    // ── Realtime ──────────────────────────────────────────────────────────────

    private fun startRealtime() {
        realtimeJob?.cancel()
        realtimeJob = scope.launch {
            val knownTruck = mutableMapOf<String, String?>()
            val knownDoor  = mutableMapOf<String, String?>()

            // Initial load
            try {
                val trucks   = supabase.from("live_movement").select().decodeList<WearTruck>()
                val doors    = supabase.from("loading_doors").select().decodeList<WearDoor>()
                val statuses = supabase.from("status_values").select().decodeList<WearStatus>()
                trucks.forEach   { knownTruck[it.truckNumber] = it.statusName }
                doors.forEach    { knownDoor[it.doorName]     = it.doorStatus }
                _trucks.value   = trucks
                _doors.value    = doors
                _statuses.value = statuses
                updateNotification("Badger Watch — Live ✅")
                Log.i("WearService", "Initial data loaded: ${trucks.size} trucks, ${doors.size} doors")
            } catch (e: Exception) {
                Log.w("WearService", "Initial load failed: ${e.message}")
                updateNotification("Badger Watch — Reconnecting...")
                delay(5000)
                startRealtime()
                return@launch
            }

            // Realtime subscription
            try {
                val channel = supabase.channel("badger-wear-${System.currentTimeMillis()}")

                channel.postgresChangeFlow<PostgresAction>("public") { table = "live_movement" }.onEach {
                    try {
                        val updated = supabase.from("live_movement").select().decodeList<WearTruck>()
                        updated.forEach { t ->
                            val prev = knownTruck[t.truckNumber]
                            if (prev != null && prev != t.statusName && t.statusName != null) {
                                val msg = "Truck ${t.truckNumber}, ${t.statusName}"
                                speak(msg)
                                postAlert("🚚 Truck ${t.truckNumber}", "$prev → ${t.statusName}")
                                Log.i("WearService", "TTS: $msg")
                            }
                            knownTruck[t.truckNumber] = t.statusName
                        }
                        _trucks.value = updated
                    } catch (e: Exception) { Log.w("WearService", "Truck update: ${e.message}") }
                }.launchIn(this)

                channel.postgresChangeFlow<PostgresAction>("public") { table = "loading_doors" }.onEach {
                    try {
                        val updated = supabase.from("loading_doors").select().decodeList<WearDoor>()
                        updated.forEach { d ->
                            val prev = knownDoor[d.doorName]
                            if (prev != null && prev != d.doorStatus && d.doorStatus.isNotBlank()) {
                                val msg = "Door ${d.doorName}, ${d.doorStatus}"
                                speak(msg)
                                postAlert("🚪 Door ${d.doorName}", "$prev → ${d.doorStatus}")
                                Log.i("WearService", "TTS: $msg")
                            }
                            knownDoor[d.doorName] = d.doorStatus
                        }
                        _doors.value = updated
                    } catch (e: Exception) { Log.w("WearService", "Door update: ${e.message}") }
                }.launchIn(this)

                channel.subscribe()
                Log.i("WearService", "Realtime subscribed ✅")
                updateNotification("Badger Watch — Live ✅")

                // Keep-alive heartbeat — if channel drops, restart
                while (isActive) {
                    delay(30_000)
                    if (channel.status.value.name != "SUBSCRIBED") {
                        Log.w("WearService", "Channel dropped — restarting realtime")
                        try { supabase.realtime.removeChannel(channel) } catch (_: Exception) {}
                        startRealtime()
                        return@launch
                    }
                }

                try { supabase.realtime.removeChannel(channel) } catch (_: Exception) {}

            } catch (e: Exception) {
                Log.w("WearService", "Realtime failed: ${e.message} — retrying in 10s")
                updateNotification("Badger Watch — Reconnecting...")
                delay(10_000)
                startRealtime()
            }
        }
    }

    // ── Status changes from watch UI ──────────────────────────────────────────

    fun changeTruckStatus(truckNumber: String, statusId: Int) {
        scope.launch {
            try {
                supabase.from("live_movement").update({ set("status_id", statusId) }) {
                    filter { eq("truck_number", truckNumber) }
                }
            } catch (e: Exception) { Log.e("WearService", "Truck status change failed: ${e.message}") }
        }
    }

    fun changeDoorStatus(doorId: Int, status: String) {
        scope.launch {
            try {
                supabase.from("loading_doors").update({ set("door_status", status) }) {
                    filter { eq("id", doorId) }
                }
            } catch (e: Exception) { Log.e("WearService", "Door status change failed: ${e.message}") }
        }
    }

    // ── PTT ───────────────────────────────────────────────────────────────────

    private fun startPtt() {
        _pttActive.value = true
        Log.i("WearService", "PTT started")
        // TODO: record via watch mic and upload to Supabase storage
    }

    private fun stopPtt() {
        _pttActive.value = false
        Log.i("WearService", "PTT stopped")
    }

    // ── TTS ───────────────────────────────────────────────────────────────────

    private fun speak(text: String) {
        if (!ttsReady || tts == null) { Log.w("WearService", "TTS not ready for: $text"); return }
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "wear_${System.currentTimeMillis()}")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            ttsReady = true
            speak("Badger watch active")
            Log.i("WearService", "TTS ready ✅")
        } else {
            Log.e("WearService", "TTS init failed: $status")
        }
    }

    // ── Notifications ─────────────────────────────────────────────────────────

    private fun postAlert(title: String, body: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(title.hashCode(), NotificationCompat.Builder(this, WearApp.CHANNEL_ALERTS)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build())
    }

    private fun buildNotification(status: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, WearMainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, WearService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, WearApp.CHANNEL_SERVICE)
            .setContentTitle("Badger Watch")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .build()
    }

    private fun updateNotification(status: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(status))
    }

    // ── Auto-update ───────────────────────────────────────────────────────────

    private suspend fun checkForUpdate() {
        try {
            val update = WearUpdater.checkForUpdate(BuildConfig.VERSION_CODE) ?: return
            Log.i("WearService", "Update available: ${update.tagName}")
            val installIntent = PendingIntent.getService(
                this, 99,
                Intent(this, WearService::class.java).apply {
                    action = ACTION_INSTALL_UPDATE
                    putExtra("downloadUrl", update.downloadUrl)
                    putExtra("tagName", update.tagName)
                    putExtra("versionCode", update.latestVersion)
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_UPDATE_ID, NotificationCompat.Builder(this, WearApp.CHANNEL_ALERTS)
                    .setContentTitle("Badger Update Available")
                    .setContentText("${update.tagName} — tap to install")
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setContentIntent(installIntent)
                    .build())
        } catch (e: Exception) { Log.w("WearService", "Update check failed: ${e.message}") }
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    private fun stopClean() {
        realtimeJob?.cancel()
        tts?.stop(); tts?.shutdown()
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
