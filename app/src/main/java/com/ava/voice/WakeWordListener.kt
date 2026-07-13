package com.ava.voice

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import com.ava.util.AppLogger
import kotlinx.coroutines.*
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import org.vosk.android.RecognitionListener
import java.io.File

/**
 * WakeWordListener uses Vosk Offline Speech Recognition to continuously
 * and silently monitor the microphone for the wake-word "AVA".
 * Utilizes low-level AudioRecord stream capture (via Vosk SpeechService)
 * with zero beeps and zero timeouts.
 */
class WakeWordListener(
    private val context: Context,
    private val onWakeWordDetected: (command: String?) -> Unit
) {
    private val TAG = "AVA:WakeWordListener"
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val modelDir = File(context.filesDir, "vosk-model")

    private var voskModel: Model? = null
    private var voskRecognizer: Recognizer? = null
    private var speechService: SpeechService? = null
    var isListening = false
        private set

    init {
        mainScope.launch(Dispatchers.IO) {
            try {
                if (modelDir.exists() && modelDir.listFiles()?.isNotEmpty() == true) {
                    AppLogger.d(TAG, "Loading Vosk Model from storage...")
                    voskModel = Model(modelDir.absolutePath)
                    voskRecognizer = Recognizer(voskModel, 16000.0f)
                    AppLogger.i(TAG, "Vosk Model loaded successfully ✅")
                } else {
                    AppLogger.w(TAG, "Vosk Model not found on storage. Download required first.")
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to initialize Vosk Model: ${e.message}")
            }
        }
    }

    /** Starts the continuous silent audio recording and recognition stream */
    fun start() {
        if (isListening) return
        isListening = true

        mainScope.launch {
            // Ensure model is loaded. If not loaded, wait/retry
            var retryCount = 0
            while (voskRecognizer == null && retryCount < 10) {
                delay(500)
                retryCount++
            }

            val recognizer = voskRecognizer
            if (recognizer == null) {
                AppLogger.e(TAG, "Cannot start listening — Vosk Recognizer is not initialized.")
                isListening = false
                return@launch
            }

            try {
                // Vosk SpeechService uses AudioRecord. There are no system beeps!
                speechService = SpeechService(recognizer, 16000.0f).apply {
                    startListening(VoskSpeechListener())
                }
                AppLogger.i(TAG, "Vosk continuous listening started silently (no beeps)")

                // Enable Hardware Audio Effects (AGC & Noise Suppression) for better sensitivity
                try {
                    val recorderField = SpeechService::class.java.getDeclaredField("recorder").apply {
                        isAccessible = true
                    }
                    val audioRecord = recorderField.get(speechService) as? android.media.AudioRecord
                    if (audioRecord != null) {
                        val sessionId = audioRecord.audioSessionId
                        if (android.media.audiofx.AutomaticGainControl.isAvailable()) {
                            val agc = android.media.audiofx.AutomaticGainControl.create(sessionId)
                            agc.enabled = true
                            AppLogger.i(TAG, "Hardware Automatic Gain Control (AGC) enabled successfully")
                        } else {
                            AppLogger.w(TAG, "Hardware Automatic Gain Control (AGC) not supported on this device")
                        }
                        if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
                            val ns = android.media.audiofx.NoiseSuppressor.create(sessionId)
                            ns.enabled = true
                            AppLogger.i(TAG, "Hardware Noise Suppressor (NS) enabled successfully")
                        } else {
                            AppLogger.w(TAG, "Hardware Noise Suppressor (NS) not supported on this device")
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Failed to apply hardware audio effects: ${e.message}")
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to start Vosk SpeechService: ${e.message}")
                isListening = false
            }
        }
    }

    /** Stops the audio recording stream and releases resources synchronously */
    fun stop() {
        isListening = false
        try {
            speechService?.stop()
            speechService?.shutdown()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error stopping SpeechService: ${e.message}")
        } finally {
            speechService = null
        }
    }

    /** Releases all resources and cancels background coroutine scope */
    fun destroy() {
        stop()
        try {
            mainScope.cancel()
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
                // Ignore
            }
        }
    }

    private fun triggerWake(command: String?) {
        stop()
        playWakeTone()
        onWakeWordDetected(command)
    }

    private inner class VoskSpeechListener : RecognitionListener {
        override fun onResult(hypothesis: String) {
            parseResult(hypothesis, isFinal = true)
        }

        override fun onPartialResult(hypothesis: String) {
            parseResult(hypothesis, isFinal = false)
        }

        override fun onFinalResult(hypothesis: String) {
            parseResult(hypothesis, isFinal = true)
        }

        override fun onError(exception: Exception) {
            AppLogger.e(TAG, "Vosk SpeechListener error: ${exception.message}")
            // Silently restart after cooldown
            mainScope.launch {
                delay(1000)
                if (isListening) {
                    stop()
                    start()
                }
            }
        }

        override fun onTimeout() {
            AppLogger.d(TAG, "Vosk SpeechListener timeout (idle reset)")
            mainScope.launch {
                if (isListening) {
                    stop()
                    start()
                }
            }
        }

        private fun parseResult(jsonText: String, isFinal: Boolean) {
            try {
                val json = JSONObject(jsonText)
                val text = if (isFinal) json.optString("text") else json.optString("partial")
                if (text.isNullOrBlank()) return

                val lowercase = text.lowercase()
                val wakeWords = listOf("ava", "alice")
                val matchedWakeWord = wakeWords.find { lowercase.contains(it) }
                if (matchedWakeWord != null) {
                    val index = lowercase.indexOf(matchedWakeWord)
                    val trailingPart = lowercase.substring(index + matchedWakeWord.length).trim()
                    val cleanCommand = trailingPart.removePrefix(",").removePrefix("and").trim()

                    AppLogger.i(TAG, "🔥 Vosk wake detected: \"$text\"")
                    
                    if (cleanCommand.length > 2) {
                        // Direct voice command trigger
                        triggerWake(cleanCommand)
                    } else if (isFinal) {
                        // Standard wake word trigger on silence pause
                        triggerWake(null)
                    }
                }
            } catch (e: Exception) {
                // Ignore json parse error
            }
        }
    }
}
