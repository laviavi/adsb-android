package com.laviavi.adsbandroid.offline

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Integration tests for the real file-backed adapters.
 *
 * The core suite proves the manager's logic against fakes; these prove the storage
 * layer actually round-trips, survives a torn write, and recovers — the failures a
 * fake cannot reproduce because it never touches a disk.
 */
class FileStorageIntegrationTests {

    @Test fun `manifest round-trips through JSON`(@TempDir dir: File) {
        val store = FileManifestStore(dir)
        assertEquals(OfflineManifest.EMPTY, store.load(), "a fresh install must start empty")

        val segment = OfflineSegment(
            id = "seg-1",
            displayName = "Riverside",
            locationName = "Riverside",
            centerLat = 33.95,
            centerLon = -117.33,
            requestedRadiusNm = 150,
            createdAtMs = 1_000,
            updatedAtMs = 2_000,
            coverage = listOf(
                CoverageEntry(
                    id = "cov-1",
                    source = CoverageSource.INITIAL_DOWNLOAD,
                    tileKeys = setOf("8/43/103", "8/44/103"),
                    minZoom = 8, maxZoom = 11,
                    createdAtMs = 1_000,
                    state = DownloadState.COMPLETE,
                    storedTileKeys = setOf("8/43/103", "8/44/103"),
                    bytesStored = 2_048,
                    radiusNm = 150, centerLat = 33.95, centerLon = -117.33,
                ),
            ),
        )
        store.save(OfflineManifest(segments = listOf(segment)))

        val reloaded = FileManifestStore(dir).load()
        assertEquals(1, reloaded.segments.size)
        val s = reloaded.segments.single()
        assertEquals("Riverside", s.displayName)
        assertEquals(150, s.requestedRadiusNm)
        assertEquals(setOf("8/43/103", "8/44/103"), s.storedTileKeys)
        assertEquals(2_048L, s.bytesStored)
        assertEquals(DownloadState.COMPLETE, s.state)
    }

    @Test fun `travel log round-trips`(@TempDir dir: File) {
        val store = FileManifestStore(dir)
        store.saveTravelLog(
            TravelLog(
                records = listOf(
                    TravelRecord(
                        id = "t1",
                        path = listOf(TravelPoint(33.9, -117.3, 1), TravelPoint(40.0, -120.0, 2)),
                        startedAtMs = 1, lastUpdatedAtMs = 2,
                        destinationName = "Somewhere",
                    ),
                ),
            ),
        )
        val back = FileManifestStore(dir).loadTravelLog()
        assertEquals(1, back.records.size)
        assertEquals("Somewhere", back.records.single().destinationName)
        assertEquals(2, back.records.single().path.size)
    }

    @Test fun `a leftover temp file from an interrupted write is discarded`(@TempDir dir: File) {
        val store = FileManifestStore(dir)
        store.save(OfflineManifest(segments = emptyList()))

        // Simulate a process death after the temp file was written but before rename.
        File(dir, "offline/manifest.json.tmp").writeText("{ this is not valid json")

        // The committed file is still good, so load must return it, not crash.
        val loaded = FileManifestStore(dir).load()
        assertEquals(0, loaded.segments.size)
        assertFalse(File(dir, "offline/manifest.json.tmp").exists(), "stale temp file was not cleaned up")
    }

    @Test fun `a corrupt manifest degrades to empty rather than crashing`(@TempDir dir: File) {
        File(dir, "offline").mkdirs()
        File(dir, "offline/manifest.json").writeText("not json at all")
        // Losing the index is bad, but taking the app down with it is worse — and the
        // tiles are still on disk for pruneOrphanedTiles to reclaim.
        assertEquals(OfflineManifest.EMPTY, FileManifestStore(dir).load())
    }

    @Test fun `tile store round-trips and reports sizes`(@TempDir dir: File) {
        val tiles = FileTileStore(dir)
        assertFalse(tiles.has("8/43/103"))

        tiles.write("8/43/103", ByteArray(500) { 7 })
        tiles.write("9/86/206", ByteArray(300) { 3 })

        assertTrue(tiles.has("8/43/103"))
        assertEquals(500, tiles.read("8/43/103")!!.size)
        assertEquals(setOf("8/43/103", "9/86/206"), tiles.storedKeys())
        assertEquals(800L, tiles.sizeOf(listOf("8/43/103", "9/86/206")))
    }

    @Test fun `tile deletion removes only the named keys`(@TempDir dir: File) {
        val tiles = FileTileStore(dir)
        tiles.write("8/1/1", ByteArray(10))
        tiles.write("8/2/2", ByteArray(10))
        tiles.write("8/3/3", ByteArray(10))

        assertEquals(2, tiles.delete(listOf("8/1/1", "8/2/2")))
        assertEquals(setOf("8/3/3"), tiles.storedKeys())
    }

    @Test fun `no partial tile is ever visible as stored`(@TempDir dir: File) {
        val tiles = FileTileStore(dir)
        tiles.write("8/43/103", ByteArray(500) { 1 })
        // A `.part` file left by an interrupted write must not be enumerated as a tile,
        // or it would count as present and never be re-fetched.
        File(dir, "offline/tiles/8/43").listFiles()?.forEach {
            assertFalse(it.name.endsWith(".part"), "a temp file survived a successful write")
        }
        assertEquals(setOf("8/43/103"), tiles.storedKeys())
    }

    @Test fun `manager works end to end against real files`(@TempDir dir: File) = runTest {
        val tiles = FileTileStore(dir)
        val manifest = FileManifestStore(dir)
        val source = FakeBytesDownloader()
        val manager = OfflineMapManager(
            store = manifest,
            tiles = tiles,
            downloader = source,
            eligibility = object : NetworkEligibility {
                override fun currentState() = NetworkState.WIFI_UNMETERED
            },
            clock = object : OfflineClock {
                override fun nowMs() = 1_000L
                override fun todayStamp() = "2026-07-29"
            },
            ids = object : IdGenerator {
                var n = 0
                override fun newId() = "id-${++n}"
            },
            namer = object : LocationNamer {
                override suspend fun nameFor(lat: Double, lon: Double) = "Riverside"
            },
        )

        val outcome = manager.downloadNew(33.95, -117.33, OfflineRadius.NM_90, MapDetail.STANDARD)
        assertTrue(outcome is DownloadOutcome.Completed, "got $outcome")

        // Reload from disk with a fresh manager to prove nothing lived only in memory.
        val reloaded = OfflineMapManager(
            store = FileManifestStore(dir),
            tiles = FileTileStore(dir),
            downloader = source,
            eligibility = object : NetworkEligibility {
                override fun currentState() = NetworkState.DISCONNECTED
            },
            clock = SystemOfflineClock(),
            ids = UuidGenerator(),
        )
        val seg = reloaded.segments().single()
        assertEquals("Riverside", seg.displayName)
        assertEquals(DownloadState.COMPLETE, seg.state)
        // Offline lookup works with the network down.
        val tile = TileRef.parse(seg.storedTileKeys.first())!!
        assertTrue(reloaded.hasTileOffline(tile))
        assertNotNull(reloaded.readTile(tile))
    }

    private class FakeBytesDownloader : TileDownloader {
        override suspend fun fetch(tile: TileRef) = ByteArray(64) { 9 }
    }
}
