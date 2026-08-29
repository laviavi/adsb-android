package com.laviavi.adsbandroid.data

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Room used `fallbackToDestructiveMigration()` through versions 1-3, wiping
 * `aircraft_history`/`aircraft_seen` on every schema bump (Sessions 9 and 10
 * each bumped the version). This is the fix: real [MIGRATION_1_2] /
 * [MIGRATION_2_3] objects, verified here against a genuine SQLite engine.
 *
 * Robolectric, not a plain JVM test: Room's SQLite bindings are the real
 * Android framework classes, and there is no other SQLite driver wired into
 * this project. [LegacyV1Database] builds a byte-correct v1 database via Room
 * itself rather than hand-transcribed `CREATE TABLE` SQL, so there is nothing
 * here that could be subtly wrong in the *test's* setup — only in the
 * migrations, which is what should be under test.
 */
// targetSdk is 35; Robolectric 4.13's shadows are only fully verified through
// API 34, so pin the test SDK explicitly rather than inherit the manifest's.
@Config(sdk = [34])
@RunWith(RobolectricTestRunner::class)
class AppDatabaseMigrationTests {

    /** The exact v1 shape: only the two tables that existed before Session 9. */
    @Database(entities = [AircraftHistoryEntity::class, EnrichmentCacheEntity::class], version = 1)
    abstract class LegacyV1Database : RoomDatabase() {
        abstract fun historyDao(): AircraftHistoryDao
        abstract fun enrichmentDao(): EnrichmentCacheDao
    }

    private val dbName = "migration-test.db"
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before fun setUp() {
        context.deleteDatabase(dbName)
    }

    @After fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test fun `v1 to v3 preserves existing rows and adds a working aircraft_seen table`() {
        val legacy = Room.databaseBuilder(context, LegacyV1Database::class.java, dbName).build()
        runBlocking {
            legacy.historyDao().insert(
                AircraftHistoryEntity(
                    icao = "ABCDEF", callsign = "TEST123", altitudeFt = 35_000,
                    latitudeDeg = 1.0, longitudeDeg = 2.0, groundSpeedKt = 400,
                    trackDeg = 90, timestampMs = 1_000L,
                ),
            )
            legacy.enrichmentDao().insert(
                EnrichmentCacheEntity(
                    key = "TEST123", route = "KLAX-KJFK", origin = "KLAX",
                    destination = "KJFK", cachedAtMs = 2_000L,
                ),
            )
        }
        legacy.close()

        // Open the SAME file with the real, current AppDatabase and the
        // migrations under test. Room validates the resulting schema against
        // AppDatabase's actual entities — a wrong migration throws here rather
        // than silently succeeding.
        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
            .build()

        runBlocking {
            val history = migrated.aircraftHistoryDao().getAll()
            assertEquals(1, history.size)
            assertEquals("ABCDEF", history[0].icao)
            assertEquals("TEST123", history[0].callsign)
            assertEquals(35_000, history[0].altitudeFt)

            // aircraft_seen exists and is fully usable via the current DAO —
            // this is the table that did not exist at all before v2.
            migrated.aircraftSeenDao().upsert(
                AircraftSeenEntity(
                    icao = "112233", callsign = "NEW1", registration = null, operator = null,
                    aircraftType = null, route = null, altitudeFt = 10_000, groundSpeedKt = 200,
                    trackDeg = 180, latitudeDeg = 3.0, longitudeDeg = 4.0, distanceNm = 5.0,
                    squawk = "7700", messageCount = 1, firstSeenMs = 0L, lastSeenMs = 0L,
                ),
            )
            assertEquals(1, migrated.aircraftSeenDao().count())
        }
        migrated.close()
    }

