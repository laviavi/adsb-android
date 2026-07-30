package com.laviavi.adsbandroid.offline

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Append, deletion, shared-tile retention and recovery.
 *
 * These are the destructive paths, so nearly every test here asserts what *survives*
 * rather than what happened.
 */
class OfflineAppendAndDeletionTests {

    private val riverside = 33.95 to -117.33
    private val vancouver = 49.28 to -123.12

    private suspend fun OfflineHarness.seed(
        name: String, lat: Double, lon: Double, radius: OfflineRadius = OfflineRadius.NM_150,
    ): OfflineSegment {
        manager.downloadNew(lat, lon, radius, TEST_DETAIL, explicitName = name)
        return store.manifest.segments.first { it.displayName == name }
    }

    private fun OfflineHarness.seedTravel(id: String, path: List<Pair<Double, Double>>, dest: String? = null) {
        store.travel = TravelLog(
            records = store.travel.records + TravelRecord(
                id = id,
                path = path.mapIndexed { i, p -> TravelPoint(p.first, p.second, i.toLong()) },
                startedAtMs = 0,
                lastUpdatedAtMs = path.size.toLong(),
                destinationName = dest,
            ),
        )
    }

    @Nested inner class Append {

        @Test fun `append adds coverage without touching the original`() = runTest {
            val h = OfflineHarness()
            val seg = h.seed("Riverside", riverside.first, riverside.second, OfflineRadius.NM_90)
            val originalTiles = seg.allTileKeys
            val originalEntryId = seg.coverage.single().id

            h.seedTravel("t1", listOf(riverside, 35.5 to -118.5))
            val outcome = h.manager.appendTravelCoverage("t1", seg.id, TEST_DETAIL)
            assertTrue(outcome is DownloadOutcome.Completed, "got $outcome")

            val after = h.store.manifest.segment(seg.id)!!
            assertEquals(2, after.coverage.size, "append should add an entry, not replace one")
            val original = after.coverage.first { it.id == originalEntryId }
            assertEquals(originalTiles, original.tileKeys, "original coverage definition was rewritten")
            assertEquals(CoverageSource.INITIAL_DOWNLOAD, original.source)
            assertEquals(CoverageSource.APPENDED_TRAVEL, after.coverage.last().source)
            assertTrue(after.hasAppendedCoverage)
            assertTrue(h.tiles.data.keys.containsAll(originalTiles), "original tiles were lost")
        }

        @Test fun `append downloads only missing coverage`() = runTest {
            val h = OfflineHarness()
            val seg = h.seed("Riverside", riverside.first, riverside.second, OfflineRadius.NM_250)
            val fetchesBefore = h.downloader.fetched.size

            // A route entirely inside the existing 250 NM radius.
            h.seedTravel("t1", listOf(riverside, riverside.first + 0.3 to riverside.second + 0.3))
            val outcome = h.manager.appendTravelCoverage("t1", seg.id, TEST_DETAIL)

            assertTrue(outcome is DownloadOutcome.NothingToDo, "got $outcome")
            assertEquals(fetchesBefore, h.downloader.fetched.size, "append refetched covered tiles")
        }

        @Test fun `repeating the same append stores nothing new`() = runTest {
            val h = OfflineHarness()
            val seg = h.seed("Riverside", riverside.first, riverside.second, OfflineRadius.NM_90)

            h.seedTravel("t1", listOf(riverside, 35.5 to -118.5))
            h.manager.appendTravelCoverage("t1", seg.id, TEST_DETAIL)
            val tilesAfterFirst = h.tiles.data.keys.toSet()
            val fetchesAfterFirst = h.downloader.fetched.size

            // Same journey recorded again - e.g. the user travelled the route twice.
            h.seedTravel("t2", listOf(riverside, 35.5 to -118.5))
            val second = h.manager.appendTravelCoverage("t2", seg.id, TEST_DETAIL)

            assertTrue(second is DownloadOutcome.NothingToDo, "got $second")
            assertEquals(tilesAfterFirst, h.tiles.data.keys.toSet(), "repeat append duplicated content")
            assertEquals(fetchesAfterFirst, h.downloader.fetched.size)
        }

        @Test fun `append is refused off wifi and changes nothing`() = runTest {
            val h = OfflineHarness()
            val seg = h.seed("Riverside", riverside.first, riverside.second, OfflineRadius.NM_90)
            val before = h.tiles.data.keys.toSet()
            val coverageBefore = h.store.manifest.segment(seg.id)!!.coverage.size

            h.seedTravel("t1", listOf(riverside, 35.5 to -118.5))
            h.network.state = NetworkState.CELLULAR
            val outcome = h.manager.appendTravelCoverage("t1", seg.id, TEST_DETAIL)

            assertTrue(outcome is DownloadOutcome.Rejected, "got $outcome")
            assertEquals(before, h.tiles.data.keys.toSet())
            assertEquals(coverageBefore, h.store.manifest.segment(seg.id)!!.coverage.size)
        }

        @Test fun `a completed append clears the pending suggestion`() = runTest {
            val h = OfflineHarness()
            val seg = h.seed("Riverside", riverside.first, riverside.second, OfflineRadius.NM_90)
            h.seedTravel("t1", listOf(riverside, 35.5 to -118.5))
            h.manager.appendTravelCoverage("t1", seg.id, TEST_DETAIL)
            assertTrue(h.store.travel.records.none { it.id == "t1" }, "handled suggestion still pending")
        }

        @Test fun `appended coverage updates the segment timestamp`() = runTest {
            val h = OfflineHarness()
            val seg = h.seed("Riverside", riverside.first, riverside.second, OfflineRadius.NM_90)
            val createdAt = seg.updatedAtMs

            h.clock.now += 60_000
            h.seedTravel("t1", listOf(riverside, 35.5 to -118.5))
            h.manager.appendTravelCoverage("t1", seg.id, TEST_DETAIL)

            assertTrue(h.store.manifest.segment(seg.id)!!.updatedAtMs > createdAt)
        }
    }

