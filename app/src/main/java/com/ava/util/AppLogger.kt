package com.ava.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AppLogger writes logs to both standard Android Logcat and a StateFlow
 * so that the MainActivity can display them live.
 *
 * Logs are also written to a file so they survive process death.
 * Call AppLogger.init(context) once from MainActivity.onCreate.
 */
object AppLogger {
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private var logFile: File? = null
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    /** Call once from MainActivity.onCreate to enable file persistence. */
    fun init(context: Context) {
        logFile = File(context.filesDir, "ava_log.txt")
        // Load previous session logs from disk on startup
        if (logFile!!.exists()) {
            val previousLogs = logFile!!.readLines().takeLast(100)
            if (previousLogs.isNotEmpty()) {
                _logs.value = listOf("── previous session ──") + previousLogs
            }
        }
    }

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        addLog("[$tag] $msg")
    }

    fun d(tag: String, msg: String) {
        Log.d(tag, msg)
        addLog("[$tag] $msg")
    }

    fun w(tag: String, msg: String) {
        Log.w(tag, msg)
        addLog("[$tag] ⚠️ $msg")
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        Log.e(tag, msg, tr)
        val suffix = if (tr != null) " - ${tr.localizedMessage}" else ""
        addLog("[$tag] ❌ $msg$suffix")
    }

    private fun addLog(line: String) {
        val timestamped = "${timeFormat.format(Date())} $line"
        _logs.update { (it + timestamped).takeLast(100) }
        // Persist to file
        try {
            logFile?.appendText(timestamped + "\n")
        } catch (_: Exception) { /* best-effort */ }
    }

    fun clear() {
        _logs.value = emptyList()
        try { logFile?.delete() } catch (_: Exception) { }
    }
}