    @Test fun `v2 to v3 drops the old integer squawk rather than stringifying garbage`() {
        // v2's aircraft_seen.squawk was INTEGER — the dump1090 hex-pack bug
        // (Session 10). Build that shape with MIGRATION_1_2 itself, then insert
        // a row with a raw pre-fix value before running MIGRATION_2_3.
        val legacy = Room.databaseBuilder(context, LegacyV1Database::class.java, dbName).build()
        val raw = legacy.openHelper.writableDatabase
        MIGRATION_1_2.migrate(raw)
        raw.execSQL(
            "INSERT INTO aircraft_seen (icao, messageCount, firstSeenMs, lastSeenMs, squawk) " +
                "VALUES ('AAAAAA', 1, 0, 0, 25202)", // 25202 = the old hex-packed value for squawk 6272
        )
        legacy.close()

        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
            .build()

        val seen = runBlocking { migrated.aircraftSeenDao().observeAll().first() }
        assertEquals(1, seen.size)
        assertNull(
            "corrupted pre-fix data must not survive as a plausible-looking string",
            seen.single().squawk,
        )
        migrated.close()
    }

    @Test fun `v5 to v6 adds a working aircraft_visits table without touching aircraft_seen`() {
        // Build a real v1 db, then run every migration up to v5 by hand to land
        // on the exact pre-v6 shape, mirroring how the v2-to-v3 test above builds
        // its starting point.
        val legacy = Room.databaseBuilder(context, LegacyV1Database::class.java, dbName).build()
        val raw = legacy.openHelper.writableDatabase
        MIGRATION_1_2.migrate(raw)
        MIGRATION_2_3.migrate(raw)
        MIGRATION_3_4.migrate(raw)
        MIGRATION_4_5.migrate(raw)
        raw.execSQL(
            "INSERT INTO aircraft_seen (icao, messageCount, firstSeenMs, lastSeenMs) " +
                "VALUES ('AAAAAA', 1, 0, 0)",
        )
        legacy.close()

        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
            .build()

        runBlocking {
            // Pre-existing aircraft_seen row survives — v6 only adds a table, touches nothing else.
            assertEquals(1, migrated.aircraftSeenDao().count())

            migrated.aircraftVisitDao().insert(
                AircraftVisitEntity(
                    icao = "AAAAAA", registration = "N123AB", operator = "Test Air",
                    aircraftType = "B738", isAirline = true,
                    firstSeenMs = 1_000L, lastSeenMs = 2_000L, messageCount = 50,
                ),
            )
            val visits = migrated.aircraftVisitDao().observeAll().first()
            assertEquals(1, visits.size)
            assertEquals("N123AB", visits.single().registration)
        }
        migrated.close()
    }

    @Test fun `v6 to v7 adds coverage_samples and best_range_record without touching aircraft_visits`() {
        val legacy = Room.databaseBuilder(context, LegacyV1Database::class.java, dbName).build()
        val raw = legacy.openHelper.writableDatabase
        MIGRATION_1_2.migrate(raw)
        MIGRATION_2_3.migrate(raw)
        MIGRATION_3_4.migrate(raw)
        MIGRATION_4_5.migrate(raw)
        MIGRATION_5_6.migrate(raw)
        raw.execSQL(
            "INSERT INTO aircraft_visits (icao, isAirline, firstSeenMs, lastSeenMs, messageCount) " +
                "VALUES ('AAAAAA', 1, 0, 0, 1)",
        )
        legacy.close()

        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
            .build()

        runBlocking {
            // Pre-existing aircraft_visits row survives — v7 only adds two tables.
            assertEquals(1, migrated.aircraftVisitDao().observeAll().first().size)

            migrated.coverageSampleDao().insertAll(listOf(
                CoverageSampleEntity(timestampMs = 1_000L, sector = "N", count = 3, maxMi = 42.0, medianSignalDbfs = -12.0),
            ))
            val totals = migrated.coverageSampleDao().allTimeBySector()
            assertEquals(1, totals.size)
            assertEquals(42.0, totals.single().maxMi, 1e-9)

            migrated.bestRangeDao().upsert(
                BestRangeRecordEntity(
                    icao = "AAAAAA", callsign = "TEST1", distanceNm = 150.0,
                    bearingDeg = 90.0, altitudeFt = 35_000, timestampMs = 2_000L,
                ),
            )
            assertEquals(150.0, migrated.bestRangeDao().get()?.distanceNm ?: 0.0, 1e-9)
        }
        migrated.close()
    }

