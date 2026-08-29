package com.laviavi.adsbandroid.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "aircraft_history")
data class AircraftHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val icao: String, val callsign: String?,
    val altitudeFt: Int?, val latitudeDeg: Double?, val longitudeDeg: Double?,
    val groundSpeedKt: Int?, val trackDeg: Int?, val timestampMs: Long,
)

@Dao interface AircraftHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: AircraftHistoryEntity)
    @Query("SELECT * FROM aircraft_history WHERE icao = :icao ORDER BY timestampMs DESC LIMIT :limit")
    suspend fun getHistory(icao: String, limit: Int = 100): List<AircraftHistoryEntity>
    @Query("SELECT * FROM aircraft_history ORDER BY timestampMs DESC")
    suspend fun getAll(): List<AircraftHistoryEntity>
    @Query("DELETE FROM aircraft_history WHERE timestampMs < :cutoffMs")
    suspend fun purgeOlderThan(cutoffMs: Long)
}

/**
 * One row per aircraft that has left the live list, holding its last known state.
 *
 * Distinct from [AircraftHistoryEntity], which is a position track log sampled
 * every 30 s and therefore only ever contains aircraft that reported a position.
 * This table is written on expiry, so an aircraft heard only as a bare Mode S
 * reply still appears. Keyed by ICAO: a re-appearing aircraft updates its row
 * rather than accumulating duplicates.
 */
@Entity(tableName = "aircraft_seen")
data class AircraftSeenEntity(
    @PrimaryKey val icao: String,
    val callsign: String?,
    val registration: String?,
    val operator: String?,
    /** True when [operator] is only the registered owner, not a confirmed airline. */
    val operatorIsOwner: Boolean = false,
    val aircraftType: String?,
    val route: String?,
    val altitudeFt: Int?,
    val groundSpeedKt: Int?,
    val trackDeg: Int?,
    val latitudeDeg: Double?,
    val longitudeDeg: Double?,
    val distanceNm: Double?,
    val squawk: String?,
    val messageCount: Int,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
)

@Dao interface AircraftSeenDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AircraftSeenEntity)

    @Query("SELECT * FROM aircraft_seen ORDER BY lastSeenMs DESC")
    fun observeAll(): Flow<List<AircraftSeenEntity>>

    @Query("SELECT * FROM aircraft_seen ORDER BY lastSeenMs DESC")
    suspend fun getAllOnce(): List<AircraftSeenEntity>

    @Query("SELECT COUNT(*) FROM aircraft_seen")
    suspend fun count(): Int

    @Query("DELETE FROM aircraft_seen WHERE lastSeenMs < :cutoffMs")
    suspend fun purgeOlderThan(cutoffMs: Long)

    @Query("DELETE FROM aircraft_seen")
    suspend fun clear()
}

/**
 * One row per departure — an append-only log, unlike [AircraftSeenEntity] which
 * replaces its row on every re-sighting. Powers the aircraft-stats screen's "times
 * seen" counts and per-aircraft visit history; independent of `aircraft_seen` so
 * clearing History never touches it.
 */
@Entity(tableName = "aircraft_visits", indices = [Index("icao")])
data class AircraftVisitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val icao: String,
    val registration: String?,
    val operator: String?,
    val aircraftType: String?,
    /** True only when [operator] is a confirmed airline name (`AircraftState.operatorKind == OperatorKind.AIRLINE`) — from the callsign prefix or a FlightAware scrape, never a bare registry owner. See `AircraftManager.setAircraftMeta`/`setFaResult`. */
    val isAirline: Boolean,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val messageCount: Int,
)

@Dao interface AircraftVisitDao {
    @Insert
    suspend fun insert(visit: AircraftVisitEntity)

    @Query("SELECT * FROM aircraft_visits ORDER BY firstSeenMs DESC")
    fun observeAll(): Flow<List<AircraftVisitEntity>>

    /** Zero means this departure is the first time this ICAO has ever been recorded. */
    @Query("SELECT COUNT(*) FROM aircraft_visits WHERE icao = :icao")
    suspend fun countByIcao(icao: String): Int
}

