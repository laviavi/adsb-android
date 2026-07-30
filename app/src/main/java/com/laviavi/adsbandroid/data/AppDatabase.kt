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

    @Query("SELECT COUNT(*) FROM aircraft_seen")
    suspend fun count(): Int

    @Query("DELETE FROM aircraft_seen WHERE lastSeenMs < :cutoffMs")
    suspend fun purgeOlderThan(cutoffMs: Long)

    @Query("DELETE FROM aircraft_seen")
    suspend fun clear()
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
}

@Database(
    entities = [
        AircraftHistoryEntity::class,
        AircraftSeenEntity::class,
        EnrichmentCacheEntity::class,
        AircraftMetaCacheEntity::class,
    ],
    version = 5,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun aircraftHistoryDao(): AircraftHistoryDao
    abstract fun aircraftSeenDao(): AircraftSeenDao
    abstract fun enrichmentCacheDao(): EnrichmentCacheDao
    abstract fun aircraftMetaCacheDao(): AircraftMetaCacheDao
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
