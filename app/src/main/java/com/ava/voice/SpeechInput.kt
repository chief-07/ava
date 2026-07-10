package com.ava.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.ava.util.AppLogger
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private const val TAG = "AVA:SpeechInput"

/**
 * SpeechInput wraps Android's built-in SpeechRecognizer as a
 * suspending function — call listenOnce() and await the result.
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
                AppLogger.d(TAG, "Speech recognized: \"$text\"")
                if (continuation.isActive) continuation.resume(text)
            }

            override fun onError(error: Int) {
                // Map common error codes to readable messages
                val errorMsg = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                    SpeechRecognizer.ERROR_CLIENT -> "Client-side error"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permission denied (Microphone)"
                    SpeechRecognizer.ERROR_NETWORK -> "Network error"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                    SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer is busy"
                    SpeechRecognizer.ERROR_SERVER -> "Server-side error"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input detected"
                    else -> "Unknown speech recognizer error ($error)"
                }
                AppLogger.w(TAG, "STT error: $errorMsg")
                if (continuation.isActive) continuation.resume(null)
            }

            // Required overrides
            override fun onReadyForSpeech(params: Bundle?) {
                AppLogger.d(TAG, "Microphone active. Speak now...")
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                AppLogger.d(TAG, "Processing speech...")
            }
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
