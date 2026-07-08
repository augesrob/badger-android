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
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
        const val ACTION_FORCE_UPDATE   = "com.badger.wear.FORCE_UPDATE"
        const val ACTION_DOOR_CHANGE    = "com.badger.wear.DOOR_CHANGE"

        var isRunning = false

        private val _trucks       = MutableStateFlow<List<WearTruck>>(emptyList())
        private val _doors        = MutableStateFlow<List<WearDoor>>(emptyList())
        private val _statuses     = MutableStateFlow<List<WearStatus>>(emptyList())
        private val _pttActive    = MutableStateFlow(false)
        private val _doorStatuses    = MutableStateFlow<List<String>>(emptyList())
        private val _printroom        = MutableStateFlow<List<WearPrintroomEntry>>(emptyList())
        // Download percent (0-100) while a self-update download runs, null when idle —
        // drives the progress bar in the app footer
        private val _updateProgress   = MutableStateFlow<Int?>(null)

        val trucks:       StateFlow<List<WearTruck>>  = _trucks.asStateFlow()
        val doors:        StateFlow<List<WearDoor>>   = _doors.asStateFlow()
        val statuses:     StateFlow<List<WearStatus>> = _statuses.asStateFlow()
        val pttActive:    StateFlow<Boolean>          = _pttActive.asStateFlow()
        val doorStatuses: StateFlow<List<String>>     = _doorStatuses.asStateFlow()
        val printroom:    StateFlow<List<WearPrintroomEntry>> = _printroom.asStateFlow()
        val updateProgress: StateFlow<Int?>                   = _updateProgress.asStateFlow()
    }

    // Single supervisor scope — wakelock keeps it alive through downloads
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var realtimeJob: Job? = null
    private var updateAttempted = false   // only try once per service instance

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsFullyInitialized = false  // true after onInit + delay
    private var ttsInitRetries = 0
    // Latest utterance that failed to queue — replayed after engine re-init so a dead
    // engine doesn't silently eat announcements (speak()'s result was ignored before)
    private var pendingTtsText: String? = null
    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= 31)
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        else @Suppress("DEPRECATION") (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
    }
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
        // Periodic update re-check: long-running watches shouldn't need a reboot
        // to notice a new release. Service-level scope survives realtime restarts.
        scope.launch {
            while (true) {
                delay(12 * 60 * 60 * 1000L) // every 12 hours
                checkForUpdate()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopClean()
            ACTION_PTT_START -> startPtt()
            ACTION_PTT_STOP  -> stopPtt()
            ACTION_FORCE_UPDATE -> {
                WearLogger.i("WearService", "FORCE_UPDATE requested from UI")
                scope.launch { checkForUpdate() }
            }
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
            WearLogger.w("WearService", "TTS not ready yet (engine init in progress), will retry: '$text'")
            // Retry after a delay
            scope.launch {
                delay(200L)
                if (ttsReady && tts != null) {
                    try {
                        val id = "wear_retry_" + System.currentTimeMillis()
                        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, id)
                        WearLogger.i("WearService", "TTS retry queued: '$text'")
                    } catch (e: Exception) {
                        WearLogger.e("WearService", "TTS retry error: " + e.message)
                    }
                }
            }
            return
        }
        try {
            val id = "wear_" + System.currentTimeMillis()
            val result = tts?.speak(text, TextToSpeech.QUEUE_ADD, null, id)
            if (result == TextToSpeech.SUCCESS) {
                WearLogger.i("WearService", "TTS queued: '$text'")
            } else {
                // The result was ignored before, so a dead engine logged "queued" while
                // nothing played (popup showed, no voice). Re-init and replay from onInit.
                WearLogger.e("WearService", "TTS speak returned $result — reinitializing, queued: '$text'")
                pendingTtsText = text
                ttsReady = false
                ttsFullyInitialized = false
                try { tts?.shutdown() } catch (_: Exception) {}
                tts = TextToSpeech(this, this)
            }
        } catch (e: Exception) {
            WearLogger.e("WearService", "TTS speak error: " + e.message + " — text: '$text'")
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            ttsReady = true
            ttsInitRetries = 0
            WearLogger.i("WearService", "TTS engine initialized, waiting for full initialization...")
            // Some devices (S25 Ultra) need a brief delay after onInit before speaking
            scope.launch {
                delay(500L)  // 500ms delay for engine to fully warm up
                ttsFullyInitialized = true
                if (!ttsSpokenWelcome) {
                    markTtsWelcomeDone()
                    speak("Badger watch active")
                }
                WearLogger.i("WearService", "TTS fully ready ✅")
                // Replay the announcement that was dropped when the old engine died
                pendingTtsText?.let { pending ->
                    pendingTtsText = null
                    speak(pending)
                }
            }
        } else {
            WearLogger.e("WearService", "TTS init failed: $status")
            if (ttsInitRetries < 5) {
                ttsInitRetries++
                scope.launch {
                    delay(3000L * ttsInitRetries)
                    WearLogger.w("WearService", "TTS init retry #$ttsInitRetries")
                    try { tts?.shutdown() } catch (_: Exception) {}
                    tts = TextToSpeech(this@WearService, this@WearService)
                }
            }
        }
    }

    // ── Notifications ─────────────────────────────────────────────────────────

    private fun postAlert(title: String, body: String) {
        // Strip emoji prefix for the popup/complication text
        val cleanTitle = title.replace(Regex("^[^A-Za-z0-9]+"), "").trim()

        // Haptic buzz (double pulse) — status changes are felt on the wrist even
        // when TTS fails or the volume is low
        try {
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 250, 120, 250), -1))
        } catch (e: Exception) {
            WearLogger.w("WearService", "Vibrate failed: ${e.message}")
        }

        // Persist for the watch-face complication and refresh it
        com.badger.wear.status.StatusStore.save(this, cleanTitle, body)
        try {
            androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester.create(
                this, android.content.ComponentName(this, com.badger.wear.status.BadgerComplicationService::class.java),
            ).requestUpdateAll()
        } catch (e: Exception) {
            WearLogger.w("WearService", "Complication update failed: ${e.message}")
        }

        // Direct launch -- with SYSTEM_ALERT_WINDOW granted (adb appops) the service may
        // start activities from the background, so the card appears over the watch face
        // even while the screen is on. Falls back to the full-screen intent below.
        try {
            startActivity(Intent(this, com.badger.wear.status.StatusPopupActivity::class.java).apply {
                putExtra(com.badger.wear.status.StatusPopupActivity.EXTRA_TITLE, cleanTitle)
                putExtra(com.badger.wear.status.StatusPopupActivity.EXTRA_BODY, body)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
        } catch (e: Exception) {
            WearLogger.w("WearService", "Popup direct start failed: ${e.message}")
        }

        // Full-screen intent -- pops the status card over the watch face (alarm mechanism)
        val popup = PendingIntent.getActivity(
            this,
            (title + body).hashCode(),
            Intent(this, com.badger.wear.status.StatusPopupActivity::class.java).apply {
                putExtra(com.badger.wear.status.StatusPopupActivity.EXTRA_TITLE, cleanTitle)
                putExtra(com.badger.wear.status.StatusPopupActivity.EXTRA_BODY, body)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(title.hashCode(), NotificationCompat.Builder(this, WearApp.CHANNEL_ALERTS)
            .setContentTitle(title).setContentText(body)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(popup, true)
            .setAutoCancel(true).build())
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

    // Only one download may run at a time: on 2026-07-08 the service-start auto-check and
    // an Update-chip tap 3s later both downloaded to the same file, interleaving writes —
    // the APK validator rejected both and the update had to be retried
    @Volatile private var updateInProgress = false

    private suspend fun checkForUpdate() {
        if (updateInProgress) {
            WearLogger.i("WearService", "Update already in progress — ignoring duplicate request")
            return
        }
        updateInProgress = true
        try {
            // A committed install may still be waiting for its confirmation dialog —
            // re-launch it instead of re-downloading (the dialog is easy to miss)
            if (com.badger.wear.updater.PendingInstallStore.relaunch(this)) return

            val update = WearUpdater.checkForUpdate(BuildConfig.VERSION_CODE) ?: return
            WearLogger.i("WearService", "Update found: ${update.tagName} — auto-downloading")
            updateNotification("Badger Watch — Updating ${update.tagName}...")
            _updateProgress.value = 0
            WearUpdater.downloadAndInstall(this, update,
                onProgress = { msg -> WearLogger.i("WearService", "Update: $msg") },
                onPercent  = { p -> _updateProgress.value = p })
        } catch (e: Exception) { WearLogger.w("WearService", "Update check failed: ${e.message}") }
        finally { updateInProgress = false; _updateProgress.value = null }
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
