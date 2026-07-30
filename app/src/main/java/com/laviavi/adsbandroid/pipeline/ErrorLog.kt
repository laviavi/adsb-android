package com.laviavi.adsbandroid.pipeline

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lightweight error/event log so problems are visible without watching the phone screen.
 * Query remotely via: adb logcat -s AdsbErrorLog:*
 * Also exposed as a StateFlow for future in-app display.
 */
object ErrorLog {
    private const val TAG = "AdsbErrorLog"
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    data class Entry(val timestamp: Long, val level: String, val message: String)

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    private const val MAX_ENTRIES = 200

    fun error(message: String, throwable: Throwable? = null) {
        val full = if (throwable != null) "$message: ${throwable.javaClass.simpleName}: ${throwable.message}" else message
        Log.e(TAG, full, throwable)
        record("ERROR", full)
    }

    fun warn(message: String) {
        Log.w(TAG, message)
        record("WARN", message)
    }

    fun info(message: String) {
        Log.i(TAG, message)
        record("INFO", message)
    }

    private fun record(level: String, message: String) {
        val entry = Entry(System.currentTimeMillis(), level, message)
        _entries.value = (_entries.value + entry).takeLast(MAX_ENTRIES)
    }
}