/**
 * One row per compass sector per 5-minute coverage tick, written only when that
 * sector actually saw traffic — the durable counterpart to [CoverageCsvLogger]'s
 * write-only CSV. Powers the Receiver tab's "all-time" coverage view.
 */
@Entity(tableName = "coverage_samples", indices = [Index("sector")])
data class CoverageSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long,
    val sector: String,
    val count: Int,
    val maxMi: Double,
    val medianSignalDbfs: Double?,
)

data class SectorAggregate(val sector: String, val count: Int, val maxMi: Double)

@Dao interface CoverageSampleDao {
    @Insert
    suspend fun insertAll(samples: List<CoverageSampleEntity>)

    @Query("SELECT sector, SUM(count) as count, MAX(maxMi) as maxMi FROM coverage_samples GROUP BY sector")
    suspend fun allTimeBySector(): List<SectorAggregate>

    @Query("DELETE FROM coverage_samples")
    suspend fun clearAll()
}

/**
 * One row per altitude band per 5-minute coverage tick, written only when that
 * band actually saw traffic — the all-time counterpart to [CoverageMetricsRow.altitudeCounts],
 * which `coverage_samples` alone has no room for. Powers the Receiver tab's
 * all-time altitude histogram the same way [CoverageSampleEntity] powers its polar.
 */
@Entity(tableName = "altitude_samples", indices = [Index("band")])
data class AltitudeSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long,
    val band: String,
    val count: Int,
)

data class AltitudeAggregate(val band: String, val count: Int)

@Dao interface AltitudeSampleDao {
    @Insert
    suspend fun insertAll(samples: List<AltitudeSampleEntity>)

    @Query("SELECT band, SUM(count) as count FROM altitude_samples GROUP BY band")
    suspend fun allTimeByBand(): List<AltitudeAggregate>

    @Query("DELETE FROM altitude_samples")
    suspend fun clearAll()
}

/** Singleton row (id always 0) holding the single furthest contact ever decoded. */
@Entity(tableName = "best_range_record")
data class BestRangeRecordEntity(
    @PrimaryKey val id: Int = 0,
    val icao: String,
    val callsign: String?,
    val distanceNm: Double,
    val bearingDeg: Double,
    val altitudeFt: Int?,
    val timestampMs: Long,
)

@Dao interface BestRangeDao {
    @Query("SELECT * FROM best_range_record WHERE id = 0")
    suspend fun get(): BestRangeRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: BestRangeRecordEntity)
}

@Entity(tableName = "enrichment_cache")
data class EnrichmentCacheEntity(
    @PrimaryKey val key: String,
    val route: String?, val origin: String?, val destination: String?, val cachedAtMs: Long,
)

@Dao interface EnrichmentCacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: EnrichmentCacheEntity)
    @Query("SELECT * FROM enrichment_cache WHERE key = :key")
    suspend fun get(key: String): EnrichmentCacheEntity?
    @Query("DELETE FROM enrichment_cache WHERE cachedAtMs < :cutoffMs")
    suspend fun purgeExpired(cutoffMs: Long)
    /** Used by the aircraft detail screen's "retry enrichment" action to force a fresh lookup. */
    @Query("DELETE FROM enrichment_cache WHERE key = :key")
    suspend fun deleteByKey(key: String)
}

@Entity(tableName = "aircraft_meta_cache")
data class AircraftMetaCacheEntity(
    @PrimaryKey val icao: String,
    val registration: String?,
    val manufacturer: String?,
    val model: String?,
    val typeCode: String?,
    val owner: String?,
    val source: String,
    val cachedAtMs: Long,
)

@Dao interface AircraftMetaCacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: AircraftMetaCacheEntity)
    @Query("SELECT * FROM aircraft_meta_cache WHERE icao = :icao")
    suspend fun get(icao: String): AircraftMetaCacheEntity?
    /** Used by the aircraft detail screen's "retry enrichment" action to force a fresh lookup. */
    @Query("DELETE FROM aircraft_meta_cache WHERE icao = :icao")
    suspend fun deleteByIcao(icao: String)
}

