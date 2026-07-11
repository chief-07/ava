package com.ava.voice

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.ava.util.AppLogger
import kotlinx.coroutines.*

/**
 * WakeWordListener uses Android's native SpeechRecognizer in an infinite-session
 * continuous streaming mode to listen for "AVA" in real-time.
 * Uses partial results debouncing to support instant triggers and direct command redirection.
 */
class WakeWordListener(
    private val context: Context,
    private val onWakeWordDetected: (command: String?) -> Unit
) {
    private val TAG = "AVA:WakeWordListener"
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    
    private var speechRecognizer: SpeechRecognizer? = null
    private var recognizerIntent: Intent? = null
    private var isListening = false
    private var isBackingOff = false
    private var triggerJob: Job? = null

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
            
            // Set extremely high complete silence thresholds to maintain continuous mic streaming
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 3600000L) // 1 hour
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3600000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3600000L)
        }
    }

    /** Starts the native continuous streaming recognition loop */
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
        triggerJob?.cancel()
        mainScope.launch {
            destroyRecognizer()
        }
    }

    private fun muteSystemBeep() {
        try {
            audioManager.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_MUTE, 0)
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun unmuteSystemBeep() {
        try {
            audioManager.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0)
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun playWakeTone() {
        mainScope.launch(Dispatchers.IO) {
            try {
                val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 85)
                toneGen.startTone(ToneGenerator.TONE_PROP_ACK, 180)
                delay(200)
                toneGen.release()
            } catch (e: Exception) {
                // Ignore fallback
            }
        }
    }

    private fun startListeningInternal() {
        if (!isListening || isBackingOff) return

        destroyRecognizer() // Ensure previous resources are released

        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(SpeechListener())
                
                // Mute beep only once during the initial start
                muteSystemBeep()
                startListening(recognizerIntent)
                mainScope.launch {
                    delay(400)
                    unmuteSystemBeep()
                }
            }
            AppLogger.d(TAG, "Continuous speech stream started")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to start speech stream: ${e.message}")
            unmuteSystemBeep()
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
        AppLogger.w(TAG, "Microphone in use. Backing off for 3s...")
        destroyRecognizer()
        mainScope.launch {
            delay(3000)
            if (isListening) {
                isBackingOff = false
                startListeningInternal()
            }
        }
    }

    private fun triggerWake(command: String?) {
        // Stop background stream to allow active dialog or execution to capture the audio channel
        stop()
        playWakeTone()
        onWakeWordDetected(command)
    }

    private inner class SpeechListener : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            unmuteSystemBeep()

            val errorMsg = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                SpeechRecognizer.ERROR_CLIENT -> "Client side error"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permissions missing"
                SpeechRecognizer.ERROR_NETWORK -> "Network error"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                SpeechRecognizer.ERROR_NO_MATCH -> "No speech match"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                SpeechRecognizer.ERROR_SERVER -> "Server error"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
                else -> "Unknown error ($error)"
            }

            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                AppLogger.w(TAG, "Speech stream paused: $errorMsg")
                triggerBackoff()
            } else {
                // If it timed out after a long period of silence, silently restart the stream
                mainScope.launch {
                    delay(300)
                    if (isListening && !isBackingOff) {
                        startListeningInternal()
                    }
                }
            }
        }

        override fun onResults(results: Bundle?) {
            unmuteSystemBeep()
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (matches != null) {
                processResults(matches)
            }
            // Continuous session fallback restart
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
                    // Cancel any active scheduled trigger
                    triggerJob?.cancel()

                    val index = lowercase.indexOf(wakeWord)
                    val trailingPart = lowercase.substring(index + wakeWord.length).trim()
                    val cleanCommand = trailingPart.removePrefix(",").removePrefix("and").trim()

                    if (cleanCommand.length > 2) {
                        // Debounce by 650ms to verify if the user is still speaking a command
                        triggerJob = mainScope.launch {
                            delay(650)
                            triggerWake(cleanCommand)
                        }
                    } else {
                        // Trigger immediately on wake word pause
                        triggerJob = mainScope.launch {
                            delay(350)
                            triggerWake(null)
                        }
                    }
                    break
                }
            }
        }
    }
}
