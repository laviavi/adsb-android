package com.laviavi.adsbandroid.pipeline

import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * TEMPORARY DEBUG AID — investigating "aircraft seen live today never showed up in
 * History." Not part of the shipped feature set; safe to delete this whole file plus
 * every call site tagged `// TEMP DEBUG: history investigation` once the cause is found.
 *
 * Writes one CSV row per watchdog tick and per recordDeparted() write outcome, so the
 * two competing hypotheses can be told apart from the output alone:
 *   - the expiry loop itself stops running (STALE_STILL_LIVE rows appear: an aircraft
 *     stayed in the live table well past its own expiry window)
 *   - the expiry loop is fine but recordDeparted()'s DB write is silently failing for
 *     specific aircraft (WRITE_FAIL rows appear, or VANISHED_NOT_IN_HISTORY rows appear
 *     with no matching WRITE_FAIL — meaning recordDeparted was never even called)
 *
 * Same day-rotating-file pattern as [PerformanceCsvLogger]/[CoverageCsvLogger].
 */
class HistoryDebugLogger(private val context: Context) {

    private var writer: BufferedWriter? = null
    private var currentDay: String? = null
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val tsFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /** One row per watchdog tick: overall health snapshot. */
    @Synchronized
    fun logTick(liveCount: Int, expirySeconds: Int, staleStillLive: List<String>, vanishedNotInHistory: List<String>) {
        writeRow(
            listOf(
                "TICK", "", "",
                "live=$liveCount expirySeconds=$expirySeconds",
                "staleStillLive=${staleStillLive.joinToString(";")}",
                "vanishedNotInHistory=${vanishedNotInHistory.joinToString(";")}",
            )
        )
    }

    /** One row per recordDeparted() write attempt (event log / aircraft_seen / visit), success or failure. */
    @Synchronized
    fun logWriteOutcome(icao: String, step: String, success: Boolean, error: String?) {
        writeRow(listOf(if (success) "WRITE_OK" else "WRITE_FAIL", icao, step, error ?: "", "", ""))
    }

    private fun writeRow(fields: List<String>) {
        val day = dayFmt.format(Date())
        if (day != currentDay) {
            writer?.close()
            val file = File(context.getExternalFilesDir(null), "history_debug_$day.csv")
            val isNew = !file.exists()
            writer = BufferedWriter(FileWriter(file, true))
            if (isNew) writer?.appendLine("timestamp,event,icao,step_or_detail,detail2,detail3")
            currentDay = day
        }
        val row = listOf(tsFmt.format(Date())) + fields
        writer?.apply { appendLine(row.joinToString(",") { csvEscape(it) }); flush() }
    }

    @Synchronized
    fun close() { writer?.close(); writer = null }

    /** Today's debug file, for the share action — Android's scoped storage blocks browsing
     *  Android/data/<pkg>/files via a file manager, so the app has to hand it over directly. */
    fun currentFile(): File = File(context.getExternalFilesDir(null), "history_debug_${dayFmt.format(Date())}.csv")
}