/**
 * One row per aircraft lifecycle/enrichment event: first detection, every enrichment
 * source actually queried (or served from cache) with what was requested and what
 * came back, and when the aircraft moved from the live table into history. Built to
 * answer "why didn't this aircraft get enriched" from the phone directly, without
 * reproducing the request live.
 */
@Entity(tableName = "aircraft_event_log", indices = [Index("icao"), Index("timestampMs")])
data class AircraftEventLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val icao: String,
    val timestampMs: Long,
    /** "DETECTED" | "ENRICHMENT_ATTEMPT" | "MOVED_TO_HISTORY" */
    val eventType: String,
    /** "hexdb" | "opensky" | "adsbdb-aircraft" | "adsbdb-route" | "flightaware" — null for DETECTED/MOVED_TO_HISTORY. */
    val source: String?,
    /** What was actually queried: the icao, the FlightAware ident, or the callsign. */
    val requestKey: String?,
    /** The exact URL called — reproducible with one curl. */
    val requestUrl: String?,
    /** true = short-circuited on a cached result, false = a real network call, null for non-enrichment events. */
    val servedFromCache: Boolean?,
    val success: Boolean?,
    /** Short human string: "reg=C-FPCG type=DHC2 owner=Seair Seaplanes" / "no data" / "error: <message>". */
    val resultSummary: String?,
    val durationMs: Long?,
)

@Dao interface AircraftEventLogDao {
    @Insert
    suspend fun insert(entity: AircraftEventLogEntity)
    @Query("SELECT * FROM aircraft_event_log WHERE icao = :icao ORDER BY timestampMs ASC")
    suspend fun forIcao(icao: String): List<AircraftEventLogEntity>
    /** Every logged event across every icao, newest first — independent of live/history status, backs the CSV export. */
    @Query("SELECT * FROM aircraft_event_log ORDER BY timestampMs DESC")
    suspend fun getAll(): List<AircraftEventLogEntity>
    @Query("DELETE FROM aircraft_event_log WHERE timestampMs < :cutoffMs")
    suspend fun purgeOlderThan(cutoffMs: Long)
}

/**
 * One row per aircraft in ADS-B Exchange's `basic-ac-db.json.gz` — a global, daily-
 * updated mirror downloaded and imported wholesale (see `GlobalAircraftDb`), not a
 * per-lookup cache. Checked before any network meta source; hexdb/adsbdb only fill
 * whatever field this table doesn't have. No write-back: a field a network source
 * fills in is never saved here, only into the existing 2h `aircraft_meta_cache`.
 */
@Entity(tableName = "global_aircraft")
data class GlobalAircraftEntity(
    @PrimaryKey val icao: String,
    val registration: String?,
    val typeCode: String?,
    val manufacturer: String?,
    val model: String?,
    val owner: String?,
    val military: Boolean,
)

@Dao interface GlobalAircraftDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<GlobalAircraftEntity>)
    @Query("SELECT * FROM global_aircraft WHERE icao = :icao")
    suspend fun get(icao: String): GlobalAircraftEntity?
    @Query("DELETE FROM global_aircraft")
    suspend fun clear()
    @Query("SELECT COUNT(*) FROM global_aircraft")
    suspend fun count(): Int
}

/**
 * Singleton row (id always 0) recording the last successful import, so a refresh
 * can skip re-downloading an unchanged file. [errorMessage] is the *most recent
 * attempt's* failure, if any — [importedAtMs]/[rowCount]/[remoteLastModified] stay
 * from the last actual success, never cleared by a failed attempt, so a lookup
 * always has the last-known-good data to work with even while an error is showing.
 */
@Entity(tableName = "global_aircraft_import")
data class GlobalAircraftImportEntity(
    @PrimaryKey val id: Int = 0,
    val remoteLastModified: String?,
    val importedAtMs: Long,
    val rowCount: Int,
    val errorMessage: String? = null,
)

@Dao interface GlobalAircraftImportDao {
    @Query("SELECT * FROM global_aircraft_import WHERE id = 0")
    suspend fun get(): GlobalAircraftImportEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: GlobalAircraftImportEntity)
}

