package com.ava.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.ava.util.AppLogger
import kotlinx.coroutines.*

/**
 * WakeWordListener uses Android's native SpeechRecognizer to listen for "AVA" offline.
 * Requires no external libraries or downloads.
 * Implements always-on restart loops and cooperative mic back-off logic.
 */
class WakeWordListener(
    private val context: Context,
    private val onWakeWordDetected: (command: String?) -> Unit
) {
    private val TAG = "AVA:WakeWordListener"
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    private var speechRecognizer: SpeechRecognizer? = null
    private var recognizerIntent: Intent? = null
    private var isListening = false
    private var isBackingOff = false

    init {
        mainScope.launch {
            setupIntent()
        }
    }

    private fun setupIntent() {
        recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
    }

    /** Starts the native speech recognition loop */
    fun start() {
        if (isListening) return
        isListening = true
        isBackingOff = false
        mainScope.launch {
            startListeningInternal()
        }
    }

    /** Stops listening and releases the speech recognizer resource */
    fun stop() {
        isListening = false
        mainScope.launch {
            destroyRecognizer()
        }
    }

    private fun startListeningInternal() {
        if (!isListening || isBackingOff) return

        destroyRecognizer() // Ensure previous instance is fully released

        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(SpeechListener())
                startListening(recognizerIntent)
            }
            AppLogger.d(TAG, "Native SpeechRecognizer started listening")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to start native SpeechRecognizer: ${e.message}")
            triggerBackoff()
        }
    }

    private fun destroyRecognizer() {
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            // Ignore clean up errors
        } finally {
            speechRecognizer = null
        }
    }

    private fun triggerBackoff() {
        if (isBackingOff || !isListening) return
        isBackingOff = true
        AppLogger.w(TAG, "Microphone occupied or error encountered. Entering 3s back-off...")
        destroyRecognizer()
        mainScope.launch {
            delay(3000)
            if (isListening) {
                isBackingOff = false
                AppLogger.d(TAG, "Back-off ended. Re-starting listener...")
                startListeningInternal()
            }
        }
    }

    private inner class SpeechListener : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        
        override fun onEndOfSpeech() {
            // We do not restart here; we wait for onResults or onError to guarantee clean cycle
        }

        override fun onError(error: Int) {
            val errorMsg = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                SpeechRecognizer.ERROR_CLIENT -> "Client side error"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permissions missing"
                SpeechRecognizer.ERROR_NETWORK -> "Network error"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                SpeechRecognizer.ERROR_NO_MATCH -> "No speech match"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy (mic occupied)"
                SpeechRecognizer.ERROR_SERVER -> "Server error"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech input timeout"
                else -> "Unknown error ($error)"
            }
            AppLogger.d(TAG, "SpeechRecognizer error: $errorMsg")

            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                triggerBackoff()
            } else {
                // For timeouts/no-match, restart immediately to maintain always-on listening
                mainScope.launch {
                    delay(300) // Small delay to let the system release resources
                    if (isListening && !isBackingOff) {
                        startListeningInternal()
                    }
                }
            }
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (matches != null) {
                processResults(matches)
            }
            // Restart listening immediately
            mainScope.launch {
                if (isListening && !isBackingOff) {
                    startListeningInternal()
                }
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (matches != null) {
                processResults(matches)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}

        private fun processResults(matches: ArrayList<String>) {
            for (text in matches) {
                val lowercase = text.lowercase()
                val wakeWord = "ava"
                if (lowercase.contains(wakeWord)) {
                    AppLogger.i(TAG, "🔥 Native wake word \"$wakeWord\" detected in: \"$text\"")
                    
                    // Stop listening immediately to prevent self-triggering
                    stop()

                    // Extract trailing command if present
                    val index = lowercase.indexOf(wakeWord)
                    val trailingPart = lowercase.substring(index + wakeWord.length).trim()
                    val cleanCommand = trailingPart.removePrefix(",").removePrefix("and").trim()

                    if (cleanCommand.length > 2) {
                        onWakeWordDetected(cleanCommand)
                    } else {
                        onWakeWordDetected(null)
                    }
                    break
                }
            }
        }
    }
}
