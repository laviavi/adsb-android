package com.laviavi.adsbandroid.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Exports the aircraft_history table to a CSV file under external app storage. */
object CsvExporter {
    private const val HEADER = "icao,callsign,altitude_ft,latitude,longitude,ground_speed_kt,track_deg,timestamp_ms\n"

    suspend fun exportHistory(context: Context, dao: AircraftHistoryDao): File {
        val rows = dao.getAll()
        val file = File(context.getExternalFilesDir(null), "aircraft_history_${System.currentTimeMillis()}.csv")
        file.bufferedWriter().use { w ->
            w.write(HEADER)
            for (r in rows) {
                w.write("${r.icao},${r.callsign.orEmpty()},${r.altitudeFt ?: ""},${r.latitudeDeg ?: ""}," +
                    "${r.longitudeDeg ?: ""},${r.groundSpeedKt ?: ""},${r.trackDeg ?: ""},${r.timestampMs}\n")
            }
        }
        return file
    }

    private const val SEEN_HEADER = "icao,callsign,registration,aircraft_type,operator,route,altitude_ft," +
        "ground_speed_kt,track_deg,latitude,longitude,distance_nm,squawk,message_count," +
        "first_seen,last_seen\n"

    // No seconds, per the History screen's own display convention (see HistoryRow's TIME_FORMAT).
    private fun timestampFormat() = SimpleDateFormat("MM/dd/yyyy HH:mm", Locale.US)

    /** Exports the aircraft_seen table (the History screen's data) to a shareable CSV. */
    suspend fun exportAircraftSeen(context: Context, dao: AircraftSeenDao): File {
        val rows = dao.getAllOnce()
        val fmt = timestampFormat()
        val file = File(context.getExternalFilesDir(null), "aircraft_seen_${System.currentTimeMillis()}.csv")
        file.bufferedWriter().use { w ->
            w.write(SEEN_HEADER)
            for (r in rows) {
                w.write(
                    "${r.icao},${csv(r.callsign)},${csv(r.registration)},${csv(r.aircraftType)}," +
                        "${csv(r.operator)},${csv(r.route)},${r.altitudeFt ?: ""},${r.groundSpeedKt ?: ""}," +
                        "${r.trackDeg ?: ""},${r.latitudeDeg ?: ""},${r.longitudeDeg ?: ""}," +
                        "${r.distanceNm ?: ""},${csv(r.squawk)},${r.messageCount}," +
                        "${fmt.format(Date(r.firstSeenMs))},${fmt.format(Date(r.lastSeenMs))}\n",
                )
            }
        }
        return file
    }

    /** Quotes a field only when it contains a character that would otherwise break the CSV. */
    private fun csv(value: String?): String {
        val v = value ?: return ""
        return if (v.any { it == ',' || it == '"' || it == '\n' }) "\"${v.replace("\"", "\"\"")}\"" else v
    }

    private const val EVENT_LOG_HEADER = "icao,timestamp,event_type,source,request_key,request_url," +
        "served_from_cache,success,result_summary,duration_ms\n"

    private fun eventTimestampFormat() = SimpleDateFormat("MM/dd/yyyy HH:mm:ss", Locale.US)

    /**
     * Exports the full aircraft_event_log table (every icao, live or since departed) to a
     * shareable CSV — the table itself is never gated on live/history status, only on the
     * existing 7-day purge, so this is the way to get at a departed aircraft's log.
     */
    suspend fun exportEventLog(context: Context, dao: AircraftEventLogDao): File {
        val rows = dao.getAll()
        val fmt = eventTimestampFormat()
        val file = File(context.getExternalFilesDir(null), "enrichment_log_${System.currentTimeMillis()}.csv")
        file.bufferedWriter().use { w ->
            w.write(EVENT_LOG_HEADER)
            for (r in rows) {
                w.write(
                    "${r.icao},${fmt.format(Date(r.timestampMs))},${csv(r.eventType)},${csv(r.source)}," +
                        "${csv(r.requestKey)},${csv(r.requestUrl)},${r.servedFromCache ?: ""},${r.success ?: ""}," +
                        "${csv(r.resultSummary)},${r.durationMs ?: ""}\n",
                )
            }
        }
        return file
    }
}
