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
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
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
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .build()

        val seen = runBlocking { migrated.aircraftSeenDao().observeAll().first() }
        assertEquals(1, seen.size)
        assertNull(
            "corrupted pre-fix data must not survive as a plausible-looking string",
            seen.single().squawk,
        )
        migrated.close()
    }
}
