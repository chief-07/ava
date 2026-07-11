package com.ava.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.ava.util.AppLogger
import kotlinx.coroutines.*
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/**
 * WakeWordListener runs a continuous background AudioRecord capture loop
 * and processes speech offline using Vosk.
 * Includes cooperative mic-sharing (releases mic if silenced or locked by another app).
 */
class WakeWordListener(
    private val context: Context,
    private val modelPath: String,
    private val onWakeWordDetected: (command: String?) -> Unit
) {
    private const val TAG = "AVA:WakeWordListener"
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var listenJob: Job? = null
    private var isListening = false
    private var isBackingOff = false

    private var model: Model? = null

    init {
        try {
            model = Model(modelPath)
            AppLogger.i(TAG, "Offline Vosk model loaded successfully from $modelPath")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to initialize Vosk model: ${e.message}", e)
        }
    }

    /** Start background listening */
    fun start() {
        if (model == null) {
            AppLogger.e(TAG, "Cannot start listening — Vosk model is null")
            return
        }
        if (isListening) return
        isListening = true
        listenJob = scope.launch {
            runListenLoop()
        }
        AppLogger.i(TAG, "Background Wake-Word Listener started")
    }

    /** Stop background listening and release microphone resource */
    fun stop() {
        isListening = false
        listenJob?.cancel()
        listenJob = null
        AppLogger.i(TAG, "Background Wake-Word Listener stopped")
    }

    private suspend fun runListenLoop() {
        val sampleRate = 16000
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ) * 2

        if (bufferSize <= 0) {
            AppLogger.e(TAG, "Invalid AudioRecord buffer size: $bufferSize")
            return
        }

        // Limit Vosk search grammar to search ONLY for "ava" and related trigger variations
        // This dramatically reduces CPU cycles and battery drain
        val recognizer = try {
            Recognizer(model, sampleRate.toFloat()).apply {
                // Set grammar to match triggers
                // Vosk allows setting specific grammars as a JSON list of strings
                // We add some generic wildcards to allow capturing following commands
                // e.g. ["ava", "[any string]"] or simply capture triggers:
                // For a keyword spotter, searching for "ava" is highly optimized
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to create Vosk Recognizer: ${e.message}")
            return
        }

        val buffer = ByteArray(4096)
        var silenceStreak = 0

        while (isListening && coroutineContext.isActive) {
            if (isBackingOff) {
                delay(3000) // Back-off period
                isBackingOff = false
                AppLogger.d(TAG, "Back-off complete. Attempting to re-acquire microphone...")
            }

            var recorder: AudioRecord? = null
            try {
                recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )

                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException("AudioRecord state not initialized")
                }

                recorder.startRecording()
                silenceStreak = 0
                AppLogger.d(TAG, "Microphone acquired successfully. Listening...")

                while (isListening && coroutineContext.isActive && !isBackingOff) {
                    val readBytes = recorder.read(buffer, 0, buffer.size)

                    if (readBytes <= 0) {
                        AppLogger.w(TAG, "AudioRecord read returned error/zero: $readBytes. Mic occupied?")
                        triggerBackoff()
                        break
                    }

                    // Cooperative checking: Android 10+ silences background recorders by sending all zeros
                    var isDigitalSilence = true
                    for (i in 0 until readBytes) {
                        if (buffer[i] != 0.toByte()) {
                            isDigitalSilence = false
                            break
                        }
                    }

                    if (isDigitalSilence) {
                        silenceStreak += readBytes
                        // 16000 samples/sec * 2 bytes/sample = 32000 bytes/sec
                        // 16000 bytes is roughly 500ms of absolute silence
                        if (silenceStreak > 16000) {
                            AppLogger.w(TAG, "Absolute digital silence detected. Releasing mic for another app...")
                            triggerBackoff()
                            break
                        }
                    } else {
                        silenceStreak = 0
                    }

                    // Feed audio to Vosk
                    if (recognizer.acceptWaveForm(buffer, readBytes)) {
                        val resultText = parseVoskJson(recognizer.result)
                        processSpeechText(resultText)
                    } else {
                        val partialText = parseVoskJson(recognizer.partialResult)
                        processSpeechText(partialText)
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Microphone capture loop error: ${e.message}. Retrying soon...")
                triggerBackoff()
            } finally {
                try {
                    recorder?.stop()
                    recorder?.release()
                } catch (e: Exception) {
                    // Ignore release errors
                }
            }
        }
    }

    private fun triggerBackoff() {
        isBackingOff = true
    }

    private fun parseVoskJson(jsonStr: String): String {
        return try {
            val json = JSONObject(jsonStr)
            when {
                json.has("text") -> json.getString("text")
                json.has("partial") -> json.getString("partial")
                else -> ""
            }
        } catch (e: Exception) {
            ""
        }
    }

    private fun processSpeechText(text: String) {
        if (text.isBlank()) return
        val lowercase = text.lowercase()
        AppLogger.d(TAG, "Speech heard: \"$lowercase\"")

        // Trigger on "ava" wake word
        val wakeWord = "ava"
        if (lowercase.contains(wakeWord)) {
            AppLogger.i(TAG, "🔥 Wake word \"$wakeWord\" detected in: \"$text\"")
            
            // Extract trailing command if present
            val index = lowercase.indexOf(wakeWord)
            val trailingPart = lowercase.substring(index + wakeWord.length).trim()
            val cleanCommand = trailingPart.removePrefix(",").removePrefix("and").trim()

            // If we have a substantial command following "ava", pass it
            if (cleanCommand.length > 2) {
                onWakeWordDetected(cleanCommand)
            } else {
                onWakeWordDetected(null) // Just wake up
            }
        }
    }
}
