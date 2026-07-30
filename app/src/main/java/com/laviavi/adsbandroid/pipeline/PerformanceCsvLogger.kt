package com.laviavi.adsbandroid.pipeline

import android.content.Context
import com.laviavi.adsbandroid.observability.CsvTimestamps
import com.laviavi.adsbandroid.observability.MessageCounters
import com.laviavi.adsbandroid.observability.PerformanceMetrics
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ticks every [PerformanceMetrics.INTERVAL_SEC] seconds, snapshotting
 * [PipelineStats] and the current aircraft ICAO set into one CSV row — port
 * of `observability/performance.py`'s `PerformanceLogger`. Day-rotating file,
 * same pattern as [RawMessageLogger].
 */
class PerformanceCsvLogger(private val context: Context) {

    private var writer: BufferedWriter? = null
    private var currentDay: String? = null
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private var previous: MessageCounters? = null
    private var previousIcaos: Set<String> = emptySet()

    /** First tick only establishes the baseline — matches the reference, which writes nothing on its first snapshot either. */
    @Synchronized
    fun tick(current: MessageCounters, currentIcaos: Set<String>) {
        val prev = previous
        if (prev == null) {
            previous = current
            previousIcaos = currentIcaos
            return
        }
        val row = PerformanceMetrics.computeRow(prev, current, previousIcaos, currentIcaos)
        writeRow(PerformanceMetrics.toCsvValues(row, CsvTimestamps.now()))
        previous = current
        previousIcaos = currentIcaos
    }

    private fun writeRow(values: List<String>) {
        val day = dayFmt.format(Date())
        if (day != currentDay) {
            writer?.close()
            val file = File(context.getExternalFilesDir(null), "performance_$day.csv")
            val isNew = !file.exists()
            writer = BufferedWriter(FileWriter(file, true))
            if (isNew) writer?.appendLine(PerformanceMetrics.COLUMNS.joinToString(","))
            currentDay = day
        }
        writer?.apply { appendLine(values.joinToString(",") { csvEscape(it) }); flush() }
    }

    @Synchronized
    fun close() { writer?.close(); writer = null }
}

/** Minimal RFC 4180 quoting — only engages if a field actually needs it. */
internal fun csvEscape(field: String): String =
    if (field.any { it == ',' || it == '"' || it == '\n' }) "\"${field.replace("\"", "\"\"")}\"" else field
