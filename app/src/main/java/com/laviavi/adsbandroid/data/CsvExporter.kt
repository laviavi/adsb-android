package com.laviavi.adsbandroid.data

import android.content.Context
import java.io.File

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
}
