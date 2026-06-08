package com.badger.wear.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.IBinder
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import com.badger.wear.BuildConfig
import com.badger.wear.WearApp
import com.badger.wear.WearDoor
import com.badger.wear.WearMainActivity
import com.badger.wear.WearMode
import com.badger.wear.WearPaths
import com.badger.wear.WearStatus
import com.badger.wear.WearTruck
import com.badger.wear.updater.WearUpdater
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Locale

class WearService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val NOTIF_ID            = 2001
        const val NOTIF_UPDATE_ID     = 2002
        const val ACTION_STOP         = "com.badger.wear.STOP"
        const val ACTION_PTT_START    = "com.badger.wear.PTT_START"
        const val ACTION_PTT_STOP     = "com.badger.wear.PTT_STOP"
        const val ACTION_INSTALL_UPDATE = "com.badger.wear.INSTALL_UPDATE"
        const val PHONE_TIMEOUT_MS    = 10_000L  // phone considered dead after 10s no heartbeat
        const val POLL_INTERVAL_MS    = 30_000L  // standalone polling interval

        var isRunning = false

        private val _trucks    = MutableStateFlow<List<WearTruck>>(emptyList())
        private val _doors     = MutableStateFlow<List<WearDoor>>(emptyList())
        private val _statuses  = MutableStateFlow<List<WearStatus>>(emptyList())
        private val _mode      = MutableStateFlow(WearMode.STANDALONE)
        private val _pttActive = MutableStateFlow(false)

        val trucks:    StateFlow<List<WearTruck>>  = _trucks.asStateFlow()
        val doors:     StateFlow<List<WearDoor>>   = _doors.asStateFlow()
        val statuses:  StateFlow<List<WearStatus>> = _statuses.asStateFlow()
        val mode:      StateFlow<WearMode>         = _mode.asStateFlow()
        val pttActive: StateFlow<Boolean>          = _pttActive.asStateFlow()

        // Called by PhoneListenerService when data arrives from phone
        fun onPhoneTrucks(list: List<WearTruck>)   { _trucks.value = list }
        fun onPhoneDoors(list: List<WearDoor>)     { _doors.value = list }
        fun onPhoneStatuses(list: List<WearStatus>){ _statuses.value = list }
        fun onPhoneAlive()                          { lastPhoneHeartbeat = System.currentTimeMillis() }
        fun onPhoneTts(text: String)               { pendingTts = text }
        fun onPhoneStop()                          { WearApp.instance.stopService(Intent(WearApp.instance, WearService::class.java)) }

        @Volatile var lastPhoneHeartbeat = 0L
        @Volatile var pendingTts: String? = null
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var pttRecorder: android.media.MediaRecorder? = null
    private var standaloneJob: Job? = null
    private var modeWatchJob: Job? = null

    // Lazy Supabase client — only created in standalone mode
    private val supabase by lazy {
        createSupabaseClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY) {
            install(Postgrest)
            install(Realtime)
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        // startForeground MUST be called before anything else on Android 12+
        startForeground(NOTIF_ID, buildNotification("Badger Watch — starting..."))
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "badger:wear_wakelock").also { it.acquire() }
        tts = TextToSpeech(this, this)
        startModeWatcher()
        // Start standalone immediately — phone relay via Wearable API requires same package ID
        startStandaloneMode()
        // Check for updates in background
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
                scope.launch {
                    WearUpdater.downloadAndInstall(this@WearService,
                        com.badger.wear.updater.WearUpdateInfo(
                            latestVersion = intent.getIntExtra("versionCode", 0),
                            tagName = tagName,
                            downloadUrl = url
                        )
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

    // ── Mode Watcher ─────────────────────────────────────────────────────────

    private fun startModeWatcher() {
        modeWatchJob = scope.launch {
            while (isActive) {
                val phoneAlive = (System.currentTimeMillis() - lastPhoneHeartbeat) < PHONE_TIMEOUT_MS
                val newMode = if (phoneAlive && lastPhoneHeartbeat > 0) WearMode.PHONE_RELAY else WearMode.STANDALONE

                if (newMode != _mode.value) {
                    _mode.value = newMode
                    Log.i("WearService", "Mode switched to $newMode")
                    updateNotification("Badger Watch — ${if (newMode == WearMode.PHONE_RELAY) "📱 Phone relay" else "📡 Standalone LTE"}")
                    if (newMode == WearMode.STANDALONE) startStandaloneMode()
                    else stopStandaloneMode()
                }

                // Drain pending TTS from phone bridge
                pendingTts?.let { text ->
                    pendingTts = null
                    speak(text)
                }

                delay(2000)
            }
        }
    }

    // ── Standalone Mode ───────────────────────────────────────────────────────

    private fun startStandaloneMode() {
        standaloneJob?.cancel()
        standaloneJob = scope.launch {
            Log.i("WearService", "Standalone: starting realtime connection")
            val knownTruckStatus = mutableMapOf<String, String?>()
            val knownDoorStatus  = mutableMapOf<String, String?>()

            // Initial load to populate known state
            try {
                val trucks = supabase.from("live_movement").select().decodeList<WearTruck>()
                trucks.forEach { knownTruckStatus[it.truckNumber] = it.statusName }
                _trucks.value = trucks

                val doors = supabase.from("loading_doors").select().decodeList<WearDoor>()
                doors.forEach { knownDoorStatus[it.doorName] = it.doorStatus }
                _doors.value = doors

                val statuses = supabase.from("status_values").select().decodeList<WearStatus>()
                _statuses.value = statuses
                updateNotification("Badger Watch — 📡 Standalone")
            } catch (e: Exception) {
                Log.w("WearService", "Initial load error: ${e.message}")
            }

            // Realtime subscription for instant updates
            try {
                val channel = supabase.realtime.channel("wear-realtime")

                channel.postgresChangeFlow<PostgresAction>("public") { table = "live_movement" }.onEach {
                    try {
                        val trucks = supabase.from("live_movement").select().decodeList<WearTruck>()
                        trucks.forEach { t ->
                            val prev = knownTruckStatus[t.truckNumber]
                            if (prev != null && prev != t.statusName && t.statusName != null) {
                                speak("Truck ${t.truckNumber}, ${t.statusName}")
                                postAlert("🚚 Truck ${t.truckNumber}", "$prev → ${t.statusName}")
                            }
                            knownTruckStatus[t.truckNumber] = t.statusName
                        }
                        _trucks.value = trucks
                    } catch (e: Exception) { Log.w("WearService", "Truck update error: ${e.message}") }
                }.launchIn(this)

                channel.postgresChangeFlow<PostgresAction>("public") { table = "loading_doors" }.onEach {
                    try {
                        val doors = supabase.from("loading_doors").select().decodeList<WearDoor>()
                        doors.forEach { d ->
                            val prev = knownDoorStatus[d.doorName]
                            if (prev != null && prev != d.doorStatus && d.doorStatus.isNotBlank()) {
                                speak("Door ${d.doorName}, ${d.doorStatus}")
                                postAlert("🚪 Door ${d.doorName}", "$prev → ${d.doorStatus}")
                            }
                            knownDoorStatus[d.doorName] = d.doorStatus
                        }
                        _doors.value = doors
                    } catch (e: Exception) { Log.w("WearService", "Door update error: ${e.message}") }
                }.launchIn(this)

                channel.subscribe()
                Log.i("WearService", "Realtime subscribed")

                // Keep alive — refresh every 60s as fallback
                while (isActive && _mode.value == WearMode.STANDALONE) {
                    delay(60_000)
                    if (_mode.value == WearMode.STANDALONE) {
                        try {
                            _trucks.value = supabase.from("live_movement").select().decodeList<WearTruck>()
                            _doors.value  = supabase.from("loading_doors").select().decodeList<WearDoor>()
                        } catch (_: Exception) {}
                    }
                }

                try { channel.unsubscribe() } catch (_: Exception) {}

            } catch (e: Exception) {
                Log.w("WearService", "Realtime error, falling back to polling: ${e.message}")
                // Fallback: poll every 10s if realtime fails
                while (isActive && _mode.value == WearMode.STANDALONE) {
                    delay(10_000)
                    if (_mode.value == WearMode.STANDALONE) {
                        pollSupabase(knownTruckStatus, knownDoorStatus)
                    }
                }
            }
        }
    }

    private suspend fun pollSupabase(
        knownTruck: MutableMap<String, String?>,
        knownDoor: MutableMap<String, String?>
    ) {
        try {
            // Fetch trucks
            val trucks = supabase.from("live_movement").select().decodeList<WearTruck>()
            trucks.forEach { t ->
                val prev = knownTruck[t.truckNumber]
                if (prev != null && prev != t.statusName && t.statusName != null) {
                    speak("Truck ${t.truckNumber}, ${t.statusName}")
                    postAlert("🚚 Truck ${t.truckNumber}", "${prev} → ${t.statusName}")
                }
                knownTruck[t.truckNumber] = t.statusName
            }
            _trucks.value = trucks

            // Fetch doors
            val doors = supabase.from("loading_doors").select().decodeList<WearDoor>()
            doors.forEach { d ->
                val prev = knownDoor[d.doorName]
                if (prev != null && prev != d.doorStatus && d.doorStatus.isNotBlank()) {
                    speak("Door ${d.doorName}, ${d.doorStatus}")
                    postAlert("🚪 Door ${d.doorName}", "${prev} → ${d.doorStatus}")
                }
                knownDoor[d.doorName] = d.doorStatus
            }
            _doors.value = doors

            // Fetch statuses
            val statuses = supabase.from("status_values").select().decodeList<WearStatus>()
            _statuses.value = statuses

        } catch (e: Exception) {
            Log.w("WearService", "Poll error: ${e.message}")
        }
    }

    private fun stopStandaloneMode() {
        standaloneJob?.cancel()
        standaloneJob = null
    }

    // ── Status Changes (sent to phone or Supabase directly) ───────────────────

    fun changeTruckStatus(truckNumber: String, statusId: Int) {
        scope.launch {
            if (_mode.value == WearMode.PHONE_RELAY) {
                // Send to phone via Wearable message
                val payload = Json.encodeToString(mapOf("truckNumber" to truckNumber, "statusId" to statusId.toString()))
                sendMessageToPhone(WearPaths.MSG_STATUS_CHANGE, payload.toByteArray())
            } else {
                // Write directly to Supabase
                try {
                    supabase.from("live_movement").update({ set("status_id", statusId) }) {
                        filter { eq("truck_number", truckNumber) }
                    }
                } catch (e: Exception) { Log.e("WearService", "Status change failed: ${e.message}") }
            }
        }
    }

    fun changeDoorStatus(doorId: Int, status: String) {
        scope.launch {
            if (_mode.value == WearMode.PHONE_RELAY) {
                val payload = Json.encodeToString(mapOf("doorId" to doorId.toString(), "status" to status))
                sendMessageToPhone(WearPaths.MSG_DOOR_CHANGE, payload.toByteArray())
            } else {
                try {
                    supabase.from("loading_doors").update({ set("door_status", status) }) {
                        filter { eq("id", doorId) }
                    }
                } catch (e: Exception) { Log.e("WearService", "Door change failed: ${e.message}") }
            }
        }
    }

    // ── PTT ───────────────────────────────────────────────────────────────────

    private fun startPtt() {
        _pttActive.value = true
        if (_mode.value == WearMode.PHONE_RELAY) {
            sendMessageToPhone(WearPaths.MSG_PTT_START, ByteArray(0))
        } else {
            // TODO: standalone PTT — record locally, send via Supabase storage
            Log.i("WearService", "PTT start (standalone — coming soon)")
        }
    }

    private fun stopPtt() {
        _pttActive.value = false
        if (_mode.value == WearMode.PHONE_RELAY) {
            sendMessageToPhone(WearPaths.MSG_PTT_STOP, ByteArray(0))
        }
    }

    // ── TTS ───────────────────────────────────────────────────────────────────

    fun speak(text: String) {
        if (!ttsReady || tts == null) return
        val id = "wear_${System.currentTimeMillis()}"
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, id)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            ttsReady = true
            speak("Badger watch active")
            Log.i("WearService", "TTS ready")
        }
    }

    // ── Notifications ─────────────────────────────────────────────────────────

    private fun postAlert(title: String, body: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val n = NotificationCompat.Builder(this, WearApp.CHANNEL_ALERTS)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        nm.notify(title.hashCode(), n)
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
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(status))
    }

    // ── Wearable messaging ────────────────────────────────────────────────────

    private fun sendMessageToPhone(path: String, data: ByteArray) {
        scope.launch {
            try {
                val nodes = Tasks.await(Wearable.getNodeClient(this@WearService).connectedNodes)
                nodes.firstOrNull()?.let { node ->
                    Wearable.getMessageClient(this@WearService).sendMessage(node.id, path, data)
                }
            } catch (e: Exception) { Log.w("WearService", "sendMessageToPhone failed: ${e.message}") }
        }
    }

    // ── Auto-update ───────────────────────────────────────────────────────────

    private suspend fun checkForUpdate() {
        try {
            val current = BuildConfig.VERSION_CODE
            val update = WearUpdater.checkForUpdate(current) ?: return
            Log.i("WearService", "Update available: ${update.tagName}")
            // Post a tappable notification — tapping triggers download + install
            val installIntent = PendingIntent.getService(
                this, 99,
                Intent(this, WearService::class.java).apply {
                    action = ACTION_INSTALL_UPDATE
                    putExtra("downloadUrl", update.downloadUrl)
                    putExtra("tagName", update.tagName)
                    putExtra("versionCode", update.latestVersion)
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_UPDATE_ID, NotificationCompat.Builder(this, WearApp.CHANNEL_ALERTS)
                .setContentTitle("Badger Update Available")
                .setContentText("${update.tagName} — tap to install")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(installIntent)
                .build())
        } catch (e: Exception) {
            Log.w("WearService", "Update check failed: ${e.message}")
        }
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    private fun stopClean() {
        Log.i("WearService", "Stopping cleanly")
        standaloneJob?.cancel()
        modeWatchJob?.cancel()
        tts?.stop(); tts?.shutdown()
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
