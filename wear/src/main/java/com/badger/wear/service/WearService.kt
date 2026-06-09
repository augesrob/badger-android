package com.badger.wear.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import com.badger.wear.BuildConfig
import com.badger.wear.WearApp
import com.badger.wear.WearDoor
import com.badger.wear.WearMainActivity
import com.badger.wear.WearStatus
import com.badger.wear.PttMessage
import com.badger.wear.WearPrintroomEntry
import com.badger.wear.WearTruck
import com.badger.wear.updater.WearUpdateInfo
import com.badger.wear.updater.WearUpdater
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.badger.wear.util.WearLogger
import com.badger.wear.util.WearLogShipper
import java.io.File
import java.util.Locale

class WearService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val NOTIF_ID              = 2001
        const val ACTION_STOP           = "com.badger.wear.STOP"
        const val ACTION_PTT_START      = "com.badger.wear.PTT_START"
        const val ACTION_PTT_STOP       = "com.badger.wear.PTT_STOP"
        const val ACTION_STATUS_CHANGE  = "com.badger.wear.STATUS_CHANGE"
        const val ACTION_DOOR_CHANGE    = "com.badger.wear.DOOR_CHANGE"

        var isRunning = false

        private val _trucks       = MutableStateFlow<List<WearTruck>>(emptyList())
        private val _doors        = MutableStateFlow<List<WearDoor>>(emptyList())
        private val _statuses     = MutableStateFlow<List<WearStatus>>(emptyList())
        private val _pttActive    = MutableStateFlow(false)
        private val _doorStatuses    = MutableStateFlow<List<String>>(emptyList())
        private val _printroom        = MutableStateFlow<List<WearPrintroomEntry>>(emptyList())

        val trucks:       StateFlow<List<WearTruck>>  = _trucks.asStateFlow()
        val doors:        StateFlow<List<WearDoor>>   = _doors.asStateFlow()
        val statuses:     StateFlow<List<WearStatus>> = _statuses.asStateFlow()
        val pttActive:    StateFlow<Boolean>          = _pttActive.asStateFlow()
        val doorStatuses: StateFlow<List<String>>     = _doorStatuses.asStateFlow()
        val printroom:    StateFlow<List<WearPrintroomEntry>> = _printroom.asStateFlow()
    }

    // Single supervisor scope — wakelock keeps it alive through downloads
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var realtimeJob: Job? = null
    private var updateAttempted = false   // only try once per service instance

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    // Persisted so "Badger watch active" only fires once per app install, not every service restart
    private val ttsSpokenWelcome get() = getSharedPreferences("badger_wear", Context.MODE_PRIVATE).getBoolean("tts_welcomed", false)
    private fun markTtsWelcomeDone() = getSharedPreferences("badger_wear", Context.MODE_PRIVATE).edit().putBoolean("tts_welcomed", true).apply()
    private var wakeLock: PowerManager.WakeLock? = null

    // PTT
    private var recorder: MediaRecorder? = null
    private var pttFile: File? = null

    private val supabase by lazy {
        createSupabaseClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY) {
            install(Postgrest)
            install(Realtime)
            install(Storage)
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startForeground(NOTIF_ID, buildNotification("Badger Watch — connecting..."))
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        // FULL wakelock — keeps CPU alive through downloads
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "badger:wear_wakelock").also {
            it.acquire(10 * 60 * 1000L) // max 10 min
        }
        tts = TextToSpeech(this, this)
        WearLogger.init(this)
        WearLogger.i("WearService", "Service started v${BuildConfig.VERSION_CODE}")
        startRealtime()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopClean()
            ACTION_PTT_START -> startPtt()
            ACTION_PTT_STOP  -> stopPtt()
            ACTION_STATUS_CHANGE -> {
                WearLogger.i("WearService", "STATUS_CHANGE received")
                val truckNumber = intent.getStringExtra("truckNumber") ?: return START_STICKY
                val statusId    = intent.getIntExtra("statusId", -1)
                scope.launch {
                    try {
                        supabase.from("live_movement").update({ set("status_id", statusId) }) {
                            filter { eq("truck_number", truckNumber) }
                        }
                        WearLogger.i("WearService", "Status changed: $truckNumber -> $statusId")
                    } catch (e: Exception) { WearLogger.e("WearService", "Status change failed: ${e.message}") }
                }
            }
            ACTION_DOOR_CHANGE -> {
                WearLogger.i("WearService", "DOOR_CHANGE received")
                val doorId = intent.getIntExtra("doorId", -1)
                val status = intent.getStringExtra("status") ?: return START_STICKY
                scope.launch {
                    try {
                        supabase.from("loading_doors").update({ set("door_status", status) }) {
                            filter { eq("id", doorId) }
                        }
                        WearLogger.i("WearService", "Door changed: $doorId -> $status")
                    } catch (e: Exception) { WearLogger.e("WearService", "Door change failed: ${e.message}") }
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

            try {
                val trucks   = supabase.from("live_movement").select(Columns.raw("truck_number, status_id, current_location, loading_door_id, status_values(status_name, status_color)")).decodeList<WearTruck>()
                val doors    = supabase.from("loading_doors").select(Columns.raw("id, door_name, door_status, sort_order")).decodeList<WearDoor>()
                val statuses = supabase.from("status_values").select(Columns.raw("id, status_name, status_color")).decodeList<WearStatus>()
                trucks.forEach { knownTruck[it.truckNumber] = it.statusName }
                doors.forEach  { knownDoor[it.doorName]    = it.doorStatus }
                _trucks.value   = trucks
                _doors.value    = doors
                _statuses.value = statuses
                try {
                    val ds = supabase.from("door_status_values").select(Columns.raw("status_name")) {
                        filter { eq("is_active", true) }
                    }.decodeList<JsonObject>()
                    _doorStatuses.value = ds.mapNotNull { it["status_name"]?.jsonPrimitive?.content }
                } catch (_: Exception) {}
                updateNotification("Badger Watch — Live ✅")
                // Load printroom entries for door grouping
                try {
                    val pr = supabase.from("printroom_entries").select(Columns.raw("truck_number, loading_door_id, batch_number, row_order, is_end_marker"))
                        .decodeList<WearPrintroomEntry>()
                    _printroom.value = pr.filter { it.isEndMarker != true && it.truckNumber != null }
                } catch (_: Exception) {}
                WearLogger.i("WearService", "Initial data loaded: ${trucks.size} trucks, ${doors.size} doors")

                // Check for update ONCE after successful data load — inside scope so wakelock covers it
                if (!updateAttempted) {
                    updateAttempted = true
                    launch { checkForUpdate() }
                }
            } catch (e: Exception) {
                WearLogger.w("WearService", "Initial load failed: ${e.message}")
                updateNotification("Badger Watch — Reconnecting...")
                delay(5000)
                startRealtime()
                return@launch
            }

            try {
                val channel = supabase.channel("badger-wear-${System.currentTimeMillis()}")

                channel.postgresChangeFlow<PostgresAction>("public") { table = "live_movement" }.onEach {
                    try {
                        val updated = supabase.from("live_movement").select(Columns.raw("truck_number, status_id, current_location, loading_door_id, status_values(status_name, status_color)")).decodeList<WearTruck>()
                        updated.forEach { t ->
                            val prev = knownTruck[t.truckNumber]
                            if (prev != null && prev != t.statusName && t.statusName != null) {
                                val msg = "Truck ${t.truckNumber}, ${t.statusName}"
                                speak(msg)
                                postAlert("🚚 Truck ${t.truckNumber}", "$prev → ${t.statusName}")
                                WearLogger.i("WearService", "TTS: $msg")
                            }
                            knownTruck[t.truckNumber] = t.statusName
                        }
                        _trucks.value = updated
                    } catch (e: Exception) { WearLogger.w("WearService", "Truck update: ${e.message}") }
                }.launchIn(this)

                channel.postgresChangeFlow<PostgresAction>("public") { table = "loading_doors" }.onEach {
                    try {
                        val updated = supabase.from("loading_doors").select(Columns.raw("id, door_name, door_status, sort_order")).decodeList<WearDoor>()
                        updated.forEach { d ->
                            val prev = knownDoor[d.doorName]
                            if (prev != null && prev != d.doorStatus && d.doorStatus.isNotBlank()) {
                                val msg = "Door ${d.doorName}, ${d.doorStatus}"
                                speak(msg)
                                postAlert("🚪 Door ${d.doorName}", "$prev → ${d.doorStatus}")
                                WearLogger.i("WearService", "TTS: $msg")
                            }
                            knownDoor[d.doorName] = d.doorStatus
                        }
                        _doors.value = updated
                    } catch (e: Exception) { WearLogger.w("WearService", "Door update: ${e.message}") }
                }.launchIn(this)

                channel.subscribe()
                WearLogger.i("WearService", "Realtime subscribed ✅")
                updateNotification("Badger Watch — Live ✅")

                while (isActive) {
                    delay(30_000)
                    WearLogger.getContext()?.let { WearLogShipper.ship(it) }
                    if (channel.status.value.name != "SUBSCRIBED") {
                        WearLogger.w("WearService", "Channel dropped — restarting realtime")
                        try { supabase.realtime.removeChannel(channel) } catch (_: Exception) {}
                        startRealtime()
                        return@launch
                    }
                }
                try { supabase.realtime.removeChannel(channel) } catch (_: Exception) {}

            } catch (e: Exception) {
                WearLogger.w("WearService", "Realtime failed: ${e.message} — retrying in 10s")
                updateNotification("Badger Watch — Reconnecting...")
                delay(10_000)
                startRealtime()
            }
        }
    }

    // ── PTT ───────────────────────────────────────────────────────────────────

    private fun startPtt() {
        if (_pttActive.value) return
        _pttActive.value = true
        WearLogger.i("WearService", "PTT recording started")
        try {
            val file = File(cacheDir, "ptt_${System.currentTimeMillis()}.m4a")
            pttFile = file
            recorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16000)
                setAudioEncodingBitRate(32000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
        } catch (e: Exception) {
            WearLogger.e("WearService", "PTT record start failed: ${e.message}")
            _pttActive.value = false
        }
    }

    private fun stopPtt() {
        if (!_pttActive.value) return
        _pttActive.value = false
        val file = pttFile ?: return
        try {
            recorder?.apply { stop(); release() }
            recorder = null
            WearLogger.i("WearService", "PTT recording stopped — uploading ${file.length()} bytes")
            scope.launch { uploadPtt(file) }
        } catch (e: Exception) {
            WearLogger.e("WearService", "PTT stop failed: ${e.message}")
        }
    }

    private suspend fun uploadPtt(file: File) {
        try {
            val bytes = file.readBytes()
            val path  = "ptt/${System.currentTimeMillis()}_watch.m4a"
            supabase.storage.from("audio").upload(path, bytes)
            WearLogger.i("WearService", "PTT uploaded to $path")
            // Insert record so phone/web pick it up via realtime
            supabase.from("ptt_messages").insert(
                PttMessage(
                    audioUrl   = "${BuildConfig.SUPABASE_URL}/storage/v1/object/public/audio/$path",
                    sender     = "watch",
                    durationMs = (bytes.size / 16)
                )
            )
            file.delete()
        } catch (e: Exception) {
            WearLogger.e("WearService", "PTT upload failed: ${e.message}")
        }
    }

    // ── TTS ───────────────────────────────────────────────────────────────────

    private fun speak(text: String) {
        if (!ttsReady || tts == null) return
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "wear_${System.currentTimeMillis()}")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            ttsReady = true
            if (!ttsSpokenWelcome) { markTtsWelcomeDone(); speak("Badger watch active") }
            WearLogger.i("WearService", "TTS ready ✅")
        } else {
            WearLogger.e("WearService", "TTS init failed: $status")
        }
    }

    // ── Notifications ─────────────────────────────────────────────────────────

    private fun postAlert(title: String, body: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(title.hashCode(), NotificationCompat.Builder(this, WearApp.CHANNEL_ALERTS)
            .setContentTitle(title).setContentText(body)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).build())
    }

    private fun buildNotification(status: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, WearMainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, WearService::class.java).apply { action = ACTION_STOP }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, WearApp.CHANNEL_SERVICE)
            .setContentTitle("Badger Watch").setContentText(status)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(open).setOngoing(true).setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop).build()
    }

    private fun updateNotification(status: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification(status))
    }

    // ── Auto-update ───────────────────────────────────────────────────────────

    private suspend fun checkForUpdate() {
        try {
            val update = WearUpdater.checkForUpdate(BuildConfig.VERSION_CODE) ?: return
            WearLogger.i("WearService", "Update found: ${update.tagName} — auto-downloading")
            updateNotification("Badger Watch — Updating ${update.tagName}...")
            WearUpdater.downloadAndInstall(this, update) { msg -> WearLogger.i("WearService", "Update: $msg") }
        } catch (e: Exception) { WearLogger.w("WearService", "Update check failed: ${e.message}") }
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    private fun stopClean() {
        realtimeJob?.cancel()
        recorder?.runCatching { stop(); release() }
        tts?.stop(); tts?.shutdown()
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
