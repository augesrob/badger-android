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

private const val TAG            = "HotwordListener"
private const val HOTWORD        = "badger"
private const val RESTART_DELAY  = 800L
private const val RETRY_DELAY    = 1500L
// After TTS speaks, block hotword for this long so the mic doesn't pick up
// the speaker output and re-trigger immediately.
const val TTS_BLACKOUT_MS        = 3500L
// Startup delay — gives the TTS welcome announcement time to finish before
// we open the mic. Without this the "Badger live monitoring active" TTS
// audio feeds straight into the first recognition cycle.
private const val STARTUP_DELAY_MS = 5000L

class HotwordListener(private val context: Context) {

    var onHotwordDetected: (() -> Unit)? = null

    private val handler  = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    // All state touched only on main thread (SpeechRecognizer requirement)
    private var active    = false
    private var paused    = false
    private var destroyed = false
    // Prevents partial+final both firing in the same recognition cycle.
    // Also used to discard any stale callbacks that arrive after pause().
    private var cycleId   = 0   // incremented each startCycle(); callbacks check their copy

    // Track whether a detection was already dispatched in this cycle so
    // partial + final results don't both call onHotwordDetected.
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
        cycleId++          // invalidate any in-flight callbacks for the current cycle
        detectedInCycle = false
        tearDown()
        RemoteLogger.d(TAG, "Hotword paused (cycleId=$cycleId)")
    }

    fun resume() {
        if (destroyed || !active) return
        paused = false
        RemoteLogger.d(TAG, "Hotword resuming (short delay)")
        scheduleNextCycle(RESTART_DELAY)
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
        tearDown()
        RemoteLogger.d(TAG, "Hotword destroyed")
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun scheduleNextCycle(delayMs: Long) {
        if (destroyed || paused || !active) return
        handler.postDelayed({
            if (!destroyed && !paused && active) startCycle()
        }, delayMs)
    }

    private fun startCycle() {
        if (destroyed || paused || !active) return
        tearDown()
        detectedInCycle = false
        val myCycleId = ++cycleId   // capture this cycle's ID for callback validation

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

            /** Guard: ignore callbacks that belong to a stale cycle (after pause/destroy). */
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
                RemoteLogger.d(TAG, "[$myCycleId] Hotword cycle result: '$text'")
                if (HOTWORD in text) {
                    handleDetected(myCycleId)
                } else {
                    scheduleNextCycle(RESTART_DELAY)
                }
            }

            override fun onError(error: Int) {
                if (isStale()) return
                val errorName = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH          -> "NO_MATCH"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT    -> "SPEECH_TIMEOUT"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY   -> "RECOGNIZER_BUSY"
                    SpeechRecognizer.ERROR_CLIENT            -> "CLIENT"
                    SpeechRecognizer.ERROR_NETWORK           -> "NETWORK"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT   -> "NETWORK_TIMEOUT"
                    SpeechRecognizer.ERROR_AUDIO             -> "AUDIO"
                    SpeechRecognizer.ERROR_SERVER            -> "SERVER"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "NO_PERMISSION"
                    else -> "UNKNOWN($error)"
                }
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        RemoteLogger.d(TAG, "[$myCycleId] $errorName — restarting")
                        scheduleNextCycle(RESTART_DELAY)
                    }
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                    SpeechRecognizer.ERROR_CLIENT -> {
                        RemoteLogger.w(TAG, "[$myCycleId] $errorName — backing off")
                        tearDown()
                        scheduleNextCycle(RETRY_DELAY)
                    }
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        RemoteLogger.e(TAG, "[$myCycleId] No mic permission — stopping")
                        active = false
                    }
                    else -> {
                        RemoteLogger.w(TAG, "[$myCycleId] Error $errorName — restarting")
                        scheduleNextCycle(RETRY_DELAY)
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
        RemoteLogger.i(TAG, "[$myCycleId] Hotword DETECTED — pausing listener")
        pause()   // increments cycleId, tears down recognizer, sets paused=true
        onHotwordDetected?.invoke()
    }

    private fun tearDown() {
        try { recognizer?.stopListening() } catch (_: Exception) {}
        try { recognizer?.cancel()        } catch (_: Exception) {}
        try { recognizer?.destroy()       } catch (_: Exception) {}
        recognizer = null
    }
}
