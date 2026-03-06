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

private const val TAG            = "HotwordListener"
private const val HOTWORD        = "badger"
private const val RESTART_DELAY  = 800L
private const val RETRY_DELAY    = 1200L
// After TTS speaks, block hotword for this long so the mic doesn't pick up
// the speaker output and re-trigger immediately.
const val TTS_BLACKOUT_MS        = 2500L

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

    // ── Public API ────────────────────────────────────────────────────────────

    fun start() {
        if (destroyed) return
        active = true
        paused = false
        Log.d(TAG, "Hotword listener starting")
        scheduleNextCycle(0)
    }

    fun pause() {
        paused = true
        cycleId++          // invalidate any in-flight callbacks for the current cycle
        tearDown()
        Log.d(TAG, "Hotword paused (cycleId=$cycleId)")
    }

    fun resume() {
        if (destroyed || !active) return
        paused = false
        Log.d(TAG, "Hotword resuming")
        scheduleNextCycle(RESTART_DELAY)
    }

    fun resumeAfterTts(extraDelayMs: Long = TTS_BLACKOUT_MS) {
        if (destroyed || !active) return
        paused = false
        Log.d(TAG, "Hotword resuming after TTS (blackout=${extraDelayMs}ms)")
        scheduleNextCycle(extraDelayMs)
    }

    fun destroy() {
        destroyed = true
        active    = false
        cycleId++
        handler.removeCallbacksAndMessages(null)
        tearDown()
        Log.d(TAG, "Hotword destroyed")
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
                if (isStale()) return
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.joinToString(" ")?.lowercase() ?: return
                if (HOTWORD in partial) {
                    Log.d(TAG, "[$myCycleId] Hotword in partial: '$partial'")
                    handleDetected(myCycleId)
                }
            }

            override fun onResults(results: Bundle?) {
                if (isStale()) return   // already handled by partial, or we were paused
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.joinToString(" ")?.lowercase() ?: ""
                Log.d(TAG, "[$myCycleId] Hotword cycle result: '$text'")
                if (HOTWORD in text) {
                    handleDetected(myCycleId)
                } else {
                    scheduleNextCycle(RESTART_DELAY)
                }
            }

            override fun onError(error: Int) {
                if (isStale()) return
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        Log.d(TAG, "[$myCycleId] No speech, restarting")
                        scheduleNextCycle(RESTART_DELAY)
                    }
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                    SpeechRecognizer.ERROR_CLIENT -> {
                        Log.w(TAG, "[$myCycleId] Busy/client error $error, backing off")
                        tearDown()
                        scheduleNextCycle(RETRY_DELAY)
                    }
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        Log.e(TAG, "[$myCycleId] No mic permission — stopping")
                        active = false
                    }
                    else -> {
                        Log.w(TAG, "[$myCycleId] Error $error, restarting")
                        scheduleNextCycle(RETRY_DELAY)
                    }
                }
            }

            override fun onReadyForSpeech(params: Bundle?)    {}
            override fun onBeginningOfSpeech()                {}
            override fun onRmsChanged(rmsdB: Float)           {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech()                      {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        rec.startListening(intent)
        Log.d(TAG, "[$myCycleId] Hotword cycle started")
    }

    private fun handleDetected(myCycleId: Int) {
        if (cycleId != myCycleId) return   // stale callback
        Log.d(TAG, "[$myCycleId] Hotword DETECTED — pausing")
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
