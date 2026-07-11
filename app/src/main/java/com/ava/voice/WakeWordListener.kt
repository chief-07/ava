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
 * WakeWordListener uses Android's native SpeechRecognizer to listen for "AVA" offline.
 * Requires no external libraries or downloads.
 * Implements always-on restart loops, ambient silent background listening,
 * wake acknowledgement tones, and cooperative mic back-off logic.
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

    private fun muteSystemBeep() {
        try {
            audioManager.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_MUTE, 0)
        } catch (e: Exception) {
            // Ignore security or permission restrictions on some OS versions
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
                // Play short notification chime on STREAM_MUSIC
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

        destroyRecognizer() // Clean up any active state

        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(SpeechListener())
                
                // Mute beep, trigger start, and unmute after beep duration has passed
                muteSystemBeep()
                startListening(recognizerIntent)
                mainScope.launch {
                    delay(350)
                    unmuteSystemBeep()
                }
            }
            AppLogger.d(TAG, "Native SpeechRecognizer started listening (ambient)")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to start native SpeechRecognizer: ${e.message}")
            unmuteSystemBeep()
            triggerBackoff()
        }
    }

    private fun destroyRecognizer() {
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            // Ignore
        } finally {
            speechRecognizer = null
        }
    }

    private fun triggerBackoff() {
        if (isBackingOff || !isListening) return
        isBackingOff = true
        AppLogger.w(TAG, "Microphone occupied. Entering 3s cooperative back-off...")
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
        
        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            // Unmute system beep on error just in case
            unmuteSystemBeep()

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
            
            // Log as debug unless it's a real permission/mic lock issue
            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                AppLogger.w(TAG, "SpeechRecognizer warning: $errorMsg")
                triggerBackoff()
            } else {
                AppLogger.d(TAG, "SpeechRecognizer idle error: $errorMsg")
                // Restart ambient loop immediately
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
            // Restart ambient loop
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
                    AppLogger.i(TAG, "🔥 Native wake word detected: \"$text\"")
                    
                    // Stop background listener to let the action loop or input overlay take the mic
                    stop()

                    // Play premium chime acknowledgment tone
                    playWakeTone()

                    // Parse trailing command
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