@Database(
    entities = [
        AircraftHistoryEntity::class,
        AircraftSeenEntity::class,
        EnrichmentCacheEntity::class,
        AircraftMetaCacheEntity::class,
        AircraftVisitEntity::class,
        CoverageSampleEntity::class,
        AltitudeSampleEntity::class,
        BestRangeRecordEntity::class,
        AircraftEventLogEntity::class,
        GlobalAircraftEntity::class,
        GlobalAircraftImportEntity::class,
    ],
    version = 12,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun aircraftHistoryDao(): AircraftHistoryDao
    abstract fun aircraftSeenDao(): AircraftSeenDao
    abstract fun enrichmentCacheDao(): EnrichmentCacheDao
    abstract fun aircraftMetaCacheDao(): AircraftMetaCacheDao
    abstract fun aircraftVisitDao(): AircraftVisitDao
    abstract fun coverageSampleDao(): CoverageSampleDao
    abstract fun altitudeSampleDao(): AltitudeSampleDao
    abstract fun bestRangeDao(): BestRangeDao
    abstract fun aircraftEventLogDao(): AircraftEventLogDao
    abstract fun globalAircraftDao(): GlobalAircraftDao
    abstract fun globalAircraftImportDao(): GlobalAircraftImportDao
}

/** v8 -> v9: added `global_aircraft` (ADS-B Exchange mirror) + `global_aircraft_import` (staleness tracking). */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `global_aircraft` (
                `icao` TEXT NOT NULL PRIMARY KEY,
                `registration` TEXT,
                `typeCode` TEXT,
                `manufacturer` TEXT,
                `model` TEXT,
                `owner` TEXT,
                `military` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `global_aircraft_import` (
                `id` INTEGER NOT NULL PRIMARY KEY,
                `remoteLastModified` TEXT,
                `importedAtMs` INTEGER NOT NULL,
                `rowCount` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }
}

/** v9 -> v10: added `global_aircraft_import.errorMessage` — the last refresh attempt's failure, if any, surfaced for the History screen's "Check DB" status. */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `global_aircraft_import` ADD COLUMN `errorMessage` TEXT")
    }
}

/** v10 -> v11: added `aircraft_seen.operatorIsOwner` — distinguishes a registered-owner value from a confirmed airline name in History and its CSV export. */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `aircraft_seen` ADD COLUMN `operatorIsOwner` INTEGER NOT NULL DEFAULT 0")
    }
}

