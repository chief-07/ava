package com.ava.util

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * AppLogger is a utility that writes logs to both standard Android Logcat
 * and a StateFlow list so that the MainActivity can display them in the UI.
 */
object AppLogger {
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

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
        _logs.update { (it + line).takeLast(100) } // Keep last 100 log lines
    }

    fun clear() {
        _logs.value = emptyList()
    }
}
