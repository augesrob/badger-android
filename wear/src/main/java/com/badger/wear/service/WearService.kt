package com.badger.wear.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Base64
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
import com.badger.wear.WearPrintroomEntry
import com.badger.wear.WearPttInsert
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
    private var recorder: MediaRecorder? = null  // unused, kept for reference
    private var pttFile: File? = null  // unused

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

                // Subscribe to incoming PTT from phone
                channel.postgresChangeFlow<PostgresAction>("public") { table = "ptt_messages" }.onEach { action ->
                    if (action !is PostgresAction.Insert) return@onEach
                    try {
                        val b64 = action.record["audio_b64"]?.jsonPrimitive?.content
                        val sender = action.record["sender"]?.jsonPrimitive?.content
                        if (b64.isNullOrEmpty() || sender == "watch") return@onEach
                        WearLogger.i("WearService", "PTT incoming from ${sender ?: "phone"}")
                        speak("Incoming message")
                        val pcm = Base64.decode(b64, Base64.DEFAULT)
                        launch { playPcm(pcm) }
                    } catch (e: Exception) { WearLogger.w("WearService", "PTT receive: ${e.message}") }
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

    // Raw PCM recording — same format as phone PushToTalkManager
    private var audioRecord: android.media.AudioRecord? = null
    private val pttChunks  = mutableListOf<ByteArray>()

    private fun startPtt() {
        if (_pttActive.value) return
        _pttActive.value = true
        WearLogger.i("WearService", "PTT recording started")
        try {
            val minBuf = android.media.AudioRecord.getMinBufferSize(8000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = android.media.AudioRecord(
                android.media.MediaRecorder.AudioSource.MIC,
                8000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4
            )
            if (rec.state != android.media.AudioRecord.STATE_INITIALIZED) { rec.release(); throw Exception("AudioRecord init failed") }
            pttChunks.clear()
            audioRecord = rec
            rec.startRecording()
            scope.launch(Dispatchers.IO) {
                val buf = ByteArray(minBuf)
                while (_pttActive.value) {
                    val read = rec.read(buf, 0, buf.size)
                    if (read > 0) pttChunks.add(buf.copyOf(read))
                }
            }
        } catch (e: Exception) {
            WearLogger.e("WearService", "PTT record start failed: ${e.message}")
            _pttActive.value = false
        }
    }

    private fun stopPtt() {
        if (!_pttActive.value) return
        _pttActive.value = false
        try {
            audioRecord?.apply { stop(); release() }
            audioRecord = null
            val total = pttChunks.sumOf { it.size }
            if (total == 0) { WearLogger.w("WearService", "PTT: no audio captured"); return }
            val pcm = ByteArray(total).also { out -> var pos = 0; pttChunks.forEach { c -> c.copyInto(out, pos); pos += c.size } }
            pttChunks.clear()
            WearLogger.i("WearService", "PTT recording stopped — uploading ${pcm.size} bytes PCM")
            scope.launch(Dispatchers.IO) { uploadPttPcm(pcm) }
        } catch (e: Exception) {
            WearLogger.e("WearService", "PTT stop failed: ${e.message}")
        }
    }

    private suspend fun uploadPttPcm(pcm: ByteArray) {
        try {
            val b64 = Base64.encodeToString(pcm, Base64.NO_WRAP)
            supabase.from("ptt_messages").insert(WearPttInsert(audioB64 = b64, sender = "watch"))
            WearLogger.i("WearService", "PTT sent — ${pcm.size} bytes")
        } catch (e: Exception) {
            WearLogger.e("WearService", "PTT send failed: ${e.message}")
        }
    }

    private suspend fun uploadPtt(file: File) {
        try {
            val pcm    = file.readBytes()
            val b64    = Base64.encodeToString(pcm, Base64.NO_WRAP)
            // Use same format as phone: raw PCM base64 in audio_b64 column
            supabase.from("ptt_messages").insert(
                WearPttInsert(audioB64 = b64, sender = "watch")
            )
            WearLogger.i("WearService", "PTT sent — ${pcm.size} bytes PCM")
            file.delete()
        } catch (e: Exception) {
            WearLogger.e("WearService", "PTT upload failed: ${e.message}")
        }
    }

    private suspend fun playPcm(pcm: ByteArray) = withContext(Dispatchers.Main) {
        if (pcm.isEmpty()) return@withContext
        try {
            val minBuf = AudioTrack.getMinBufferSize(8000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val track  = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(8000).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(minBuf, pcm.size))
                .setTransferMode(AudioTrack.MODE_STATIC).build()
            track.write(pcm, 0, pcm.size)
            track.setVolume(AudioTrack.getMaxVolume())
            track.play()
            val durationMs = (pcm.size.toLong() * 1000L) / (8000 * 2)
            WearLogger.i("WearService", "PTT playing ~${durationMs}ms")
            delay(durationMs + 300)
            track.stop(); track.release()
        } catch (e: Exception) {
            WearLogger.e("WearService", "PTT playback error: ${e.message}")
        }
    }

    // ── TTS ───────────────────────────────────────────────────────────────────

    private fun speak(text: String) {
        if (tts == null) {
            WearLogger.w("WearService", "TTS speak called but TTS is null: '$text'")
            return
        }
        if (!ttsReady) {
            WearLogger.w("WearService", "TTS not ready yet, attempting to speak anyway: '$text'")
        }
        try {
            val id = "wear_" + System.currentTimeMillis()
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, id)
            WearLogger.i("WearService", "TTS queued: '$text'")
        } catch (e: Exception) {
            WearLogger.e("WearService", "TTS speak error: " + e.message + " — text: '$text'")
        }
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
