package com.badger.trucks.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.content.BroadcastReceiver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
        const val ACTION_APPLY_SETTINGS = "com.badger.trucks.APPLY_SETTINGS"
        const val ACTION_MANUAL_VOICE   = "com.badger.trucks.MANUAL_VOICE"

        var ttsEnabled = true
        var isRunning  = false

        // PTT state
        private val _pttRecording = MutableStateFlow(false)
        private val _pttIncoming  = MutableStateFlow(false)
        val pttRecording: StateFlow<Boolean> = _pttRecording.asStateFlow()
        val pttIncoming:  StateFlow<Boolean> = _pttIncoming.asStateFlow()

        // Voice command UI state
        private val _voiceProcessing = MutableStateFlow(false)
        private val _voiceFeedback   = MutableStateFlow<String?>(null)
        val voiceProcessing: StateFlow<Boolean>  = _voiceProcessing.asStateFlow()
        val voiceFeedback:   StateFlow<String?>  = _voiceFeedback.asStateFlow()

        // Live truck/door data â€” updated optimistically on voice commands
        private val _liveTrucks          = MutableStateFlow<List<LiveMovement>>(emptyList())
        private val _liveDoors           = MutableStateFlow<List<LoadingDoor>>(emptyList())
        private val _liveDoorStatusValues     = MutableStateFlow<List<DoorStatusValue>>(emptyList())
        private val _liveDockLockStatusValues  = MutableStateFlow<List<DockLockStatusValue>>(emptyList())
        val liveTrucks:              StateFlow<List<LiveMovement>>          = _liveTrucks.asStateFlow()
        val liveDoors:               StateFlow<List<LoadingDoor>>           = _liveDoors.asStateFlow()
        val liveDoorStatusValues:    StateFlow<List<DoorStatusValue>>       = _liveDoorStatusValues.asStateFlow()
        val liveDockLockStatusValues: StateFlow<List<DockLockStatusValue>>  = _liveDockLockStatusValues.asStateFlow()

        // Unread chat tracking â€” roomId -> unread count
        private val _unreadCounts   = MutableStateFlow<Map<Int, Int>>(emptyMap())
        private val _pendingRoomId  = MutableStateFlow<Int?>(null)   // room to auto-open
        val unreadCounts: StateFlow<Map<Int, Int>> = _unreadCounts.asStateFlow()
        val pendingRoomId: StateFlow<Int?> = _pendingRoomId.asStateFlow()
        val totalUnread: Int get() = _unreadCounts.value.values.sum()

        fun markRoomRead(roomId: Int) {
            _unreadCounts.value = _unreadCounts.value.toMutableMap().also { it.remove(roomId) }
            if (_pendingRoomId.value == roomId) _pendingRoomId.value = null
        }

        fun consumePendingRoom(): Int? {
            val id = _pendingRoomId.value
            _pendingRoomId.value = null
            return id
        }

        internal fun incrementUnread(roomId: Int) {
            _unreadCounts.value = _unreadCounts.value.toMutableMap().also {
                it[roomId] = (it[roomId] ?: 0) + 1
            }
            _pendingRoomId.value = roomId
        }
    }

    private val scope        = CoroutineScope(
        Dispatchers.IO + SupervisorJob() +
        CoroutineExceptionHandler { _, e ->
            if (e !is CancellationException) {
                RemoteLogger.e("BadgerService", "Unhandled coroutine: ${e::class.simpleName}: ${e.message}")
            }
        }
    )
    private val mainHandler  = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady       = false
    private var ttsParams: android.os.Bundle? = null
    private val ttsCallbacks   = mutableMapOf<String, () -> Unit>()
    private var ttsInitRetries = 0
    private var lastSpeakTime  = 0L  // for watchdog

    // Keep references so we can cleanly unsubscribe on restart
    private var realtimeChannel: io.github.jan.supabase.realtime.RealtimeChannel? = null
    private var chatRealtimeChannel: io.github.jan.supabase.realtime.RealtimeChannel? = null
    private var realtimeSyncJob: Job? = null
    @Volatile private var realtimeRestarting = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var pttManager:     PushToTalkManager?  = null
    private var commandRecognizer: BadgerSpeechRecognizer? = null
    private var loudnessEnhancer: android.media.audiofx.LoudnessEnhancer? = null

    // Audio focus
    private var audioManager: AudioManager? = null
    private var connectivityManager: ConnectivityManager? = null

    // Reconnect WebSocket when network comes back (Doze exit, WiFi switch, etc.)
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scope.launch {
                delay(1500)
                if (realtimeChannel?.status?.value?.name != "SUBSCRIBED") {
                    RemoteLogger.w("BadgerService", "Network available -- WebSocket not SUBSCRIBED, reconnecting")
                    startRealtimeSync()
                }
            }
        }
    }

    // Reconnect when device exits Doze mode
    private val dozeExitReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isDeviceIdleMode) {
                scope.launch {
                    delay(500)
                    if (realtimeChannel?.status?.value?.name != "SUBSCRIBED") {
                        RemoteLogger.w("BadgerService", "Doze exit -- WebSocket not SUBSCRIBED, reconnecting")
                        startRealtimeSync()
                    }
                }
            }
        }
    }
    private var audioFocusRequest: AudioFocusRequest? = null  // API 26+
    private var audioFocusHeld    = false
    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        // When we gain focus back (shouldn't happen normally), log it
        // Other apps auto-resume when we ABANDON focus via abandonAudioFocus()
        if (focusChange == android.media.AudioManager.AUDIOFOCUS_LOSS) audioFocusHeld = false
        Log.d("BadgerService", "Audio focus change: $focusChange")
    }

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

    // â”€â”€ Lifecycle â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "badger:ptt_wakelock").also { it.acquire() }

        NotificationHelper.createAllChannels(this)

        // Android 14+ can block FGS with microphone type after force-close.
        // Fall back to dataSync-only if that happens.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, buildServiceNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIF_ID, buildServiceNotification())
            }
        } catch (e: SecurityException) {
            Log.w("BadgerService", "FGS microphone blocked, falling back to dataSync: ${e.message}")
            RemoteLogger.w("BadgerService", "FGS microphone blocked — starting without mic FGS type")
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(NOTIF_ID, buildServiceNotification(),
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                } else {
                    startForeground(NOTIF_ID, buildServiceNotification())
                }
            } catch (e2: Exception) {
                Log.e("BadgerService", "FGS start failed completely: ${e2.message}")
                RemoteLogger.e("BadgerService", "FGS start failed: ${e2.message}")
                stopSelf()
                return
            }
        }

        tts = TextToSpeech(this, this)

        pttManager = PushToTalkManager(this, scope).also { mgr ->
            mgr.onIncoming = {
                _pttIncoming.value = true
                RemoteLogger.i("PTT", "Incoming PTT message received")
                NotificationHelper.postNotification(this, NotificationHelper.CHANNEL_SYSTEM, "ðŸ“» Incoming PTT", "Someone is talking on the radio", "ptt_incoming")
            }
            mgr.onDone = { _pttIncoming.value = false }
            mgr.startListening()
        }

        startRealtimeSync()

        // Register network and Doze-exit listeners so we reconnect immediately
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val netReq = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        try { connectivityManager?.registerNetworkCallback(netReq, networkCallback) } catch (_: Exception) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            registerReceiver(dozeExitReceiver,
                android.content.IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED))
        }
        scope.launch { refreshVoiceData() }
        Log.d("BadgerService", "Service created")
        RemoteLogger.i("BadgerService", "Service started â€” URL: ${com.badger.trucks.BuildConfig.SUPABASE_URL}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_TTS -> {
                ttsEnabled = !ttsEnabled
                updateServiceNotification()
                if (ttsEnabled) speak("Text to speech enabled")
                RemoteLogger.i("TTS", "TTS ${if (ttsEnabled) "ENABLED" else "DISABLED"}")
            }
            ACTION_PTT_START -> {
                _pttRecording.value = true
                pttManager?.startRecording()
                RemoteLogger.i("PTT", "PTT recording STARTED")
            }
            ACTION_PTT_STOP -> {
                _pttRecording.value = false
                pttManager?.stopRecording()
                RemoteLogger.i("PTT", "PTT recording STOPPED")
            }
            ACTION_APPLY_SETTINGS -> applySettingsLive()
            ACTION_MANUAL_VOICE   -> onManualVoiceTrigger()
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // Called when user swipes app away from recents.
    // stopWithTask="false" in manifest keeps the service alive,
    // but onTaskRemoved gives us a chance to explicitly restart it
    // on Samsung devices that ignore stopWithTask.
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        RemoteLogger.w("BadgerService", "onTaskRemoved — scheduling restart")
        val restartIntent = Intent(applicationContext, BadgerService::class.java)
        val pending = android.app.PendingIntent.getService(
            applicationContext, 1, restartIntent,
            android.app.PendingIntent.FLAG_ONE_SHOT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val alarm = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        alarm.set(
            android.app.AlarmManager.ELAPSED_REALTIME,
            android.os.SystemClock.elapsedRealtime() + 1000,
            pending
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        _pttRecording.value = false
        _pttIncoming.value  = false
        pttManager?.destroy()
        commandRecognizer?.destroy()
        // unsubscribe() is suspend â€” run in a short-lived scope before cancelling main scope
        val cleanupScope = CoroutineScope(Dispatchers.IO)
        cleanupScope.launch {
            try { realtimeChannel?.unsubscribe() } catch (_: Exception) {}
            try { chatRealtimeChannel?.unsubscribe() } catch (_: Exception) {}
        }
        realtimeChannel = null
        chatRealtimeChannel = null
        tts?.stop(); tts?.shutdown()
        ttsCallbacks.clear()
        abandonAudioFocus()
        loudnessEnhancer?.release()
        loudnessEnhancer = null
        try { connectivityManager?.unregisterNetworkCallback(networkCallback) } catch (_: Exception) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try { unregisterReceiver(dozeExitReceiver) } catch (_: Exception) {}
        }
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            // Use default TTS stream (STREAM_MUSIC) so LoudnessEnhancer and volume boost work.
            // STREAM_VOICE_CALL is quieter and ignores LoudnessEnhancer â€” don't use it.
            ttsParams = null
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onError(utteranceId: String?) {
                    utteranceId?.let { id ->
                        if (tts?.isSpeaking != true) abandonAudioFocus()
                        ttsCallbacks.remove(id)?.invoke()
                    }
                }
                override fun onDone(utteranceId: String?) {
                    utteranceId?.let { id ->
                        mainHandler.postDelayed({
                            if (tts?.isSpeaking != true) abandonAudioFocus()
                            ttsCallbacks.remove(id)?.invoke()
                        }, 600)
                    }
                }
            })
            ttsReady = true
            applyVolumeBoost()
            speak("Badger live monitoring active")
        }
    }

    // â”€â”€ Audio Focus â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private fun requestAudioFocus() {
        if (audioFocusHeld) return
        val mode = NotificationPrefsStore.getString(this, NotificationPrefsStore.KEY_AUDIO_FOCUS,
            NotificationPrefsStore.AUDIO_FOCUS_EXCLUSIVE) // default to exclusive â€” TikTok ignores transient
        if (mode == NotificationPrefsStore.AUDIO_FOCUS_OFF) return
        val focusType = when (mode) {
            NotificationPrefsStore.AUDIO_FOCUS_DUCK -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            else -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE // transient + exclusive both use exclusive
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                // NAVIGATION_GUIDANCE keeps audio on speaker/headphones (not earpiece)
                // while still getting high-priority exclusive focus over media apps
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            audioFocusRequest = AudioFocusRequest.Builder(focusType)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(audioFocusListener)
                .setAcceptsDelayedFocusGain(false)
                .setWillPauseWhenDucked(true) // force pause rather than duck
                .build()
                .also { val r = audioManager?.requestAudioFocus(it); if (r == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED) audioFocusHeld = true }
        } else {
            @Suppress("DEPRECATION")
            audioManager?.requestAudioFocus(audioFocusListener, AudioManager.STREAM_VOICE_CALL, focusType)
        }
    }

    private fun abandonAudioFocus() {
        audioFocusHeld = false
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

    // â”€â”€ Voice command flow (manual trigger from mic FAB) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private fun onManualVoiceTrigger() {
        if (_voiceProcessing.value) return
        _voiceProcessing.value = false
        _voiceFeedback.value   = null
        if (commandRecognizer == null) commandRecognizer = BadgerSpeechRecognizer(this)
        mainHandler.post {
            commandRecognizer?.startListening(
                onResult = { text -> onCommandResult(text) },
                onError  = { err  -> onCommandError(err)  }
            )
        }
    }

    private fun onCommandResult(text: String) {
        _voiceProcessing.value = true
        RemoteLogger.i("Voice", "Command heard: \"$text\"")
        scope.launch {
            try {
                val cmd    = VoiceCommandProcessor.parseCommand(text, cachedTrucks, cachedDoors, cachedStatuses)
                val result = VoiceCommandProcessor.executeCommand(cmd, cachedTrucks, cachedDoors, cachedStatuses)

                val (feedback, spokenText) = when (result) {
                    is VoiceResult.Success -> "âœ… ${result.description}" to result.description
                    is VoiceResult.Error   -> "âŒ ${result.message}" to "Sorry, ${result.message}"
                    VoiceResult.Unknown    -> "ðŸ¤” Didn't understand" to "I didn't understand that"
                }

                when (result) {
                    is VoiceResult.Success -> RemoteLogger.i("Voice", "âœ… ${result.description}")
                    is VoiceResult.Error   -> RemoteLogger.w("Voice", "âŒ ${result.message} â€” heard: \"$text\"")
                    VoiceResult.Unknown    -> RemoteLogger.w("Voice", "ðŸ¤” Unknown: \"$text\"")
                }

                // Optimistic UI update
                if (result is VoiceResult.Success) {
                    when (cmd.action) {
                        "truck_status" -> {
                            val newStatus = cachedStatuses.find { it.statusName == cmd.status }
                            cachedTrucks = cachedTrucks.map { t ->
                                if (t.truckNumber == cmd.truck)
                                    t.copy(statusValues = LiveMovementStatus(statusName = newStatus?.statusName, statusColor = newStatus?.statusColor))
                                else t
                            }
                        }
                        "door_status" -> {
                            cachedDoors = cachedDoors.map { d ->
                                if (d.doorName == cmd.door) d.copy(doorStatus = cmd.status ?: "") else d
                            }
                        }
                    }
                    scope.launch { refreshVoiceData() }
                }

                _voiceFeedback.value   = feedback
                _voiceProcessing.value = false
                speak(spokenText) {
                    scope.launch {
                        delay(if (result is VoiceResult.Success) 1500L else 3000L)
                        _voiceFeedback.value = null
                    }
                }
            } catch (e: Exception) {
                Log.e("BadgerService", "Voice command error", e)
                _voiceProcessing.value = false
                _voiceFeedback.value   = "âŒ Error: ${e.message}"
                delay(3000)
                _voiceFeedback.value = null
            }
        }
    }

    private fun onCommandError(err: String) {
        _voiceProcessing.value = false
        _voiceFeedback.value   = "âŒ $err"
        RemoteLogger.w("Voice", "Speech recognition error: $err")
        scope.launch {
            delay(2500)
            _voiceFeedback.value = null
        }
    }

    private suspend fun refreshVoiceData() {
        try {
            cachedTrucks  = BadgerRepo.getLiveMovement()
            cachedDoors   = BadgerRepo.getLoadingDoors()
            cachedStatuses = BadgerRepo.getStatuses()
            cachedDoorStatusValues = BadgerRepo.getDoorStatusValues()
            _liveDoorStatusValues.value = cachedDoorStatusValues
            cachedDockLockStatusValues = BadgerRepo.getDockLockStatusValues()
            _liveDockLockStatusValues.value = cachedDockLockStatusValues
        } catch (e: Exception) {
            Log.w("BadgerService", "refreshVoiceData error: ${e.message}")
        }
    }

    private fun applySettingsLive() {
        applyVolumeBoost()
    }

    private fun applyVolumeBoost() {
        val level = NotificationPrefsStore.getString(this, NotificationPrefsStore.KEY_VOLUME_BOOST, NotificationPrefsStore.VOLUME_BOOST_OFF)
        val am = audioManager ?: return

        // Always release previous enhancer first
        loudnessEnhancer?.release()
        loudnessEnhancer = null

        when (level) {
            NotificationPrefsStore.VOLUME_BOOST_OFF -> {
                Log.d("BadgerService", "VolumeBoost: OFF")
            }
            else -> {
                // Max out system volume so boost has full headroom
                val maxMusic = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusic, 0)

                val gainMb = when (level) {
                    NotificationPrefsStore.VOLUME_BOOST_LOW    -> 400   // +4 dB
                    NotificationPrefsStore.VOLUME_BOOST_MEDIUM -> 800   // +8 dB
                    NotificationPrefsStore.VOLUME_BOOST_MAX    -> 1200  // +12 dB
                    else -> 0
                }

                // LoudnessEnhancer on session 0 = global output mix, which covers TTS output
                try {
                    loudnessEnhancer = android.media.audiofx.LoudnessEnhancer(0).apply {
                        setTargetGain(gainMb)
                        enabled = true
                    }
                    Log.d("BadgerService", "VolumeBoost: $level (+${gainMb / 100}dB)")
                    RemoteLogger.i("BadgerService", "VolumeBoost applied: $level +${gainMb / 100}dB")
                } catch (e: Exception) {
                    Log.w("BadgerService", "VolumeBoost failed: ${e.message}")
                }
            }
        }
    }
    // â”€â”€ TTS â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private fun speak(text: String, onDone: (() -> Unit)? = null) {
        val ttsOn = NotificationPrefsStore.get(this, NotificationPrefsStore.KEY_CHANNEL_TTS)
        if (!ttsEnabled || !ttsOn) { onDone?.invoke(); return }

        // Re-init TTS if engine died (Android reclaims it in background)
        if (!ttsReady || tts == null) {
            Log.w("BadgerService", "TTS not ready, re-initializing for: $text")
            tts?.shutdown()
            ttsCallbacks.clear()
            ttsReady = false
            tts = TextToSpeech(this, this)
            onDone?.invoke()
            return
        }

        val boostLevel = NotificationPrefsStore.getString(this, NotificationPrefsStore.KEY_VOLUME_BOOST, NotificationPrefsStore.VOLUME_BOOST_OFF)
        if (boostLevel != NotificationPrefsStore.VOLUME_BOOST_OFF) {
            audioManager?.let { am ->
                am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
            }
        }
        val uttId = "badger_${System.currentTimeMillis()}"
        if (onDone != null) ttsCallbacks[uttId] = onDone
        lastSpeakTime = System.currentTimeMillis()
        requestAudioFocus()

        val result = tts?.speak(text, TextToSpeech.QUEUE_ADD, ttsParams, uttId)
        if (result != TextToSpeech.SUCCESS) {
            Log.e("BadgerService", "TTS speak() failed ($result) for: $text — reinitializing")
            ttsCallbacks.remove(uttId)
            abandonAudioFocus()
            // Engine is broken, reinit
            tts?.shutdown()
            ttsCallbacks.clear()
            ttsReady = false
            tts = TextToSpeech(this, this)
            onDone?.invoke()
            return
        }

        // Failsafe: if TTS doesn't finish within 10s, force stop and release focus
        mainHandler.postDelayed({
            if (tts?.isSpeaking == true) Log.w("BadgerService", "TTS queue still running at 10s mark -- not stopping")
            if (tts?.isSpeaking != true) abandonAudioFocus()
            ttsCallbacks.remove(uttId)?.invoke()
        }, 10_000)
    }

    // â”€â”€ Push notification helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private fun canNotify(eventKey: String): Boolean {
        val eventOn   = NotificationPrefsStore.get(this, eventKey)
        val channelOn = NotificationPrefsStore.get(this, NotificationPrefsStore.KEY_CHANNEL_APP)
        return eventOn && channelOn
    }

    private fun pushNotif(channelId: String, title: String, body: String, tag: String? = null) {
        NotificationHelper.postNotification(this, channelId, title, body, tag)
    }

    // â”€â”€ Realtime data sync â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private fun startRealtimeSync() {
        if (realtimeRestarting) { RemoteLogger.w("BadgerService", "startRealtimeSync skipped â€” already restarting"); return }
        realtimeRestarting = true
        realtimeSyncJob?.cancel()
        realtimeSyncJob = scope.launch {
            try {
                val oldMain = realtimeChannel
                val oldChat = chatRealtimeChannel
                realtimeChannel = null
                chatRealtimeChannel = null
                if (oldMain != null) { try { oldMain.unsubscribe() } catch (_: Exception) {}; try { BadgerRepo.removeChannel(oldMain) } catch (_: Exception) {} }
                if (oldChat != null) { try { oldChat.unsubscribe() } catch (_: Exception) {}; try { BadgerRepo.removeChannel(oldChat) } catch (_: Exception) {} }
                delay(500) // let supabase-kt internal state settle before registering new flows
                realtimeRestarting = false

                BadgerRepo.getLiveMovement().forEach { knownStatuses[it.truckNumber] = it.statusName }
                BadgerRepo.getLoadingDoors().forEach { knownDoorStatus[it.doorName]  = it.doorStatus }
                BadgerRepo.getStagingDoors().forEach { knownPreshift[it.id]          = Pair(it.inFront, it.inBack) }

                val channelName = "badger-svc-${java.util.UUID.randomUUID()}"
                val channel = BadgerRepo.realtimeChannel(channelName)
                realtimeChannel = channel

                // Guard: Supabase WebSocket reconnect can auto-subscribe a newly created channel
                // before we register flows, causing postgresChangeFlow to throw IllegalStateException.
                if (channel.status.value.name != "UNSUBSCRIBED") {
                    RemoteLogger.w("BadgerService", "Channel $channelName already in ${channel.status.value.name} — unsubscribing before flow registration")
                    try { channel.unsubscribe() } catch (_: Exception) {}
                    delay(300)
                }

                // Register ALL flows before subscribe() — use launchIn(this) so they
                // are children of this job and cancel together, preventing AtomicMutableList race
                channel.postgresChangeFlow<PostgresAction>("public") { table = "live_movement" }.onEach { action ->
                    try {
                        val record = when (action) {
                            is PostgresAction.Update -> action.record
                            is PostgresAction.Insert -> action.record
                            else -> null
                        }
                        if (record != null) {
                            val truckNum = record["truck_number"]?.toString()?.trim('"') ?: ""
                            val statusId = record["status_id"]?.toString()?.trim('"')?.toIntOrNull()
                            // If status not in cache (new custom status), refresh statuses first
                            var currName = cachedStatuses.find { it.id == statusId }?.statusName
                            if (currName == null && statusId != null) {
                                cachedStatuses = BadgerRepo.getStatuses()
                                currName = cachedStatuses.find { it.id == statusId }?.statusName
                            }
                            if (truckNum.isNotBlank()) {
                                val prev = knownStatuses[truckNum]
                                if (currName != null && prev != currName) {
                                    speak("Truck $truckNum, $currName")
                                    if (canNotify(NotificationPrefsStore.KEY_TRUCK_STATUS))
                                        pushNotif(NotificationHelper.CHANNEL_TRUCK_STATUS,
                                            "Truck $truckNum", "${prev ?: "New"} -> $currName", "truck_$truckNum")
                                }
                                // Only store valid status name — never store null which breaks next comparison
                                if (currName != null) knownStatuses[truckNum] = currName
                            }
                        }
                        // Background cache refresh for location/other fields only
                        val updated = BadgerRepo.getLiveMovement()
                        cachedTrucks = updated
                        updated.forEach { t -> if (t.statusName != null) knownStatuses[t.truckNumber] = t.statusName }
                        val currSet = updated.map { it.truckNumber }.toSet()
                        knownStatuses.keys.filter { it !in currSet }.forEach { knownStatuses.remove(it) }
                    } catch (e: Exception) { Log.e("BadgerService", "Truck refresh: ${e.message}") }
                }.launchIn(this)

                // Keep cachedStatuses current so new custom statuses are announced immediately
                channel.postgresChangeFlow<PostgresAction>("public") { table = "status_values" }.onEach {
                    try {
                        cachedStatuses = BadgerRepo.getStatuses()
                        RemoteLogger.i("BadgerService", "status_values updated: ${cachedStatuses.size} statuses")
                    } catch (e: Exception) { Log.e("BadgerService", "StatusValues: ${e.message}") }
                }.launchIn(this)

                channel.postgresChangeFlow<PostgresAction>("public") { table = "loading_doors" }.onEach {
                    try {
                        val updated = BadgerRepo.getLoadingDoors()
                        updated.forEach { door ->
                            val prev = knownDoorStatus[door.doorName]
                            val curr = door.doorStatus
                            if (prev != null && curr != prev && curr.isNotBlank()) {
                                speak("Door ${door.doorName}, $curr")
                                if (canNotify(NotificationPrefsStore.KEY_DOOR_STATUS))
                                    pushNotif(NotificationHelper.CHANNEL_DOOR_STATUS, "ðŸšª Door ${door.doorName}", "$prev â†’ $curr", "door_${door.doorName}")
                            }
                            knownDoorStatus[door.doorName] = curr
                        }
                        cachedDoors = updated
                    } catch (e: Exception) { Log.e("BadgerService", "Door refresh: ${e.message}") }
                }.launchIn(this)

                channel.postgresChangeFlow<PostgresAction>("public") { table = "staging_doors" }.onEach {
                    try {
                        val changes = mutableListOf<String>()
                        BadgerRepo.getStagingDoors().forEach { door ->
                            val prev = knownPreshift[door.id]
                            val curr = Pair(door.inFront, door.inBack)
                            if (prev != null && prev != curr) {
                                if (prev.first  != door.inFront) changes.add("${door.doorLabel} front: ${prev.first ?: "empty"} â†’ ${door.inFront ?: "empty"}")
                                if (prev.second != door.inBack)  changes.add("${door.doorLabel} back: ${prev.second ?: "empty"} â†’ ${door.inBack ?: "empty"}")
                            }
                            knownPreshift[door.id] = curr
                        }
                        if (changes.isNotEmpty()) {
                            speak("Preshift updated")
                            if (canNotify(NotificationPrefsStore.KEY_PRESHIFT))
                                pushNotif(NotificationHelper.CHANNEL_PRESHIFT, "ðŸ“‹ PreShift Updated", changes.joinToString("\n"), "preshift_change")
                        }
                    } catch (e: Exception) { Log.e("BadgerService", "PreShift refresh: ${e.message}") }
                }.launchIn(this)

                channel.postgresChangeFlow<PostgresAction>("public") { table = "door_status_values" }.onEach {
                    try { cachedDoorStatusValues = BadgerRepo.getDoorStatusValues(); _liveDoorStatusValues.value = cachedDoorStatusValues }
                    catch (e: Exception) { Log.e("BadgerService", "DoorStatusValues: ${e.message}") }
                }.launchIn(this)

                channel.postgresChangeFlow<PostgresAction>("public") { table = "dock_lock_status_values" }.onEach {
                    try { cachedDockLockStatusValues = BadgerRepo.getDockLockStatusValues(); _liveDockLockStatusValues.value = cachedDockLockStatusValues }
                    catch (e: Exception) { Log.e("BadgerService", "DockLockStatusValues: ${e.message}") }
                }.launchIn(this)

                // Chat — separate channel subscribed before main to avoid ordering issues
                val chatChannel = BadgerRepo.realtimeChannel("badger-chat-${java.util.UUID.randomUUID()}")
                chatRealtimeChannel = chatChannel
                chatChannel.postgresChangeFlow<PostgresAction.Insert>("public") { table = "messages" }.onEach { action ->
                    try {
                        val roomId   = action.record["room_id"]?.toString()?.trim('"')?.toIntOrNull() ?: return@onEach
                        val senderId = action.record["sender_id"]?.toString()?.trim('"') ?: return@onEach
                        val myId     = BadgerRepo.currentUserId() ?: return@onEach
                        if (senderId == myId) return@onEach
                        incrementUnread(roomId)
                    } catch (e: Exception) { Log.w("BadgerService", "Chat unread: ${e.message}") }
                }.launchIn(this)
                chatChannel.subscribe()

                channel.subscribe(blockUntilSubscribed = true)
                RemoteLogger.i("BadgerService", "Realtime subscribed OK â€” $channelName status=${channel.status.value.name}")

                // Heartbeat -- 15s: WebSocket ping, TTS watchdog, silent cache refresh
                while (isActive) {
                    delay(15_000L)

                    // Check channel health
                    val statusName = channel.status.value.name
                    if (statusName != "SUBSCRIBED") {
                        RemoteLogger.w("BadgerService", "Heartbeat: channel $statusName -- restarting")
                        startRealtimeSync()
                        return@launch
                    }

                    // Active ping -- detects dead Samsung WebSocket TCP connections
                    try { BadgerRepo.ping() } catch (e: Exception) {
                        RemoteLogger.w("BadgerService", "Heartbeat ping failed: ${e.message} -- restarting")
                        startRealtimeSync(); return@launch
                    }

                    // TTS watchdog -- reinit if engine silently died
                    // Check both ttsReady flag AND whether speak() actually works
                    if (ttsEnabled) {
                        if (!ttsReady || tts == null) {
                            RemoteLogger.w("BadgerService", "TTS watchdog: engine dead (ttsReady=$ttsReady), reinitializing")
                            tts?.stop(); tts?.shutdown(); ttsCallbacks.clear(); ttsReady = false
                            tts = TextToSpeech(this@BadgerService, this@BadgerService)
                        } else if (lastSpeakTime > 0 && System.currentTimeMillis() - lastSpeakTime > 120_000) {
                            // If last speak was 2+ minutes ago, test the engine with a silent check
                            val testResult = tts?.speak("", TextToSpeech.QUEUE_ADD, null, "watchdog_test")
                            if (testResult != TextToSpeech.SUCCESS) {
                                RemoteLogger.w("BadgerService", "TTS watchdog: engine unresponsive, reinitializing")
                                tts?.stop(); tts?.shutdown(); ttsCallbacks.clear(); ttsReady = false
                                tts = TextToSpeech(this@BadgerService, this@BadgerService)
                            }
                        }
                    }

                    // Silent cache sync -- no TTS/notifications
                    try {
                        cachedTrucks = BadgerRepo.getLiveMovement().also { list -> list.forEach { knownStatuses[it.truckNumber] = it.statusName } }
                        cachedDoors  = BadgerRepo.getLoadingDoors().also  { list -> list.forEach { knownDoorStatus[it.doorName]  = it.doorStatus } }
                    } catch (e: Exception) { Log.w("BadgerService", "Heartbeat poll: ${e.message}") }
                }

            } catch (e: Exception) {
                realtimeRestarting = false
                RemoteLogger.e("BadgerService", "Realtime setup error: ${e.message}")
                delay(10_000)
                startRealtimeSync()
            }
        }
    }

    // â”€â”€ Foreground notification â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private fun buildServiceNotification(): Notification {
        val openIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val ttsToggleIntent = PendingIntent.getService(this, 1,
            Intent(this, BadgerService::class.java).apply { action = ACTION_TOGGLE_TTS },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stopIntent = PendingIntent.getService(this, 2,
            Intent(this, BadgerService::class.java).apply { action = ACTION_STOP }, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, NotificationHelper.CHANNEL_SERVICE)
            .setContentTitle("ðŸ¦¡ Badger Live")
            .setContentText("Say \"Badger\" to issue a command â€¢ TTS ${if (ttsEnabled) "ON ðŸ”Š" else "OFF ðŸ”‡"}")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_btn_speak_now, if (ttsEnabled) "ðŸ”Š TTS ON" else "ðŸ”‡ TTS OFF", ttsToggleIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .build()
    }

    private fun updateServiceNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildServiceNotification())
    }
}