/** v11 -> v12: added `altitude_samples`, the all-time counterpart to `coverage_samples` that lets the Receiver tab's all-time altitude histogram show real data instead of always-zero. */
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `altitude_samples` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `timestampMs` INTEGER NOT NULL,
                `band` TEXT NOT NULL,
                `count` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_altitude_samples_band` ON `altitude_samples` (`band`)")
    }
}

/** v7 -> v8: added `aircraft_event_log`, the per-aircraft detection/enrichment/departure audit trail. */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `aircraft_event_log` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `icao` TEXT NOT NULL,
                `timestampMs` INTEGER NOT NULL,
                `eventType` TEXT NOT NULL,
                `source` TEXT,
                `requestKey` TEXT,
                `requestUrl` TEXT,
                `servedFromCache` INTEGER,
                `success` INTEGER,
                `resultSummary` TEXT,
                `durationMs` INTEGER
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_aircraft_event_log_icao` ON `aircraft_event_log` (`icao`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_aircraft_event_log_timestampMs` ON `aircraft_event_log` (`timestampMs`)")
    }
}

/**
 * v1 -> v2 (Session 9): added `aircraft_seen`, the "one row per departed
 * aircraft" table the History screen reads. Did not exist before this version.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `aircraft_seen` (
                `icao` TEXT NOT NULL PRIMARY KEY,
                `callsign` TEXT,
                `registration` TEXT,
                `operator` TEXT,
                `aircraftType` TEXT,
                `route` TEXT,
                `altitudeFt` INTEGER,
                `groundSpeedKt` INTEGER,
                `trackDeg` INTEGER,
                `latitudeDeg` REAL,
                `longitudeDeg` REAL,
                `distanceNm` REAL,
                `squawk` INTEGER,
                `messageCount` INTEGER NOT NULL,
                `firstSeenMs` INTEGER NOT NULL,
                `lastSeenMs` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }
}

/**
 * v2 -> v3 (Session 10): `aircraft_seen.squawk` changes from INTEGER to TEXT —
 * the fix that replaced dump1090's hex-packed squawk representation with the
 * formatted 4-digit octal string the decoder now emits directly (see
 * `decoder/MessageDecoder.kt:decodeIdentity`). SQLite has no `ALTER COLUMN
 * TYPE`, so the table is rebuilt.
 *
 * The old INTEGER values are dropped rather than stringified: they are the
 * dump1090-hex-pack values the Session 10 fix replaced (squawk 6272 stored as
 * 25202), and `CAST(squawk AS TEXT)` would turn that into the equally-wrong
 * string "25202" — a value that looks like a plausible squawk but isn't one.
 * Losing a handful of historical squawk fields is a smaller cost than carrying
 * corrupted data forward under a type that no longer signals it might be wrong.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `aircraft_meta_cache` (
                `icao` TEXT NOT NULL PRIMARY KEY,
                `registration` TEXT,
                `manufacturer` TEXT,
                `model` TEXT,
                `typeCode` TEXT,
                `owner` TEXT,
                `source` TEXT NOT NULL,
                `cachedAtMs` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `faa_aircraft` (
                `icao` TEXT NOT NULL PRIMARY KEY,
                `registration` TEXT,
                `manufacturer` TEXT,
                `model` TEXT,
                `owner` TEXT
            )
            """.trimIndent(),
        )
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS `faa_aircraft`")
    }
}

/** v5 -> v6: added `aircraft_visits`, the append-only log the aircraft-stats screen reads. */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `aircraft_visits` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `icao` TEXT NOT NULL,
                `registration` TEXT,
                `operator` TEXT,
                `aircraftType` TEXT,
                `isAirline` INTEGER NOT NULL,
                `firstSeenMs` INTEGER NOT NULL,
                `lastSeenMs` INTEGER NOT NULL,
                `messageCount` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_aircraft_visits_icao` ON `aircraft_visits` (`icao`)")
    }
}

/** v6 -> v7: added `coverage_samples` (durable coverage history) and `best_range_record` (personal-best contact). */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `coverage_samples` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `timestampMs` INTEGER NOT NULL,
                `sector` TEXT NOT NULL,
                `count` INTEGER NOT NULL,
                `maxMi` REAL NOT NULL,
                `medianSignalDbfs` REAL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_coverage_samples_sector` ON `coverage_samples` (`sector`)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `best_range_record` (
                `id` INTEGER NOT NULL PRIMARY KEY,
                `icao` TEXT NOT NULL,
                `callsign` TEXT,
                `distanceNm` REAL NOT NULL,
                `bearingDeg` REAL NOT NULL,
                `altitudeFt` INTEGER,
                `timestampMs` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE `aircraft_seen_new` (
                `icao` TEXT NOT NULL PRIMARY KEY,
                `callsign` TEXT,
                `registration` TEXT,
                `operator` TEXT,
                `aircraftType` TEXT,
                `route` TEXT,
                `altitudeFt` INTEGER,
                `groundSpeedKt` INTEGER,
                `trackDeg` INTEGER,
                `latitudeDeg` REAL,
                `longitudeDeg` REAL,
                `distanceNm` REAL,
                `squawk` TEXT,
                `messageCount` INTEGER NOT NULL,
                `firstSeenMs` INTEGER NOT NULL,
                `lastSeenMs` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO `aircraft_seen_new`
            SELECT icao, callsign, registration, operator, aircraftType, route,
                   altitudeFt, groundSpeedKt, trackDeg, latitudeDeg, longitudeDeg,
                   distanceNm, NULL, messageCount, firstSeenMs, lastSeenMs
            FROM `aircraft_seen`
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE `aircraft_seen`")
        db.execSQL("ALTER TABLE `aircraft_seen_new` RENAME TO `aircraft_seen`")
    }
}
