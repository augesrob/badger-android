package com.badger.trucks.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.view.WindowManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import com.badger.trucks.MainActivity
import com.badger.trucks.util.RemoteLogger
import com.badger.trucks.data.BadgerRepo
import com.badger.trucks.data.DoorStatusValue
import com.badger.trucks.data.DockLockStatusValue
import com.badger.trucks.data.LoadingDoor
import com.badger.trucks.data.LiveMovement
import com.badger.trucks.data.LiveMovementStatus
import com.badger.trucks.data.StatusValue
import com.badger.trucks.voice.BadgerSpeechRecognizer
import com.badger.trucks.voice.HotwordListener
import com.badger.trucks.voice.PushToTalkManager
import com.badger.trucks.voice.VoiceCommandProcessor
import com.badger.trucks.voice.VoiceResult
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
        const val ACTION_STOP         = "com.badger.trucks.STOP_SERVICE"
        const val ACTION_MANUAL_VOICE  = "com.badger.trucks.MANUAL_VOICE"
        const val ACTION_DATA_CHANGED  = "com.badger.trucks.DATA_CHANGED"
        const val ACTION_APPLY_SETTINGS = "com.badger.trucks.APPLY_SETTINGS"

        var ttsEnabled = true
        var isRunning  = false

        // PTT state
        private val _pttRecording = MutableStateFlow(false)
        private val _pttIncoming  = MutableStateFlow(false)
        val pttRecording: StateFlow<Boolean> = _pttRecording.asStateFlow()
        val pttIncoming:  StateFlow<Boolean> = _pttIncoming.asStateFlow()

        // Hotword / voice command state — UI observes these
        private val _hotwordActive   = MutableStateFlow(false)
        private val _voiceProcessing = MutableStateFlow(false)
        private val _voiceFeedback   = MutableStateFlow<String?>(null)
        val hotwordActive:   StateFlow<Boolean>  = _hotwordActive.asStateFlow()
        val voiceProcessing: StateFlow<Boolean>  = _voiceProcessing.asStateFlow()
        val voiceFeedback:   StateFlow<String?>  = _voiceFeedback.asStateFlow()

        // Live truck/door data — updated optimistically on voice commands
        private val _liveTrucks          = MutableStateFlow<List<LiveMovement>>(emptyList())
        private val _liveDoors           = MutableStateFlow<List<LoadingDoor>>(emptyList())
        private val _liveDoorStatusValues     = MutableStateFlow<List<DoorStatusValue>>(emptyList())
        private val _liveDockLockStatusValues  = MutableStateFlow<List<DockLockStatusValue>>(emptyList())
        val liveTrucks:              StateFlow<List<LiveMovement>>          = _liveTrucks.asStateFlow()
        val liveDoors:               StateFlow<List<LoadingDoor>>           = _liveDoors.asStateFlow()
        val liveDoorStatusValues:    StateFlow<List<DoorStatusValue>>       = _liveDoorStatusValues.asStateFlow()
        val liveDockLockStatusValues: StateFlow<List<DockLockStatusValue>>  = _liveDockLockStatusValues.asStateFlow()
    }

    private val scope        = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler  = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady     = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var screenLock: PowerManager.WakeLock? = null
    private var pttManager:     PushToTalkManager?  = null
    private var hotwordListener: HotwordListener?   = null
    private var commandRecognizer: BadgerSpeechRecognizer? = null

    // Audio focus
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null  // API 26+
    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        // When we gain focus back (shouldn't happen normally), log it
        // Other apps auto-resume when we ABANDON focus via abandonAudioFocus()
        Log.d("BadgerService", "Audio focus change: $focusChange")
    }

    private val HOTWORD_COOLDOWN_MS = 4_000L
    @Volatile private var lastHotwordMs = 0L

    // Cached data for voice commands
    @Volatile private var cachedStatuses: List<StatusValue> = emptyList()
    @Volatile private var cachedDoorStatusValues: List<DoorStatusValue> = emptyList()
    @Volatile private var cachedDockLockStatusValues: List<DockLockStatusValue> = emptyList()
    private var cachedTrucks: List<LiveMovement> = emptyList()
        set(value) { field = value; _liveTrucks.value = value }
    private var cachedDoors: List<LoadingDoor> = emptyList()
        set(value) { field = value; _liveDoors.value = value }

    private val knownStatuses   = mutableMapOf<String, String?>()
    private val knownDoorStatus = mutableMapOf<String, String?>()
    private val knownPreshift   = mutableMapOf<Int, Pair<String?, String?>>()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "badger:ptt_wakelock").also { it.acquire() }

        NotificationHelper.createAllChannels(this)
        startForeground(NOTIF_ID, buildServiceNotification())

        tts = TextToSpeech(this, this)

        pttManager = PushToTalkManager(this, scope).also { mgr ->
            mgr.onIncoming = {
                _pttIncoming.value = true
                NotificationHelper.postNotification(this, NotificationHelper.CHANNEL_SYSTEM, "📻 Incoming PTT", "Someone is talking on the radio", "ptt_incoming")
            }
            mgr.onDone = { _pttIncoming.value = false }
            mgr.startListening()
        }

        commandRecognizer = BadgerSpeechRecognizer(this)

        val hotwordEnabled = NotificationPrefsStore.get(this, NotificationPrefsStore.KEY_HOTWORD)
        mainHandler.post {
            hotwordListener = HotwordListener(this).also { hw ->
                hw.onHotwordDetected = { onHotwordDetected() }
                if (hotwordEnabled) hw.start()
                else Log.d("BadgerService", "Hotword disabled by user pref")
            }
        }

        scope.launch { refreshVoiceData() }
        startRealtimeSync()
        Log.d("BadgerService", "Service created")
        RemoteLogger.i("BadgerService", "Service started — URL: ${com.badger.trucks.BuildConfig.SUPABASE_URL}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_TTS -> {
                ttsEnabled = !ttsEnabled
                updateServiceNotification()
                if (ttsEnabled) speak("Text to speech enabled")
            }
            ACTION_PTT_START -> { _pttRecording.value = true;  pttManager?.startRecording() }
            ACTION_PTT_STOP  -> { _pttRecording.value = false; pttManager?.stopRecording()  }
            ACTION_MANUAL_VOICE -> mainHandler.post { onHotwordDetected() }
            ACTION_APPLY_SETTINGS -> applySettingsLive()
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        _pttRecording.value  = false
        _pttIncoming.value   = false
        _hotwordActive.value = false
        _voiceProcessing.value = false
        _voiceFeedback.value   = null
        pttManager?.destroy()
        mainHandler.post { hotwordListener?.destroy() }
        commandRecognizer?.destroy()
        tts?.stop(); tts?.shutdown()
        abandonAudioFocus()
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        releaseScreen()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            ttsReady = true
            speak("Badger live monitoring active")
        }
    }

    // ── Audio Focus ───────────────────────────────────────────────────────────

    private fun requestAudioFocus() {
        val mode = NotificationPrefsStore.getString(this, NotificationPrefsStore.KEY_AUDIO_FOCUS,
            NotificationPrefsStore.AUDIO_FOCUS_TRANSIENT)
        if (mode == NotificationPrefsStore.AUDIO_FOCUS_OFF) return

        val focusType = when (mode) {
            NotificationPrefsStore.AUDIO_FOCUS_EXCLUSIVE -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
            NotificationPrefsStore.AUDIO_FOCUS_DUCK      -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            else                                          -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            audioFocusRequest = AudioFocusRequest.Builder(focusType)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(audioFocusListener)
                .setAcceptsDelayedFocusGain(false)   // we don't need delayed — abandon when done
                .setWillPauseWhenDucked(mode == NotificationPrefsStore.AUDIO_FOCUS_EXCLUSIVE)
                .build()
                .also { audioManager?.requestAudioFocus(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager?.requestAudioFocus(audioFocusListener, AudioManager.STREAM_MUSIC, focusType)
        }
        Log.d("BadgerService", "Audio focus requested: $mode")
    }

    private fun abandonAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audioManager?.abandonAudioFocus(audioFocusListener)
            }
        } catch (e: Exception) {
            Log.w("BadgerService", "abandonAudioFocus error: ${e.message}")
        }
    }

    /** Called when user changes settings — re-reads prefs and applies live without restart */
    private fun applySettingsLive() {
        val hotwordEnabled = NotificationPrefsStore.get(this, NotificationPrefsStore.KEY_HOTWORD)
        mainHandler.post {
            hotwordListener?.let { hw ->
                if (hotwordEnabled) {
                    hw.resume()
                    Log.d("BadgerService", "applySettings: hotword resumed")
                } else {
                    hw.pause()
                    Log.d("BadgerService", "applySettings: hotword paused")
                }
            }
        }
        Log.d("BadgerService", "applySettings: audio focus mode = ${NotificationPrefsStore.getString(this, NotificationPrefsStore.KEY_AUDIO_FOCUS)}")
        // Audio focus mode is read fresh on each requestAudioFocus() call — no action needed
    }


    // ── Hotword flow ──────────────────────────────────────────────────────────

    private fun wakeScreen() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            screenLock?.let { if (it.isHeld) it.release() }
            @Suppress("DEPRECATION")
            screenLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "badger:screen_wake"
            ).also { it.acquire(8_000L) }
        } catch (e: Exception) {
            Log.w("BadgerService", "Could not wake screen: ${e.message}")
        }
    }

    private fun releaseScreen() {
        try { screenLock?.let { if (it.isHeld) it.release() }; screenLock = null } catch (_: Exception) {}
    }

    private fun onHotwordDetected() {
        val now = System.currentTimeMillis()
        if (now - lastHotwordMs < HOTWORD_COOLDOWN_MS) {
            hotwordListener?.resume()
            return
        }
        lastHotwordMs = now

        wakeScreen()
        playActivationChime()
        _hotwordActive.value   = true
        _voiceProcessing.value = false
        _voiceFeedback.value   = null

        mainHandler.postDelayed({
            commandRecognizer?.startListening(
                onResult = { text -> onCommandResult(text) },
                onError  = { err  -> onCommandError(err)  }
            )
        }, 450)
    }

    private fun onCommandResult(text: String) {
        _hotwordActive.value   = false
        _voiceProcessing.value = true

        scope.launch {
            try {
                val cmd    = VoiceCommandProcessor.parseCommand(text, cachedTrucks, cachedDoors, cachedStatuses)
                val result = VoiceCommandProcessor.executeCommand(cmd, cachedTrucks, cachedDoors, cachedStatuses)

                val (feedback, spokenText) = when (result) {
                    is VoiceResult.Success -> "✅ ${result.description}" to result.description
                    is VoiceResult.Error   -> "❌ ${result.message}" to "Sorry, ${result.message}"
                    VoiceResult.Unknown    -> "🤔 Didn't understand: \"$text\"" to "I didn't understand that"
                }

                _voiceFeedback.value   = feedback
                _voiceProcessing.value = false

                if (result is VoiceResult.Success) {
                    when (cmd.action) {
                        "truck_status" -> {
                            val newStatus = cachedStatuses.find { it.statusName == cmd.status }
                            cachedTrucks = cachedTrucks.map { t ->
                                if (t.truckNumber == cmd.truck)
                                    t.copy(statusValues = LiveMovementStatus(
                                        statusName  = newStatus?.statusName,
                                        statusColor = newStatus?.statusColor
                                    ))
                                else t
                            }
                            cmd.truck?.let { knownStatuses[it] = newStatus?.statusName }
                        }
                        "door_status" -> {
                            cachedDoors = cachedDoors.map { d ->
                                if (d.doorName == cmd.door) d.copy(doorStatus = cmd.status ?: "") else d
                            }
                            cmd.door?.let { knownDoorStatus[it] = cmd.status }
                        }
                    }

                    sendBroadcast(Intent(ACTION_DATA_CHANGED))
                    scope.launch { refreshVoiceData() }
                }

                speak(spokenText) {
                    scope.launch {
                        delay(if (result is VoiceResult.Success) 1500L else 3000L)
                        _voiceFeedback.value = null
                        releaseScreen()
                        lastHotwordMs = System.currentTimeMillis()
                        mainHandler.post { hotwordListener?.resume() }
                    }
                }

            } catch (e: Exception) {
                Log.e("BadgerService", "Voice command error", e)
                _voiceProcessing.value = false
                _voiceFeedback.value   = "❌ Error: ${e.message}"
                delay(3000)
                _voiceFeedback.value = null
                releaseScreen()
                lastHotwordMs = System.currentTimeMillis()
                mainHandler.postDelayed({ hotwordListener?.resume() }, 1000)
            }
        }
    }

    private fun onCommandError(err: String) {
        _hotwordActive.value   = false
        _voiceProcessing.value = false
        _voiceFeedback.value   = "❌ $err"
        scope.launch {
            delay(2000)
            _voiceFeedback.value = null
            releaseScreen()
            lastHotwordMs = System.currentTimeMillis()
            mainHandler.postDelayed({ hotwordListener?.resume() }, 1000)
        }
    }

    private fun playActivationChime() {
        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            mainHandler.postDelayed({ toneGen.release() }, 200)
        } catch (e: Exception) {
            Log.w("BadgerService", "Chime failed: ${e.message}")
        }
    }

    private suspend fun refreshVoiceData() {
        try {
            cachedTrucks          = BadgerRepo.getLiveMovement()
            cachedDoors           = BadgerRepo.getLoadingDoors()
            cachedStatuses        = BadgerRepo.getStatuses()
            cachedDoorStatusValues = BadgerRepo.getDoorStatusValues()
            _liveDoorStatusValues.value = cachedDoorStatusValues
            cachedDockLockStatusValues = BadgerRepo.getDockLockStatusValues()
            _liveDockLockStatusValues.value = cachedDockLockStatusValues
        } catch (e: Exception) {
            Log.w("BadgerService", "refreshVoiceData error: ${e.message}")
        }
    }

    // ── TTS ───────────────────────────────────────────────────────────────────

    private fun speak(text: String, onDone: (() -> Unit)? = null) {
        val ttsOn = NotificationPrefsStore.get(this, NotificationPrefsStore.KEY_CHANNEL_TTS)
        if (ttsEnabled && ttsReady && ttsOn) {
            val uttId = "badger_${System.currentTimeMillis()}"
            requestAudioFocus()
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onError(utteranceId: String?) {
                    abandonAudioFocus()
                    onDone?.invoke()
                }
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == uttId) {
                        mainHandler.postDelayed({
                            abandonAudioFocus()
                            onDone?.invoke()
                        }, 600)
                    }
                }
            })
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, uttId)
        } else {
            onDone?.invoke()
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
                BadgerRepo.getLiveMovement().forEach { knownStatuses[it.truckNumber] = it.statusName }
                BadgerRepo.getLoadingDoors().forEach { knownDoorStatus[it.doorName]  = it.doorStatus }
                BadgerRepo.getStagingDoors().forEach { knownPreshift[it.id]          = Pair(it.inFront, it.inBack) }

                val channel = BadgerRepo.realtimeChannel("badger-service-realtime")

                channel.postgresChangeFlow<PostgresAction>("public") { table = "live_movement" }.onEach {
                    try {
                        val updated = BadgerRepo.getLiveMovement()
                        updated.forEach { truck ->
                            val prev = knownStatuses[truck.truckNumber]
                            val curr = truck.statusName
                            if (prev != null && curr != null && prev != curr) {
                                speak("Truck ${truck.truckNumber}, $curr")
                                if (canNotify(NotificationPrefsStore.KEY_TRUCK_STATUS)) {
                                    pushNotif(NotificationHelper.CHANNEL_TRUCK_STATUS, "🚚 Truck ${truck.truckNumber}",
                                        "$prev → $curr${truck.currentLocation?.let { " @ $it" } ?: ""}", "truck_${truck.truckNumber}")
                                }
                            }
                            knownStatuses[truck.truckNumber] = curr
                        }
                        val currSet = updated.map { it.truckNumber }.toSet()
                        knownStatuses.keys.filter { it !in currSet }.forEach { knownStatuses.remove(it) }
                        cachedTrucks = updated
                    } catch (e: Exception) { Log.e("BadgerService", "Truck refresh: ${e.message}") }
                }.launchIn(scope)

                channel.postgresChangeFlow<PostgresAction>("public") { table = "loading_doors" }.onEach {
                    try {
                        val updated = BadgerRepo.getLoadingDoors()
                        updated.forEach { door ->
                            val prev = knownDoorStatus[door.doorName]
                            val curr = door.doorStatus
                            if (prev != null && curr != prev && curr.isNotBlank()) {
                                speak("Door ${door.doorName}, $curr")
                                if (canNotify(NotificationPrefsStore.KEY_DOOR_STATUS)) {
                                    pushNotif(NotificationHelper.CHANNEL_DOOR_STATUS, "🚪 Door ${door.doorName}", "$prev → $curr", "door_${door.doorName}")
                                }
                            }
                            knownDoorStatus[door.doorName] = curr
                        }
                        cachedDoors = updated
                    } catch (e: Exception) { Log.e("BadgerService", "Door refresh: ${e.message}") }
                }.launchIn(scope)

                channel.postgresChangeFlow<PostgresAction>("public") { table = "staging_doors" }.onEach {
                    try {
                        val changes = mutableListOf<String>()
                        BadgerRepo.getStagingDoors().forEach { door ->
                            val prev = knownPreshift[door.id]
                            val curr = Pair(door.inFront, door.inBack)
                            if (prev != null && prev != curr) {
                                if (prev.first  != door.inFront) changes.add("${door.doorLabel} front: ${prev.first ?: "empty"} → ${door.inFront ?: "empty"}")
                                if (prev.second != door.inBack)  changes.add("${door.doorLabel} back: ${prev.second ?: "empty"} → ${door.inBack ?: "empty"}")
                            }
                            knownPreshift[door.id] = curr
                        }
                        if (changes.isNotEmpty()) {
                            speak("Preshift updated")
                            if (canNotify(NotificationPrefsStore.KEY_PRESHIFT)) {
                                pushNotif(NotificationHelper.CHANNEL_PRESHIFT, "📋 PreShift Updated", changes.joinToString("\n"), "preshift_change")
                            }
                        }
                    } catch (e: Exception) { Log.e("BadgerService", "PreShift refresh: ${e.message}") }
                }.launchIn(scope)

                // Refresh door status values when admin changes them
                channel.postgresChangeFlow<PostgresAction>("public") { table = "door_status_values" }.onEach {
                    try {
                        cachedDoorStatusValues = BadgerRepo.getDoorStatusValues()
                        _liveDoorStatusValues.value = cachedDoorStatusValues
                    } catch (e: Exception) { Log.e("BadgerService", "DoorStatusValues refresh: ${e.message}") }
                }.launchIn(scope)

                // Refresh dock lock status values when admin changes them
                channel.postgresChangeFlow<PostgresAction>("public") { table = "dock_lock_status_values" }.onEach {
                    try {
                        cachedDockLockStatusValues = BadgerRepo.getDockLockStatusValues()
                        _liveDockLockStatusValues.value = cachedDockLockStatusValues
                    } catch (e: Exception) { Log.e("BadgerService", "DockLockStatusValues refresh: ${'$'}{e.message}") }
                }.launchIn(scope)

                channel.subscribe()
                Log.d("BadgerService", "Realtime subscribed")
                RemoteLogger.i("BadgerService", "Realtime subscribed OK")

            } catch (e: Exception) {
                Log.e("BadgerService", "Realtime setup error: ${e.message}")
                RemoteLogger.e("BadgerService", "Realtime setup error: ${e.message}")
                delay(10_000)
                startRealtimeSync()
            }
        }
    }

    // ── Foreground notification ───────────────────────────────────────────────

    private fun buildServiceNotification(): Notification {
        val openIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val ttsToggleIntent = PendingIntent.getService(this, 1,
            Intent(this, BadgerService::class.java).apply { action = ACTION_TOGGLE_TTS },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stopIntent = PendingIntent.getService(this, 2,
            Intent(this, BadgerService::class.java).apply { action = ACTION_STOP }, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, NotificationHelper.CHANNEL_SERVICE)
            .setContentTitle("🦡 Badger Live")
            .setContentText("Say \"Badger\" to issue a command • TTS ${if (ttsEnabled) "ON 🔊" else "OFF 🔇"}")
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
