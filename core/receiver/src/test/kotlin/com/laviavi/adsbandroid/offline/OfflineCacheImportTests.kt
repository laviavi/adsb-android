package com.laviavi.adsbandroid.offline

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Importing coverage the device already holds.
 *
 * The distinguishing property from a download is that it moves no bytes off-device,
 * so it must work on any connection — including none — while still producing a
 * segment identical in every other respect.
 */
class OfflineCacheImportTests {

    private val lat = 33.95
    private val lon = -117.33

    /** A cache holding a given set of tiles. */
    private class FakeCache(private val tiles: Set<TileRef>, private val bytes: Int = 80) : LocalTileSource {
        val fetched = mutableListOf<TileRef>()
        override fun availableTiles(): Set<TileRef> = tiles
        override suspend fun fetch(tile: TileRef): ByteArray? {
            fetched += tile
            return if (tile in tiles) ByteArray(bytes) { 5 } else null
        }
    }

    private fun cacheCovering(radiusNm: Int, fraction: Double = 1.0): FakeCache {
        val all = TileGeometry.tilesForRadius(lat, lon, radiusNm, TEST_DETAIL.zoomRange).toList()
        return FakeCache(all.take((all.size * fraction).toInt()).toSet())
    }

    @Test fun `import creates a named segment from cached tiles`() = runTest {
        val h = OfflineHarness()
        val cache = cacheCovering(90)

        val outcome = h.manager.importFromCache(lat, lon, OfflineRadius.NM_90, cache, TEST_DETAIL)

        assertTrue(outcome is DownloadOutcome.Completed, "got $outcome")
        val seg = h.store.manifest.segments.single()
        assertEquals("Riverside", seg.displayName)
        assertEquals(90, seg.requestedRadiusNm)
        assertEquals(DownloadState.COMPLETE, seg.state)
        assertEquals(CoverageSource.INITIAL_DOWNLOAD, seg.coverage.single().source)
        assertTrue(h.tiles.data.isNotEmpty())
    }

    @Test fun `import works with no network at all`() = runTest {
        // The Wi-Fi rule protects a data allowance; copying local bytes spends none.
        listOf(
            NetworkState.DISCONNECTED,
            NetworkState.CELLULAR,
            NetworkState.WIFI_METERED,
            NetworkState.UNKNOWN,
        ).forEach { state ->
            val h = OfflineHarness(network = FakeNetwork(state))
            val outcome = h.manager.importFromCache(
                lat, lon, OfflineRadius.NM_90, cacheCovering(90), TEST_DETAIL,
            )
            assertTrue(outcome is DownloadOutcome.Completed, "$state gave $outcome")
            assertTrue(h.tiles.data.isNotEmpty(), "$state stored nothing")
        }
    }

    @Test fun `import never touches the network downloader`() = runTest {
        val h = OfflineHarness(network = FakeNetwork(NetworkState.DISCONNECTED))
        h.manager.importFromCache(lat, lon, OfflineRadius.NM_90, cacheCovering(90), TEST_DETAIL)
        assertTrue(h.downloader.fetched.isEmpty(), "import used the network downloader")
    }

    @Test fun `a partially cached area imports what exists and stays resumable`() = runTest {
        val h = OfflineHarness()
        val all = TileGeometry.tilesForRadius(lat, lon, 90, TEST_DETAIL.zoomRange)
        val cache = cacheCovering(90, fraction = 0.5)

        val outcome = h.manager.importFromCache(lat, lon, OfflineRadius.NM_90, cache, TEST_DETAIL)

        assertTrue(outcome is DownloadOutcome.Completed, "got $outcome")
        val seg = h.store.manifest.segments.single()
        // Only the cached half is claimed as coverage — the segment does not pretend
        // to hold areas that were never viewed.
        assertTrue(seg.allTileKeys.size < all.size, "segment claimed uncached coverage")
        assertEquals(seg.allTileKeys.size, h.tiles.data.size)
    }

