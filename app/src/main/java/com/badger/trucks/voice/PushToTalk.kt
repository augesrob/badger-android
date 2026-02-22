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

class PushToTalkManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val client get() = BadgerApp.supabase
    private var recorder: AudioRecord? = null
    private var recordJob: Job? = null
    private var listenJob: Job? = null
    private var listenChannel: io.github.jan.supabase.realtime.RealtimeChannel? = null
    @Volatile private var recording = false

    var onIncoming: (() -> Unit)? = null
    var onDone: (() -> Unit)? = null

    // ── Start listening for incoming PTT via postgres realtime ────────────────
    fun startListening() {
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
                // Extract audio_b64 from the raw JsonObject record
                val b64 = action.record["audio_b64"]?.jsonPrimitive?.content
                if (b64.isNullOrEmpty()) {
                    Log.w(TAG, "PTT: received row with no audio_b64")
                    return@onEach
                }
                Log.d(TAG, "PTT incoming: ${b64.length} b64 chars")
                scope.launch(Dispatchers.Main) { onIncoming?.invoke() }

                val pcm = Base64.decode(b64, Base64.DEFAULT)
                playPcm(pcm)

                scope.launch(Dispatchers.Main) { onDone?.invoke() }

                // Clean up this row
                val rowId = action.record["id"]?.jsonPrimitive?.longOrNull
                if (rowId != null) {
                    scope.launch(Dispatchers.IO) {
                        try {
                            client.postgrest[TABLE].delete {
                                filter { lte("id", rowId.toString()) }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "PTT cleanup error: ${e.message}")
                        }
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
                Log.d(TAG, "PTT listening on $TABLE")
            } catch (e: Exception) {
                Log.e(TAG, "PTT subscribe error", e)
            }
        }
    }

    // ── Start recording ───────────────────────────────────────────────────────
    fun startRecording() {
        stopRecording()

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, ENCODING)
        if (minBuf <= 0) {
            Log.e(TAG, "getMinBufferSize failed: $minBuf")
            return
        }

        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            ENCODING,
            minBuf * 4
        )

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to init — check RECORD_AUDIO permission")
            rec.release()
            return
        }

        recorder = rec
        recording = true
        Log.d(TAG, "PTT recording started, minBuf=$minBuf")
        rec.startRecording()

        recordJob = scope.launch(Dispatchers.IO) {
            val chunks = mutableListOf<ByteArray>()
            val buf = ByteArray(minBuf)
            while (isActive && recording) {
                val read = rec.read(buf, 0, buf.size)
                if (read > 0) chunks.add(buf.copyOf(read))
            }

            val total = chunks.sumOf { it.size }
            Log.d(TAG, "PTT recording done: $total bytes in ${chunks.size} chunks")

            if (total > 0) {
                val pcm = ByteArray(total).also { out ->
                    var pos = 0
                    chunks.forEach { c -> c.copyInto(out, pos); pos += c.size }
                }
                val b64 = Base64.encodeToString(pcm, Base64.NO_WRAP)
                Log.d(TAG, "PTT sending ${pcm.size} bytes as ${b64.length} b64 chars")
                try {
                    client.postgrest[TABLE].insert(buildJsonObject {
                        put("audio_b64", b64)
                        put("sender", "device")
                    })
                    Log.d(TAG, "PTT sent OK")
                } catch (e: Exception) {
                    Log.e(TAG, "PTT send error", e)
                }
            } else {
                Log.w(TAG, "PTT: no audio captured")
            }
        }
    }

    // ── Stop recording ────────────────────────────────────────────────────────
    fun stopRecording() {
        recording = false
        recorder?.let { it.stop(); it.release() }
        recorder = null
    }

    // ── Play raw PCM through speaker ──────────────────────────────────────────
    private suspend fun playPcm(pcm: ByteArray) = withContext(Dispatchers.IO) {
        if (pcm.isEmpty()) { Log.w(TAG, "PTT: empty PCM"); return@withContext }
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val wasSpeakerOn = audioManager.isSpeakerphoneOn
        try {
            // Force speaker so audio comes out loud regardless of phone state
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = true
            val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, ENCODING)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(ENCODING)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf, pcm.size))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            track.write(pcm, 0, pcm.size)
            track.setVolume(AudioTrack.getMaxVolume())
            track.play()

            val durationMs = (pcm.size.toLong() * 1000L) / (SAMPLE_RATE * 2)
            Log.d(TAG, "PTT playing ${pcm.size} bytes, ~${durationMs}ms")
            delay(durationMs + 400)

            track.stop()
            track.release()
            Log.d(TAG, "PTT playback done")
        } catch (e: Exception) {
            Log.e(TAG, "PTT playback error", e)
        } finally {
            // Restore audio state
            audioManager.isSpeakerphoneOn = wasSpeakerOn
            audioManager.mode = AudioManager.MODE_NORMAL
        }
    }

    fun destroy() {
        stopRecording()
        recordJob?.cancel()
        listenJob?.cancel()
        scope.launch { try { listenChannel?.unsubscribe() } catch (_: Exception) {} }
    }
}