    @Test fun `v10 to v11 adds aircraft_seen operatorIsOwner, defaulting existing rows to false`() {
        // Only pre-migrated far enough to legally insert into aircraft_seen (present
        // since v1) — the actual, Room-tracked migration run (all of it, replayed
        // from v1) happens in the real builder below, same as every other test in
        // this file. Manually raw-migrating all the way to 9_10 here and then
        // letting Room replay it *again* double-runs its non-idempotent ADD COLUMN
        // and fails with "duplicate column name" — raw .migrate() calls never touch
        // user_version, so Room's own build() always replays the full chain.
        val legacy = Room.databaseBuilder(context, LegacyV1Database::class.java, dbName).build()
        val raw = legacy.openHelper.writableDatabase
        MIGRATION_1_2.migrate(raw)
        MIGRATION_2_3.migrate(raw)
        MIGRATION_3_4.migrate(raw)
        MIGRATION_4_5.migrate(raw)
        raw.execSQL(
            "INSERT INTO aircraft_seen (icao, operator, messageCount, firstSeenMs, lastSeenMs) " +
                "VALUES ('AAAAAA', 'UMB BANK NA TRUSTEE', 1, 0, 0)",
        )
        legacy.close()

        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
            .build()

        runBlocking {
            // Pre-existing row survives with the new column defaulted to false — a
            // row written before this migration existed can't retroactively know
            // whether its operator was an owner or an airline.
            val existing = migrated.aircraftSeenDao().getAllOnce().single { it.icao == "AAAAAA" }
            assertEquals("UMB BANK NA TRUSTEE", existing.operator)
            assertEquals(false, existing.operatorIsOwner)

            migrated.aircraftSeenDao().upsert(
                AircraftSeenEntity(
                    icao = "BBBBBB", callsign = null, registration = "N116AN", operator = "UMB BANK NA TRUSTEE",
                    operatorIsOwner = true, aircraftType = "A321", route = null, altitudeFt = null,
                    groundSpeedKt = null, trackDeg = null, latitudeDeg = null, longitudeDeg = null,
                    distanceNm = null, squawk = null, messageCount = 1, firstSeenMs = 0, lastSeenMs = 0,
                ),
            )
            assertEquals(true, migrated.aircraftSeenDao().getAllOnce().single { it.icao == "BBBBBB" }.operatorIsOwner)
        }
        migrated.close()
    }

    @Test fun `v11 to v12 adds a working altitude_samples table without touching coverage_samples`() {
        // Pre-existing tables only go back to v7 (coverage_samples) — no data needs
        // pre-migrating here since altitude_samples is new in v12 and starts empty.
        val legacy = Room.databaseBuilder(context, LegacyV1Database::class.java, dbName).build()
        val raw = legacy.openHelper.writableDatabase
        MIGRATION_1_2.migrate(raw)
        MIGRATION_2_3.migrate(raw)
        MIGRATION_3_4.migrate(raw)
        MIGRATION_4_5.migrate(raw)
        MIGRATION_5_6.migrate(raw)
        MIGRATION_6_7.migrate(raw)
        raw.execSQL(
            "INSERT INTO coverage_samples (timestampMs, sector, count, maxMi, medianSignalDbfs) " +
                "VALUES (1000, 'N', 3, 42.0, -12.0)",
        )
        legacy.close()

        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
            .build()

        runBlocking {
            // Pre-existing coverage_samples row survives — v12 only adds a new table.
            assertEquals(1, migrated.coverageSampleDao().allTimeBySector().size)

            migrated.altitudeSampleDao().insertAll(listOf(
                AltitudeSampleEntity(timestampMs = 2_000L, band = "BAND_3000_10000", count = 4),
                AltitudeSampleEntity(timestampMs = 3_000L, band = "BAND_3000_10000", count = 2),
            ))
            val totals = migrated.altitudeSampleDao().allTimeByBand()
            assertEquals(1, totals.size)
            assertEquals(6, totals.single().count)
        }
        migrated.close()
    }
}
