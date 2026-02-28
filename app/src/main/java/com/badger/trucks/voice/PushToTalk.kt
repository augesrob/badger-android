package com.badger.trucks.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import com.badger.trucks.util.RemoteLogger
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import com.badger.trucks.BadgerApp
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
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

private const val TAG          = "PushToTalk"
private const val SAMPLE_RATE  = 8000
private const val ENCODING     = AudioFormat.ENCODING_PCM_16BIT
private const val TABLE        = "ptt_messages"

// How often the watchdog checks channel health (ms)
private const val WATCHDOG_INTERVAL_MS  = 8_000L
// Keep-alive ping interval — prevents idle disconnect on long shifts
private const val KEEPALIVE_INTERVAL_MS = 25_000L
// Only play missed messages received within this window (avoid playing ancient ones)
private const val MISSED_WINDOW_MS      = 5 * 60 * 1000L  // 5 minutes

class PushToTalkManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val client get() = BadgerApp.supabase

    private var recorder: AudioRecord? = null
    private var recordJob: Job?    = null
    private var listenJob: Job?    = null
    private var watchdogJob: Job?  = null
    private var keepAliveJob: Job? = null
    private var listenChannel: RealtimeChannel? = null

    @Volatile private var recording  = false
    @Volatile private var destroyed  = false
    @Volatile private var lastSeenId = 0L   // highest message id we've seen/played

    var onIncoming: (() -> Unit)? = null
    var onDone:     (() -> Unit)? = null

    // ── Public API ────────────────────────────────────────────────────────────

    fun startListening() {
        destroyed = false
        doSubscribe()
        startWatchdog()
        startKeepAlive()
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
                RemoteLogger.i("PTT", "PTT sent OK — ${pcm.size} bytes")
            } catch (e: Exception) {
                Log.e(TAG, "PTT send error", e)
                RemoteLogger.e("PTT", "PTT send FAILED: ${e.message}")
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
        keepAliveJob?.cancel()
        scope.launch { try { listenChannel?.unsubscribe() } catch (_: Exception) {} }
        listenChannel = null
    }

    // ── Keep-alive ────────────────────────────────────────────────────────────
    // Supabase realtime can silently drop idle connections after ~60s of no events.
    // We ping the REST API every 25s so the shift never goes silent.

    private fun startKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = scope.launch {
            while (isActive && !destroyed) {
                delay(KEEPALIVE_INTERVAL_MS)
                try {
                    // Lightweight query — just checks connectivity + keeps the connection warm
                    client.postgrest[TABLE].select { limit(1) }
                    Log.d(TAG, "PTT keep-alive ping OK")
                } catch (e: Exception) {
                    Log.w(TAG, "PTT keep-alive ping failed: ${e.message}")
                }
            }
        }
    }

    // ── Missed message recovery ───────────────────────────────────────────────
    // On reconnect, fetch any messages with id > lastSeenId that arrived while
    // we were disconnected. Only play ones from the last 5 minutes to avoid
    // playing hour-old messages after a long disconnect.

    private fun playMissedMessages() {
        if (lastSeenId == 0L) {
            // First connect — just record current max id as baseline, don't play anything
            scope.launch(Dispatchers.IO) {
                try {
                    val rows = client.postgrest[TABLE].select {
                        order("id", Order.DESCENDING)
                        limit(1)
                    }
                    val json = rows.data
                    // Parse the max id from JSON array string like [{"id":42,...}]
                    val idMatch = Regex(""""id"\s*:\s*(\d+)""").find(json)
                    lastSeenId = idMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    Log.d(TAG, "PTT baseline id set to $lastSeenId")
                } catch (e: Exception) {
                    Log.w(TAG, "PTT baseline query failed: ${e.message}")
                }
            }
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                val rows = client.postgrest[TABLE].select {
                    order("id", Order.ASCENDING)
                }
                val json = rows.data
                Log.d(TAG, "PTT missed check, raw: ${json.take(200)}")

                // Parse rows manually from JSON array
                val idPattern    = Regex(""""id"\s*:\s*(\d+)""")
                val b64Pattern   = Regex(""""audio_b64"\s*:\s*"([^"]+)"""")
                val timePattern  = Regex(""""created_at"\s*:\s*"([^"]+)"""")

                // Split into per-row objects crudely
                val rowObjects = json.split("""{"id"""").drop(1).map { """{"id$it""" }
                val now = System.currentTimeMillis()

                var playedCount = 0
                for (row in rowObjects) {
                    val rowId  = idPattern.find(row)?.groupValues?.get(1)?.toLongOrNull() ?: continue
                    if (rowId <= lastSeenId) continue

                    // Check created_at is within window
                    val createdStr = timePattern.find(row)?.groupValues?.get(1)
                    val createdMs  = runCatching {
                        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                            .also { it.timeZone = java.util.TimeZone.getTimeZone("UTC") }
                            .parse(createdStr?.take(19) ?: "")?.time ?: 0L
                    }.getOrDefault(0L)

                    if (createdMs > 0 && (now - createdMs) > MISSED_WINDOW_MS) {
                        Log.d(TAG, "PTT skipping old missed message id=$rowId")
                        lastSeenId = maxOf(lastSeenId, rowId)
                        continue
                    }

                    val b64 = b64Pattern.find(row)?.groupValues?.get(1)
                    if (b64.isNullOrEmpty()) continue

                    Log.d(TAG, "PTT playing missed message id=$rowId")
                    scope.launch(Dispatchers.Main) { onIncoming?.invoke() }
                    playPcm(Base64.decode(b64, Base64.DEFAULT))
                    scope.launch(Dispatchers.Main) { onDone?.invoke() }

                    lastSeenId = maxOf(lastSeenId, rowId)
                    playedCount++

                    // Small gap between back-to-back missed messages
                    if (playedCount > 0) delay(500)
                }

                if (playedCount > 0) {
                    Log.d(TAG, "PTT played $playedCount missed message(s)")
                    // Clean up played messages
                    try {
                        client.postgrest[TABLE].delete { filter { lte("id", lastSeenId.toString()) } }
                    } catch (e: Exception) {
                        Log.w(TAG, "PTT missed cleanup error: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "PTT missed message check failed: ${e.message}")
            }
        }
    }

    // ── Watchdog ─────────────────────────────────────────────────────────────

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
                    Log.d(TAG, "PTT watchdog: OK (lastSeenId=$lastSeenId)")
                }
            }
        }
    }

    // ── Subscribe ─────────────────────────────────────────────────────────────

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
                val rowId = action.record["id"]?.jsonPrimitive?.longOrNull
                val b64   = action.record["audio_b64"]?.jsonPrimitive?.content
                if (b64.isNullOrEmpty()) { Log.w(TAG, "PTT: row with no audio_b64"); return@onEach }

                if (rowId != null) lastSeenId = maxOf(lastSeenId, rowId)
                Log.d(TAG, "PTT incoming: ${b64.length} b64 chars (id=$rowId)")

                scope.launch(Dispatchers.Main) { onIncoming?.invoke() }
                playPcm(Base64.decode(b64, Base64.DEFAULT))
                scope.launch(Dispatchers.Main) { onDone?.invoke() }

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
                Log.d(TAG, "PTT channel subscribed — checking for missed messages")
                playMissedMessages()
            } catch (e: Exception) {
                Log.e(TAG, "PTT subscribe error: ${e.message}")
            }
        }
    }

    // ── Playback ──────────────────────────────────────────────────────────────

    private suspend fun playPcm(pcm: ByteArray) = withContext(Dispatchers.IO) {
        if (pcm.isEmpty()) { Log.w(TAG, "PTT: empty PCM"); return@withContext }
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Request audio focus so other apps (YouTube, PocketFM, etc.) pause
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
            audioManager.requestAudioFocus(req)
        } else {
            focusRequest = null
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }

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
            Log.d(TAG, "PTT audio focus released")
        }
    }
}
