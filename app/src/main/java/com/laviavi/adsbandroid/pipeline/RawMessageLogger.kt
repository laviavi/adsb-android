package com.laviavi.adsbandroid.pipeline

import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Appends raw decoded messages to a daily rotating log file — dump1090.log equivalent. */
class RawMessageLogger(private val context: Context) {
    private var writer: BufferedWriter? = null
    private var currentDay: String? = null
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val tsFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun log(rawHex: String) {
        val now = Date()
        val day = dayFmt.format(now)
        if (day != currentDay) {
            writer?.close()
            val file = File(context.getExternalFilesDir(null), "adsb_$day.log")
            writer = BufferedWriter(FileWriter(file, true))
            currentDay = day
        }
        writer?.apply { write("${tsFmt.format(now)} *$rawHex;\n"); flush() }
    }

    @Synchronized
    fun close() { writer?.close(); writer = null }
}
