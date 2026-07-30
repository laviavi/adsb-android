package com.laviavi.adsbandroid.pipeline

import android.content.Context
import com.laviavi.adsbandroid.observability.CoverageMetrics
import com.laviavi.adsbandroid.observability.CsvTimestamps
import com.laviavi.adsbandroid.observability.PositionedAircraft
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ticks every [CoverageMetrics.INTERVAL_SEC] seconds, aggregating currently
 * tracked aircraft into one directional-coverage CSV row — port of
 * `observability/coverage.py`'s `CoverageLogger`. Day-rotating file, same
 * pattern as [RawMessageLogger].
 *
 * Unlike the Python reference, there is no "observer position unset" skip:
 * `AppConfig` always carries some observer coordinates (see
 * `CoverageMetrics.computeRow`'s doc comment). A row is skipped only when no
 * aircraft currently has a known position.
 */
class CoverageCsvLogger(private val context: Context) {

    private var writer: BufferedWriter? = null
    private var currentDay: String? = null
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    @Synchronized
    fun tick(observerLat: Double, observerLon: Double, aircraft: List<PositionedAircraft>) {
        val row = CoverageMetrics.computeRow(observerLat, observerLon, aircraft) ?: return
        writeRow(CoverageMetrics.toCsvValues(row, CsvTimestamps.now()))
    }

    private fun writeRow(values: List<String>) {
        val day = dayFmt.format(Date())
        if (day != currentDay) {
            writer?.close()
            val file = File(context.getExternalFilesDir(null), "coverage_$day.csv")
            val isNew = !file.exists()
            writer = BufferedWriter(FileWriter(file, true))
            if (isNew) writer?.appendLine(CoverageMetrics.COLUMNS.joinToString(","))
            currentDay = day
        }
        writer?.apply { appendLine(values.joinToString(",") { csvEscape(it) }); flush() }
    }

    @Synchronized
    fun close() { writer?.close(); writer = null }
}
