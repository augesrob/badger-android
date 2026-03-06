package com.badger.trucks.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.badger.trucks.util.RemoteLogger

private const val TAG                = "HotwordListener"
private const val HOTWORD            = "badger"
private const val RESTART_DELAY_MS   = 1200L   // normal restart after clean end
private const val RETRY_DELAY_MS     = 3000L   // retry after error (give OS time to release)
private const val DESTROY_SETTLE_MS  = 500L    // wait after destroy() before re-creating
// After TTS speaks, block hotword for this long so the mic doesn't pick up
// the speaker output and re-trigger immediately.
const val TTS_BLACKOUT_MS            = 3500L
// Startup delay — gives the TTS welcome announcement time to finish before
// we open the mic.
private const val STARTUP_DELAY_MS   = 6000L

class HotwordListener(private val context: Context) {

    var onHotwordDetected: (() -> Unit)? = null

    private val handler  = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    // All state touched only on main thread (SpeechRecognizer requirement)
    private var active    = false
    private var paused    = false
    private var destroyed = false
    private var cycleId   = 0
    private var detectedInCycle = false

    // ── Public API ────────────────────────────────────────────────────────────

    fun start() {
        if (destroyed) return
        active = true
        paused = false
        RemoteLogger.i(TAG, "Hotword listener starting (startup delay ${STARTUP_DELAY_MS}ms)")
        scheduleNextCycle(STARTUP_DELAY_MS)
    }

    fun pause() {
        paused = true
        cycleId++
        detectedInCycle = false
        tearDown(settle = false)
        RemoteLogger.d(TAG, "Hotword paused (cycleId=$cycleId)")
    }

    fun resume() {
        if (destroyed || !active) return
        paused = false
        RemoteLogger.d(TAG, "Hotword resuming")
        scheduleNextCycle(RESTART_DELAY_MS)
    }

    fun resumeAfterTts(extraDelayMs: Long = TTS_BLACKOUT_MS) {
        if (destroyed || !active) return
        paused = false
        RemoteLogger.d(TAG, "Hotword resuming after TTS (blackout=${extraDelayMs}ms)")
        scheduleNextCycle(extraDelayMs)
    }

    fun destroy() {
        destroyed = true
        active    = false
        cycleId++
        handler.removeCallbacksAndMessages(null)
        tearDown(settle = false)
        RemoteLogger.d(TAG, "Hotword destroyed")
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun scheduleNextCycle(delayMs: Long) {
        if (destroyed || paused || !active) return
        // Always add settle time on top so the OS speech service is fully released
        val totalDelay = delayMs + DESTROY_SETTLE_MS
        handler.postDelayed({
            if (!destroyed && !paused && active) startCycle()
        }, totalDelay)
    }

    private fun startCycle() {
        if (destroyed || paused || !active) return
        tearDown(settle = false)
        detectedInCycle = false
        val myCycleId = ++cycleId

        val rec = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = rec

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
        }

        rec.setRecognitionListener(object : RecognitionListener {

            private fun isStale() = cycleId != myCycleId

            override fun onPartialResults(partialResults: Bundle?) {
                if (isStale() || detectedInCycle) return
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.joinToString(" ")?.lowercase() ?: return
                if (HOTWORD in partial) {
                    RemoteLogger.i(TAG, "[$myCycleId] Hotword in partial: '$partial'")
                    handleDetected(myCycleId)
                }
            }

            override fun onResults(results: Bundle?) {
                if (isStale() || detectedInCycle) return
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.joinToString(" ")?.lowercase() ?: ""
                RemoteLogger.d(TAG, "[$myCycleId] Result: '$text'")
                if (HOTWORD in text) {
                    handleDetected(myCycleId)
                } else {
                    scheduleNextCycle(RESTART_DELAY_MS)
                }
            }

            override fun onError(error: Int) {
                if (isStale()) return
                val errorName = errorName(error)
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        RemoteLogger.d(TAG, "[$myCycleId] $errorName — restarting")
                        scheduleNextCycle(RESTART_DELAY_MS)
                    }
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                        // Something else is using the mic — wait longer before retrying
                        RemoteLogger.w(TAG, "[$myCycleId] $errorName — mic busy, backing off ${RETRY_DELAY_MS}ms")
                        tearDown(settle = false)
                        scheduleNextCycle(RETRY_DELAY_MS)
                    }
                    SpeechRecognizer.ERROR_CLIENT, 11 -> {
                        // OS speech service / Google server not ready — destroy and wait longer
                        RemoteLogger.w(TAG, "[$myCycleId] $errorName — speech service busy, backing off ${RETRY_DELAY_MS}ms")
                        tearDown(settle = false)
                        scheduleNextCycle(RETRY_DELAY_MS)
                    }
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        RemoteLogger.e(TAG, "[$myCycleId] No mic permission — stopping hotword")
                        active = false
                    }
                    else -> {
                        RemoteLogger.w(TAG, "[$myCycleId] $errorName — restarting after delay")
                        scheduleNextCycle(RETRY_DELAY_MS)
                    }
                }
            }

            override fun onReadyForSpeech(params: Bundle?)    { RemoteLogger.d(TAG, "[$myCycleId] Ready for speech") }
            override fun onBeginningOfSpeech()                { RemoteLogger.d(TAG, "[$myCycleId] Speech began") }
            override fun onRmsChanged(rmsdB: Float)           {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech()                      { RemoteLogger.d(TAG, "[$myCycleId] End of speech") }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        rec.startListening(intent)
        RemoteLogger.i(TAG, "[$myCycleId] Hotword cycle started")
    }

    private fun handleDetected(myCycleId: Int) {
        if (cycleId != myCycleId || detectedInCycle) return
        detectedInCycle = true
        RemoteLogger.i(TAG, "[$myCycleId] ✅ Hotword DETECTED — pausing")
        pause()
        onHotwordDetected?.invoke()
    }

    private fun tearDown(settle: Boolean) {
        try { recognizer?.stopListening() } catch (_: Exception) {}
        try { recognizer?.cancel()        } catch (_: Exception) {}
        try { recognizer?.destroy()       } catch (_: Exception) {}
        recognizer = null
    }

    private fun errorName(error: Int) = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH          -> "NO_MATCH(2)"
        SpeechRecognizer.ERROR_CLIENT            -> "CLIENT(5)"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY   -> "RECOGNIZER_BUSY(8)"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT    -> "SPEECH_TIMEOUT(6)"
        SpeechRecognizer.ERROR_AUDIO             -> "AUDIO(3)"
        SpeechRecognizer.ERROR_NETWORK           -> "NETWORK(1)"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT   -> "NETWORK_TIMEOUT(7)"
        SpeechRecognizer.ERROR_SERVER            -> "SERVER(4)"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "NO_PERMISSION(9)"
        11                                       -> "SERVER_11(GoogleSpeechBusy)"
        else -> "UNKNOWN($error)"
    }
}
