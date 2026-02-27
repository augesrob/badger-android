package com.badger.trucks.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import com.badger.trucks.BadgerApp
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

private const val TAG         = "PushToTalk"
private const val SAMPLE_RATE = 8000
private const val ENCODING    = AudioFormat.ENCODING_PCM_16BIT
private const val TABLE       = "ptt_messages"

// How often the watchdog checks channel health (ms)
private const val WATCHDOG_INTERVAL_MS = 10_000L

class PushToTalkManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val client get() = BadgerApp.supabase

    private var recorder: AudioRecord? = null
    private var recordJob: Job?  = null
    private var listenJob: Job?  = null
    private var watchdogJob: Job? = null
    private var listenChannel: RealtimeChannel? = null

    @Volatile private var recording = false
    @Volatile private var destroyed  = false

    var onIncoming: (() -> Unit)? = null
    var onDone:     (() -> Unit)? = null

    // ── Public API ────────────────────────────────────────────────────────────

    fun startListening() {
        destroyed = false
        doSubscribe()
        startWatchdog()
    }

    fun startRecording() {
        stopRecording()
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, ENCODING)
        if (minBuf <= 0) { Log.e(TAG, "getMinBufferSize failed: $minBuf"); return }

        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, ENCODING, minBuf * 4
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to init"); rec.release(); return
        }

        recorder = rec
        recording = true
        rec.startRecording()
        Log.d(TAG, "PTT recording started")

        recordJob = scope.launch(Dispatchers.IO) {
            val chunks = mutableListOf<ByteArray>()
            val buf = ByteArray(minBuf)
            while (isActive && recording) {
                val read = rec.read(buf, 0, buf.size)
                if (read > 0) chunks.add(buf.copyOf(read))
            }
            val total = chunks.sumOf { it.size }
            if (total == 0) { Log.w(TAG, "PTT: no audio captured"); return@launch }

            val pcm = ByteArray(total).also { out ->
                var pos = 0; chunks.forEach { c -> c.copyInto(out, pos); pos += c.size }
            }
            val b64 = Base64.encodeToString(pcm, Base64.NO_WRAP)
            Log.d(TAG, "PTT sending ${pcm.size} bytes")
            try {
                client.postgrest[TABLE].insert(buildJsonObject {
                    put("audio_b64", b64)
                    put("sender", "device")
                })
                Log.d(TAG, "PTT sent OK")
            } catch (e: Exception) {
                Log.e(TAG, "PTT send error", e)
            }
        }
    }

    fun stopRecording() {
        recording = false
        recorder?.let { it.stop(); it.release() }
        recorder = null
    }

    fun destroy() {
        destroyed = true
        stopRecording()
        recordJob?.cancel()
        listenJob?.cancel()
        watchdogJob?.cancel()
        scope.launch { try { listenChannel?.unsubscribe() } catch (_: Exception) {} }
        listenChannel = null
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun doSubscribe() {
        if (destroyed) return

        listenJob?.cancel()
        listenChannel?.let { ch ->
            scope.launch { try { ch.unsubscribe() } catch (_: Exception) {} }
        }

        val ch = client.channel("badger-ptt-listen")
        listenChannel = ch

        listenJob = ch.postgresChangeFlow<PostgresAction>("public") {
            table = TABLE
        }.onEach { action ->
            if (action !is PostgresAction.Insert) return@onEach
            try {
                val b64 = action.record["audio_b64"]?.jsonPrimitive?.content
                if (b64.isNullOrEmpty()) { Log.w(TAG, "PTT: row with no audio_b64"); return@onEach }

                Log.d(TAG, "PTT incoming: ${b64.length} b64 chars")
                scope.launch(Dispatchers.Main) { onIncoming?.invoke() }

                playPcm(Base64.decode(b64, Base64.DEFAULT))

                scope.launch(Dispatchers.Main) { onDone?.invoke() }

                val rowId = action.record["id"]?.jsonPrimitive?.longOrNull
                if (rowId != null) {
                    scope.launch(Dispatchers.IO) {
                        try { client.postgrest[TABLE].delete { filter { lte("id", rowId.toString()) } }
                        } catch (e: Exception) { Log.w(TAG, "PTT cleanup error: ${e.message}") }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "PTT receive error", e)
                scope.launch(Dispatchers.Main) { onDone?.invoke() }
            }
        }.launchIn(scope)

        scope.launch {
            try {
                ch.subscribe()
                Log.d(TAG, "PTT channel subscribed")
            } catch (e: Exception) {
                Log.e(TAG, "PTT subscribe error: ${e.message}")
                // watchdog will retry
            }
        }
    }

    /**
     * Watchdog: checks every 10s. If the channel is not SUBSCRIBED it tears down
     * and rebuilds the whole channel from scratch — no hanging partial states.
     */
    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive && !destroyed) {
                delay(WATCHDOG_INTERVAL_MS)
                val status = listenChannel?.status?.value
                if (status != RealtimeChannel.Status.SUBSCRIBED) {
                    Log.w(TAG, "PTT watchdog: channel status=$status — reconnecting")
                    doSubscribe()
                } else {
                    Log.d(TAG, "PTT watchdog: OK")
                }
            }
        }
    }

    private suspend fun playPcm(pcm: ByteArray) = withContext(Dispatchers.IO) {
        if (pcm.isEmpty()) { Log.w(TAG, "PTT: empty PCM"); return@withContext }
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Request audio focus so other apps (YouTube, PocketFM, etc.) pause
        val focusResult: Int
        val focusRequest: android.media.AudioFocusRequest?
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val req = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attrs)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener {}
                .build()
            focusRequest = req
            focusResult = audioManager.requestAudioFocus(req)
        } else {
            focusRequest = null
            @Suppress("DEPRECATION")
            focusResult = audioManager.requestAudioFocus(
                null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
        }
        Log.d(TAG, "PTT audio focus result: $focusResult")

        try {
            val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, ENCODING)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE).setEncoding(ENCODING)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
                )
                .setBufferSizeInBytes(maxOf(minBuf, pcm.size))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            track.write(pcm, 0, pcm.size)
            track.setVolume(AudioTrack.getMaxVolume())
            track.play()

            val durationMs = (pcm.size.toLong() * 1000L) / (SAMPLE_RATE * 2)
            Log.d(TAG, "PTT playing ~${durationMs}ms")
            delay(durationMs + 300)
            track.stop(); track.release()
        } catch (e: Exception) {
            Log.e(TAG, "PTT playback error", e)
        } finally {
            // Abandon focus so paused apps auto-resume
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
            Log.d(TAG, "PTT audio focus released — other apps should resume")
        }
    }
}