    @Test fun `importing an unviewed area fails with a useful message and creates nothing`() = runTest {
        val h = OfflineHarness()
        val emptyCache = FakeCache(emptySet())

        val outcome = h.manager.importFromCache(lat, lon, OfflineRadius.NM_90, emptyCache, TEST_DETAIL)

        assertTrue(outcome is DownloadOutcome.Failed, "got $outcome")
        assertTrue((outcome as DownloadOutcome.Failed).reason.contains("viewed"))
        assertTrue(h.store.manifest.segments.isEmpty(), "a failed import left a segment behind")
        assertTrue(h.tiles.data.isEmpty())
    }

    @Test fun `repeating an import stores nothing new`() = runTest {
        val h = OfflineHarness()
        val cache = cacheCovering(90)
        h.manager.importFromCache(lat, lon, OfflineRadius.NM_90, cache, TEST_DETAIL)
        val after = h.tiles.data.keys.toSet()

        h.manager.importFromCache(lat, lon, OfflineRadius.NM_90, cache, TEST_DETAIL, explicitName = "Second")

        assertEquals(after, h.tiles.data.keys.toSet(), "repeat import duplicated stored content")
        assertEquals(2, h.store.manifest.segments.size, "the second segment should still exist")
        // Both segments reference the same tiles, so deleting one must keep them.
        val first = h.store.manifest.segments.first()
        h.manager.deleteSegments(listOf(first.id))
        assertEquals(after, h.tiles.data.keys.toSet(), "shared tiles removed while still referenced")
    }

    @Test fun `import estimate reports only what is both cached and not already saved`() = runTest {
        val h = OfflineHarness()
        val cache = cacheCovering(90, fraction = 0.5)

        val before = h.manager.estimateImport(lat, lon, OfflineRadius.NM_90, cache, TEST_DETAIL)
        assertTrue(before.newTiles > 0)
        assertTrue(before.newTiles < before.totalTiles, "half-cached area should not report full coverage")

        h.manager.importFromCache(lat, lon, OfflineRadius.NM_90, cache, TEST_DETAIL)
        val after = h.manager.estimateImport(lat, lon, OfflineRadius.NM_90, cache, TEST_DETAIL)
        assertEquals(0, after.newTiles, "already-imported tiles still counted as new")
    }

    @Test fun `imported coverage is readable offline and survives deletion of an unrelated segment`() = runTest {
        val h = OfflineHarness()
        h.manager.importFromCache(lat, lon, OfflineRadius.NM_90, cacheCovering(90), TEST_DETAIL)
        val imported = h.store.manifest.segments.single()

        h.manager.downloadNew(49.28, -123.12, OfflineRadius.NM_90, TEST_DETAIL, explicitName = "Vancouver")
        val vancouver = h.store.manifest.segments.first { it.displayName == "Vancouver" }
        h.manager.deleteSegments(listOf(vancouver.id))

        h.network.state = NetworkState.DISCONNECTED
        val tile = TileRef.parse(imported.storedTileKeys.first())!!
        assertTrue(h.manager.hasTileOffline(tile), "imported coverage lost when another segment was deleted")
        assertNotNull(h.manager.readTile(tile))
    }

    @Test fun `travel coverage can be appended to an imported segment`() = runTest {
        val h = OfflineHarness()
        h.manager.importFromCache(lat, lon, OfflineRadius.NM_90, cacheCovering(90), TEST_DETAIL)
        val seg = h.store.manifest.segments.single()

        h.store.travel = TravelLog(
            records = listOf(
                TravelRecord(
                    id = "t1",
                    path = listOf(TravelPoint(lat, lon, 0), TravelPoint(35.5, -118.5, 1)),
                    startedAtMs = 0, lastUpdatedAtMs = 1,
                ),
            ),
        )
        val outcome = h.manager.appendTravelCoverage("t1", seg.id, TEST_DETAIL)

        assertTrue(outcome is DownloadOutcome.Completed, "got $outcome")
        val after = h.store.manifest.segment(seg.id)!!
        assertTrue(after.hasAppendedCoverage)
        assertEquals(2, after.coverage.size, "append replaced the imported coverage")
    }
}
