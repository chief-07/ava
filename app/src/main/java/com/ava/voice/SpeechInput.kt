package com.ava.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private const val TAG = "AVA:SpeechInput"

/**
 * SpeechInput wraps Android's built-in SpeechRecognizer as a
 * suspending function — call listenOnce() and await the result.
 *
 * Uses the device's built-in STT engine (Google or OEM).
 * No API key required — completely free.
 */
class SpeechInput(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null

    /**
     * Start listening and return the recognized text, or null on failure.
     * Suspends until speech is detected and recognized.
     */
    suspend fun listenOnce(): String? = suspendCancellableCoroutine { continuation ->
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }

        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val matches = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull()
                Log.d(TAG, "Recognized: $text")
                if (continuation.isActive) continuation.resume(text)
            }

            override fun onError(error: Int) {
                Log.w(TAG, "STT error: $error")
                if (continuation.isActive) continuation.resume(null)
            }

            // Required overrides — we don't need these for Phase 1
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        recognizer?.startListening(intent)

        continuation.invokeOnCancellation {
            recognizer?.destroy()
            recognizer = null
        }
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
    }
}
