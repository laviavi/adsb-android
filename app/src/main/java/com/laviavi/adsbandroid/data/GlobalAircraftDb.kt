package com.laviavi.adsbandroid.data

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.http.HttpHeaders
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

private const val TAG = "GlobalAircraftDb"
private const val SOURCE_URL = "https://downloads.adsbexchange.com/downloads/basic-ac-db.json.gz"
private const val IMPORT_BATCH_SIZE = 1_000

private val globalDbJson = Json { ignoreUnknownKeys = true }

@Serializable
internal data class GlobalAcDbRow(
    val icao: String,
    val reg: String? = null,
    val icaotype: String? = null,
    val manufacturer: String? = null,
    val model: String? = null,
    val ownop: String? = null,
    val mil: Boolean = false,
)

/** Pure so the real file's line shape is directly testable without a network call or a Room instance. */
internal fun parseGlobalAcDbLine(line: String): GlobalAircraftEntity? {
    val row = runCatching { globalDbJson.decodeFromString<GlobalAcDbRow>(line) }.getOrNull() ?: return null
    return GlobalAircraftEntity(
        icao = row.icao.uppercase(),
        registration = row.reg.present(),
        typeCode = row.icaotype.present()?.uppercase(),
        manufacturer = row.manufacturer.present(),
        model = row.model.present(),
        owner = row.ownop.present(),
        military = row.mil,
    )
}

/**
 * Local mirror of ADS-B Exchange's `basic-ac-db.json.gz` — a global, daily-updated
 * aircraft database (614k+ aircraft at last check, confirmed live against real
 * ICAOs this app has seen: C04205, C0809E both matched exactly). Downloaded and
 * imported wholesale on a schedule, not per-aircraft — this is what turns meta
 * enrichment mostly offline instead of hitting hexdb/adsbdb for every aircraft.
 *
 * No write-back: a field hexdb/adsbdb fills in that this table lacks is saved only
 * into the existing 2h `aircraft_meta_cache`, never here. This table is a pure
 * mirror of the upstream file, fully replaced on each refresh.
 */
class GlobalAircraftDb(
    private val dao: GlobalAircraftDao,
    private val importDao: GlobalAircraftImportDao,
) {
    private val client = HttpClient(CIO) {
        engine { requestTimeout = 60_000 }
    }

    suspend fun lookup(icao: String): AircraftMeta? {
        val row = dao.get(icao.uppercase().trim()) ?: return null
        if (row.registration == null && row.manufacturer == null && row.model == null && row.typeCode == null) return null
        return AircraftMeta(
            icao = row.icao, registration = row.registration, manufacturer = row.manufacturer,
            model = row.model, typeCode = row.typeCode, owner = row.owner, source = "global-db",
        )
    }

    /** Releases the underlying HTTP engine's connection pool/threads. */
    fun close() = client.close()

    /**
     * Re-imports the mirror only if the remote file has actually changed since the
     * last import (a HEAD request compares `Last-Modified`) — safe to call on a
     * schedule, does nothing but one small request most of the time.
     */
    suspend fun refreshIfNeeded(): Result<Int> = runCatching {
        val remoteModified = client.head(SOURCE_URL).headers[HttpHeaders.LastModified]
        val lastImport = importDao.get()
        if (remoteModified != null && remoteModified == lastImport?.remoteLastModified) {
            Log.d(TAG, "global aircraft db unchanged since last import, skipping download")
            return@runCatching lastImport.rowCount
        }

        // Held as compressed bytes (~14MB) only — decompression and JSON parsing
        // both stream from here, the ~120MB decompressed text is never materialized
        // in memory at once, only the current line and the current insert batch.
        val compressed: ByteArray = client.get(SOURCE_URL).body()
        val rowCount = GZIPInputStream(compressed.inputStream()).use { gz ->
            BufferedReader(InputStreamReader(gz)).use { reader -> importStream(reader) }
        }

        importDao.upsert(
            GlobalAircraftImportEntity(
                remoteLastModified = remoteModified,
                importedAtMs = System.currentTimeMillis(),
                rowCount = rowCount,
            )
        )
        Log.d(TAG, "global aircraft db imported: $rowCount rows")
        rowCount
    }

    /** One JSON object per line (not a single array) — read and inserted in batches so no more than [IMPORT_BATCH_SIZE] parsed rows are held at once. */
    private suspend fun importStream(reader: BufferedReader): Int {
        dao.clear()
        var total = 0
        val batch = ArrayList<GlobalAircraftEntity>(IMPORT_BATCH_SIZE)
        reader.lineSequence().forEach { line ->
            val entity = parseGlobalAcDbLine(line) ?: return@forEach
            batch.add(entity)
            if (batch.size >= IMPORT_BATCH_SIZE) {
                dao.insertAll(batch)
                total += batch.size
                batch.clear()
            }
        }
        if (batch.isNotEmpty()) {
            dao.insertAll(batch)
            total += batch.size
        }
        return total
    }
}