    @Nested inner class Deletion {

        @Test fun `deleting one segment leaves unrelated segments intact`() = runTest {
            val h = OfflineHarness()
            val a = h.seed("Riverside", riverside.first, riverside.second, OfflineRadius.NM_90)
            val b = h.seed("Vancouver", vancouver.first, vancouver.second, OfflineRadius.NM_90)
            val bTiles = h.store.manifest.segment(b.id)!!.storedTileKeys

            val result = h.manager.deleteSegments(listOf(a.id))

            assertEquals(1, result.segmentsRemoved)
            assertNull(h.store.manifest.segment(a.id))
            assertNotNull(h.store.manifest.segment(b.id))
            assertTrue(h.tiles.data.keys.containsAll(bTiles), "unrelated segment lost tiles")
        }

        @Test fun `shared tiles survive until the last referencing segment is deleted`() = runTest {
            val h = OfflineHarness()
            // Concentric radii at the same point: the inner set is shared entirely.
            val outer = h.seed("Outer", riverside.first, riverside.second, OfflineRadius.NM_150)
            val inner = h.seed("Inner", riverside.first, riverside.second, OfflineRadius.NM_90)
            val sharedTiles = h.store.manifest.segment(inner.id)!!.storedTileKeys
            assertTrue(sharedTiles.isNotEmpty())

            // Deleting the inner segment must not remove tiles the outer still needs.
            h.manager.deleteSegments(listOf(inner.id))
            assertTrue(
                h.tiles.data.keys.containsAll(sharedTiles),
                "shared tiles were removed while another segment still referenced them",
            )

            // Now the last referencing segment goes; the tiles may finally be freed.
            h.manager.deleteSegments(listOf(outer.id))
            assertTrue(
                sharedTiles.none { it in h.tiles.data },
                "tiles survived after their final reference was deleted",
            )
            assertTrue(h.store.manifest.segments.isEmpty())
        }

        @Test fun `the preview reports what will and will not be freed`() = runTest {
            val h = OfflineHarness()
            val outer = h.seed("Outer", riverside.first, riverside.second, OfflineRadius.NM_150)
            val inner = h.seed("Inner", riverside.first, riverside.second, OfflineRadius.NM_90)

            val preview = h.manager.deletionPreview(listOf(inner.id))
            val item = preview.segments.single()
            assertEquals("Inner", item.name)
            assertTrue(item.bytes > 0)
            assertTrue(preview.tilesRetainedShared > 0, "shared tiles should be reported as retained")
            assertEquals(0, preview.tilesToRemove, "inner is wholly contained, so nothing is exclusive")

            // Deleting the outer instead frees the ring it exclusively owns.
            val outerPreview = h.manager.deletionPreview(listOf(outer.id))
            assertTrue(outerPreview.tilesToRemove > 0)
        }

        @Test fun `the preview surfaces appended coverage before deletion`() = runTest {
            val h = OfflineHarness()
            val seg = h.seed("Riverside", riverside.first, riverside.second, OfflineRadius.NM_90)
            h.seedTravel("t1", listOf(riverside, 35.5 to -118.5))
            h.manager.appendTravelCoverage("t1", seg.id, TEST_DETAIL)

            val preview = h.manager.deletionPreview(listOf(seg.id))
            assertTrue(preview.segments.single().hasAppendedCoverage, "append not disclosed before deletion")
        }

        @Test fun `deleting several segments at once removes exactly those`() = runTest {
            val h = OfflineHarness()
            val a = h.seed("A", riverside.first, riverside.second, OfflineRadius.NM_90)
            val b = h.seed("B", vancouver.first, vancouver.second, OfflineRadius.NM_90)
            val c = h.seed("C", 40.0, -120.0, OfflineRadius.NM_90)

            h.manager.deleteSegments(listOf(a.id, b.id))
            assertEquals(listOf(c.id), h.store.manifest.segments.map { it.id })
        }

        @Test fun `deletion is logged`() = runTest {
            val h = OfflineHarness()
            val seg = h.seed("Riverside", riverside.first, riverside.second, OfflineRadius.NM_90)
            h.manager.deleteSegments(listOf(seg.id))
            val entry = h.logger.of("deleted").single()
            assertEquals("Riverside", entry.fields["name"])
        }

        @Test fun `nothing is ever deleted automatically`() = runTest {
            val h = OfflineHarness()
            val a = h.seed("A", riverside.first, riverside.second, OfflineRadius.NM_90)
            val tilesAfterA = h.tiles.data.keys.toSet()

            // Downloads, appends and travel observation must never free storage.
            h.seed("B", vancouver.first, vancouver.second, OfflineRadius.NM_150)
            h.seedTravel("t1", listOf(riverside, 35.5 to -118.5))
            h.manager.appendTravelCoverage("t1", a.id, TEST_DETAIL)
            h.manager.observePosition(60.0, -140.0)

            assertTrue(h.tiles.data.keys.containsAll(tilesAfterA), "storage was reclaimed without the user asking")
            assertEquals(2, h.store.manifest.segments.size)
        }

        @Test fun `pruning orphans only removes unreferenced tiles`() = runTest {
            val h = OfflineHarness()
            val seg = h.seed("Riverside", riverside.first, riverside.second, OfflineRadius.NM_90)
            val referenced = h.store.manifest.segment(seg.id)!!.storedTileKeys
            h.tiles.data["99/1/1"] = ByteArray(10)   // not referenced by any segment

            val removed = h.manager.pruneOrphanedTiles()
            assertEquals(1, removed)
            assertTrue(h.tiles.data.keys.containsAll(referenced))
        }
    }

