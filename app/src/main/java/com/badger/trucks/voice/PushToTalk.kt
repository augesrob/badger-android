package com.badger.trucks.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import com.badger.trucks.data.BadgerRepo
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcast
import io.github.jan.supabase.realtime.broadcastFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val TAG = "PushToTalk"
private const val SAMPLE_RATE = 8000        // 8kHz — low quality but tiny size
private const val CHANNEL_IN  = android.media.AudioFormat.CHANNEL_IN_MONO
private const val CHANNEL_OUT = android.media.AudioFormat.CHANNEL_OUT_MONO
private const val ENCODING    = AudioFormat.ENCODING_PCM_16BIT
private const val EVENT       = "ptt"

@Serializable
data class PttPayload(val audio: String) // base64 PCM

class PushToTalkManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private var recorder: AudioRecord? = null
    private var recordJob: Job? = null
    private var channel: RealtimeChannel? = null
    private var listenJob: Job? = null

    var onIncoming: (() -> Unit)? = null   // called when audio arrives → show banner
    var onDone: (() -> Unit)? = null        // called when playback finishes

    // ── Subscribe to incoming PTT broadcasts ─────────────────────────────────
    fun attach(ch: RealtimeChannel) {
        channel = ch
        listenJob?.cancel()
        listenJob = ch.broadcastFlow<JsonObject>(EVENT)
            .onEach { payload ->
                try {
                    val b64 = payload["audio"]?.jsonPrimitive?.content ?: return@onEach
                    val pcm = Base64.decode(b64, Base64.DEFAULT)
                    Log.d(TAG, "Received PTT: ${pcm.size} bytes")
                    onIncoming?.invoke()
                    playPcm(pcm)
                } catch (e: Exception) {
                    Log.e(TAG, "PTT receive error", e)
                }
            }
            .launchIn(scope)
    }

    // ── Start recording (call on button press) ────────────────────────────────
    fun startRecording() {
        stopRecording()
        val bufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
        recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE, CHANNEL_IN, ENCODING,
            bufSize * 4
        )
        recorder?.startRecording()
        Log.d(TAG, "PTT recording started")
    }

    // ── Stop recording, encode, broadcast (call on button release) ───────────
    fun stopRecording() {
        val rec = recorder ?: return
        rec.stop()
        recorder = null

        recordJob = scope.launch(Dispatchers.IO) {
            try {
                // Read all buffered audio
                val bufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
                val buf = ByteArray(bufSize * 20) // max ~2.5 seconds at 8kHz 16-bit mono
                val read = rec.read(buf, 0, buf.size)
                rec.release()

                if (read <= 0) return@launch

                val pcm = buf.copyOf(read)
                val b64 = Base64.encodeToString(pcm, Base64.DEFAULT)
                Log.d(TAG, "PTT sending: ${pcm.size} bytes (~${pcm.size / (SAMPLE_RATE * 2)} sec)")

                // Broadcast to all connected clients
                channel?.broadcast(EVENT, buildPayload(b64))
                    ?: Log.w(TAG, "No channel to broadcast on")
            } catch (e: Exception) {
                Log.e(TAG, "PTT send error", e)
                rec.release()
            }
        }
    }

    // ── Play raw PCM bytes through speaker ────────────────────────────────────
    private suspend fun playPcm(pcm: ByteArray) = withContext(Dispatchers.IO) {
        try {
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
                        .setChannelMask(CHANNEL_OUT)
                        .build()
                )
                .setBufferSizeInBytes(pcm.size)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            track.write(pcm, 0, pcm.size)
            track.play()

            // Wait for playback to finish
            val durationMs = (pcm.size.toLong() * 1000L) / (SAMPLE_RATE * 2)
            kotlinx.coroutines.delay(durationMs + 200)

            track.stop()
            track.release()
            onDone?.invoke()
        } catch (e: Exception) {
            Log.e(TAG, "PTT playback error", e)
            onDone?.invoke()
        }
    }

    fun destroy() {
        recorder?.release()
        recorder = null
        recordJob?.cancel()
        listenJob?.cancel()
    }

    private fun buildPayload(b64: String): JsonObject {
        return kotlinx.serialization.json.buildJsonObject {
            put("audio", kotlinx.serialization.json.JsonPrimitive(b64))
        }
    }
}
