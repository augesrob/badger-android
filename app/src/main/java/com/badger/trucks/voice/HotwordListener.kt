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
private const val RESTART_DELAY  = 800L   // ms between hotword cycles
private const val RETRY_DELAY    = 1200L  // ms after an error before restarting

/**
 * Always-on hotword listener that runs inside BadgerService.
 *
 * Continuously listens with SpeechRecognizer.  When it hears "badger"
 * (anywhere in the result) it:
 *   1. Stops itself temporarily
 *   2. Fires onHotwordDetected — service plays a chime and shows the
 *      listening banner in the UI via StateFlow
 *   3. Caller invokes resume() after the command recognizer finishes,
 *      which restarts the hotword loop
 *
 * Must be created and used on the main thread (SpeechRecognizer requirement).
 */
class HotwordListener(private val context: Context) {

    var onHotwordDetected: (() -> Unit)? = null

    private val handler  = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    @Volatile private var active   = false   // currently running the hotword loop
    @Volatile private var paused   = false   // paused while a command is processing
    @Volatile private var destroyed = false

    // ── Public API ────────────────────────────────────────────────────────────

    /** Start the always-on hotword loop. Call from main thread. */
    fun start() {
        if (destroyed) return
        active = true
        paused = false
        Log.d(TAG, "Hotword listener starting")
        scheduleNextCycle(0)
    }

    /**
     * Pause hotword detection while a voice command is being processed.
     * Call resume() when the command is done.
     */
    fun pause() {
        paused = true
        tearDown()
        Log.d(TAG, "Hotword paused")
    }

    /** Resume after command processing finished. */
    fun resume() {
        if (destroyed || !active) return
        paused = false
        Log.d(TAG, "Hotword resuming")
        scheduleNextCycle(RESTART_DELAY)
    }

    /** Permanently stop. */
    fun destroy() {
        destroyed = true
        active    = false
        handler.removeCallbacksAndMessages(null)
        tearDown()
        Log.d(TAG, "Hotword destroyed")
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun scheduleNextCycle(delayMs: Long) {
        handler.postDelayed({
            if (!destroyed && !paused && active) startCycle()
        }, delayMs)
    }

    private fun startCycle() {
        if (destroyed || paused) return
        tearDown()

        val rec = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = rec

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Keep listening longer so the hotword has time to be spoken
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
        }

        rec.setRecognitionListener(object : RecognitionListener {

            override fun onPartialResults(partialResults: Bundle?) {
                // Check partial results so detection is instant — don't wait for final
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.joinToString(" ")
                    ?.lowercase() ?: return
                if (HOTWORD in partial) {
                    Log.d(TAG, "Hotword detected in partial: '$partial'")
                    handleDetected()
                }
            }

            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.joinToString(" ")
                    ?.lowercase() ?: ""
                Log.d(TAG, "Hotword cycle result: '$text'")
                if (HOTWORD in text) {
                    handleDetected()
                } else {
                    // Didn't hear hotword — restart cycle
                    scheduleNextCycle(RESTART_DELAY)
                }
            }

            override fun onError(error: Int) {
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        // Normal silence — just restart
                        Log.d(TAG, "Hotword: no speech, restarting")
                        scheduleNextCycle(RESTART_DELAY)
                    }
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                    SpeechRecognizer.ERROR_CLIENT -> {
                        // Busy / OS not ready — back off longer
                        Log.w(TAG, "Hotword: busy/client error $error, backing off")
                        tearDown()
                        scheduleNextCycle(RETRY_DELAY)
                    }
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        Log.e(TAG, "Hotword: no mic permission — stopping")
                        active = false
                    }
                    else -> {
                        Log.w(TAG, "Hotword: error $error, restarting")
                        scheduleNextCycle(RETRY_DELAY)
                    }
                }
            }

            override fun onReadyForSpeech(params: Bundle?)  {}
            override fun onBeginningOfSpeech()              {}
            override fun onRmsChanged(rmsdB: Float)         {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech()                    {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        rec.startListening(intent)
        Log.d(TAG, "Hotword cycle started")
    }

    private fun handleDetected() {
        if (paused) return   // already triggered, ignore double-fire from partial+final
        pause()              // stop listening while command runs
        onHotwordDetected?.invoke()
    }

    private fun tearDown() {
        try { recognizer?.stopListening() } catch (_: Exception) {}
        try { recognizer?.cancel()        } catch (_: Exception) {}
        try { recognizer?.destroy()       } catch (_: Exception) {}
        recognizer = null
    }
}