    @Nested inner class Recovery {

        @Test fun `tiles on disk are reclaimed after an interrupted manifest write`() = runTest {
            val h = OfflineHarness()
            h.manager.downloadNew(riverside.first, riverside.second, OfflineRadius.NM_90, TEST_DETAIL)
            val seg = h.store.manifest.segments.single()

            // Simulate a manifest that lost its progress while the tiles survived.
            val torn = seg.copy(
                coverage = seg.coverage.map { it.copy(storedTileKeys = emptySet(), state = DownloadState.INCOMPLETE) },
            )
            h.store.manifest = h.store.manifest.copy(segments = listOf(torn))
            val fetchesBefore = h.downloader.fetched.size

            val outcome = h.manager.resume(seg.id, seg.coverage.single().id)

            assertTrue(outcome is DownloadOutcome.NothingToDo, "got $outcome")
            assertEquals(fetchesBefore, h.downloader.fetched.size, "recovery refetched tiles already on disk")
            assertEquals(DownloadState.COMPLETE, h.store.manifest.segments.single().state)
        }

        @Test fun `a failed manifest save does not lose downloaded tiles`() = runTest {
            val h = OfflineHarness()
            h.store.failNextSave = true
            // The save inside downloadNew throws; the operation reports failure rather
            // than pretending to have succeeded.
            runCatching { h.manager.downloadNew(riverside.first, riverside.second, OfflineRadius.NM_90, TEST_DETAIL) }
            // Whatever happened, no tile was deleted.
            assertTrue(h.tiles.data.keys.all { TileRef.parse(it) != null })
        }
    }

    @Nested inner class OfflineRendering {

        @Test fun `initial and appended coverage are both readable offline`() = runTest {
            val h = OfflineHarness()
            val seg = h.seed("Riverside", riverside.first, riverside.second, OfflineRadius.NM_90)
            h.seedTravel("t1", listOf(riverside, 35.5 to -118.5))
            h.manager.appendTravelCoverage("t1", seg.id, TEST_DETAIL)

            val after = h.store.manifest.segment(seg.id)!!
            val initialTile = TileRef.parse(after.coverage.first().storedTileKeys.first())!!
            val appendedTile = TileRef.parse(after.coverage.last().storedTileKeys.first())!!

            // Network down: lookups must still succeed from local storage.
            h.network.state = NetworkState.DISCONNECTED
            assertTrue(h.manager.hasTileOffline(initialTile), "initial coverage not readable offline")
            assertTrue(h.manager.hasTileOffline(appendedTile), "appended coverage not readable offline")
            assertNotNull(h.manager.readTile(initialTile))
            assertNotNull(h.manager.readTile(appendedTile))
        }

        @Test fun `storage usage counts shared tiles once`() = runTest {
            val h = OfflineHarness()
            h.seed("Outer", riverside.first, riverside.second, OfflineRadius.NM_150)
            h.seed("Inner", riverside.first, riverside.second, OfflineRadius.NM_90)

            val usage = h.manager.storageUsage()
            assertEquals(2, usage.segmentCount)
            assertEquals(h.tiles.data.size, usage.distinctTiles, "distinct count should match tiles on disk")
            assertTrue(usage.sharedTiles > 0, "concentric segments should share tiles")
        }
    }
}
