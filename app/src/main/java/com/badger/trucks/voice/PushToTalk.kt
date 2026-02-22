package com.badger.trucks.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcast
import io.github.jan.supabase.realtime.broadcastFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val TAG         = "PushToTalk"
private const val SAMPLE_RATE = 8000
private const val ENCODING    = AudioFormat.ENCODING_PCM_16BIT
private const val EVENT       = "ptt"

class PushToTalkManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private var recorder: AudioRecord? = null
    private var recordJob: Job? = null
    private var listenJob: Job? = null
    private var channel: RealtimeChannel? = null

    var onIncoming: (() -> Unit)? = null
    var onDone: (() -> Unit)? = null

    // ── Attach to an already-subscribed channel ───────────────────────────────
    fun attach(ch: RealtimeChannel) {
        channel = ch
        listenJob?.cancel()
        listenJob = ch.broadcastFlow<JsonObject>(EVENT)
            .onEach { payload ->
                try {
                    val b64 = payload["audio"]?.jsonPrimitive?.content ?: return@onEach
                    val pcm = Base64.decode(b64, Base64.DEFAULT)
                    Log.d(TAG, "PTT received: ${pcm.size} bytes")
                    scope.launch(Dispatchers.Main) { onIncoming?.invoke() }
                    playPcm(pcm)
                    scope.launch(Dispatchers.Main) { onDone?.invoke() }
                } catch (e: Exception) {
                    Log.e(TAG, "PTT receive error", e)
                    scope.launch(Dispatchers.Main) { onDone?.invoke() }
                }
            }
            .launchIn(scope)
        Log.d(TAG, "PTT listening on channel")
    }

    // ── Start recording — streams audio into a growing buffer ─────────────────
    fun startRecording() {
        stopRecording()
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            ENCODING
        )
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            ENCODING,
            minBuf * 4
        )

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            rec.release()
            return
        }

        recorder = rec
        val chunks = mutableListOf<ByteArray>()
        rec.startRecording()
        Log.d(TAG, "PTT recording started")

        recordJob = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(minBuf)
            while (isActive && recorder != null) {
                val read = rec.read(buf, 0, buf.size)
                if (read > 0) chunks.add(buf.copyOf(read))
            }
            // Stopped — send collected audio
            val total = chunks.sumOf { it.size }
            if (total > 0) {
                val pcm = ByteArray(total)
                var pos = 0
                chunks.forEach { chunk -> chunk.copyInto(pcm, pos); pos += chunk.size }
                val b64 = Base64.encodeToString(pcm, Base64.NO_WRAP)
                Log.d(TAG, "PTT sending: ${pcm.size} bytes")
                try {
                    channel?.broadcast(EVENT, buildJsonObject { put("audio", b64) })
                        ?: Log.w(TAG, "PTT: no channel")
                } catch (e: Exception) {
                    Log.e(TAG, "PTT broadcast error", e)
                }
            }
        }
    }

    // ── Stop recording — recordJob will finish and send ───────────────────────
    fun stopRecording() {
        recorder?.let {
            it.stop()
            it.release()
        }
        recorder = null
        // recordJob sees recorder==null and exits its loop, then sends
    }

    // ── Play PCM through speaker ──────────────────────────────────────────────
    private suspend fun playPcm(pcm: ByteArray) = withContext(Dispatchers.IO) {
        try {
            val minBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                ENCODING
            )
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
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
            track.play()

            val durationMs = (pcm.size.toLong() * 1000L) / (SAMPLE_RATE * 2)
            delay(durationMs + 300)

            track.stop()
            track.release()
        } catch (e: Exception) {
            Log.e(TAG, "PTT playback error", e)
        }
    }

    fun destroy() {
        stopRecording()
        recordJob?.cancel()
        listenJob?.cancel()
    }
}
