package com.laviavi.adsbandroid.offline

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** Location naming, duplicate-safe naming, travel tracking and append targeting. */
class OfflineNamingAndTravelTests {

    private val riverside = 33.95 to -117.33
    private val vancouver = 49.28 to -123.12
    private val penticton = 49.50 to -119.59

    @Nested inner class Naming {

        @Test fun `a new segment is named after where it was created`() = runTest {
            val h = OfflineHarness(namer = FixedNamer("Riverside"))
            h.manager.downloadNew(riverside.first, riverside.second, OfflineRadius.NM_90, TEST_DETAIL)
            assertEquals("Riverside", h.store.manifest.segments.single().displayName)
        }

        @Test fun `a duplicate location gets a counter suffix without overwriting`() = runTest {
            val h = OfflineHarness(namer = FixedNamer("Riverside"))
            h.manager.downloadNew(riverside.first, riverside.second, OfflineRadius.NM_90, TEST_DETAIL)
            h.manager.downloadNew(34.5, -118.0, OfflineRadius.NM_90, TEST_DETAIL)
            h.manager.downloadNew(35.0, -118.5, OfflineRadius.NM_90, TEST_DETAIL)

            val names = h.store.manifest.segments.map { it.displayName }
            assertEquals(listOf("Riverside", "Riverside (2)", "Riverside (3)"), names)
            assertEquals(3, h.store.manifest.segments.size, "a collision overwrote a segment")
            assertEquals(3, h.store.manifest.segments.map { it.id }.toSet().size, "ids must stay unique")
        }

        @Test fun `naming falls back to the date form when counters are taken`() {
            val existing = buildList {
                add("Riverside")
                for (n in 2..999) add("Riverside ($n)")
            }
            val name = SegmentNaming.uniqueName("Riverside", existing, dateStamp = "2026-07-29")
            assertEquals("Riverside - 2026-07-29", name)
        }

        @Test fun `an unresolvable location still produces a distinguishable name`() = runTest {
            val h = OfflineHarness(namer = FixedNamer(null))
            h.manager.downloadNew(riverside.first, riverside.second, OfflineRadius.NM_90, TEST_DETAIL)
            val name = h.store.manifest.segments.single().displayName
            assertTrue(name.contains("33.95"), "coordinate fallback expected, got $name")
            assertTrue(name.contains("N") && name.contains("W"))
        }

        @Test fun `blank location names do not produce a blank segment`() {
            assertEquals(SegmentNaming.UNKNOWN_LOCATION, SegmentNaming.uniqueName("   ", emptyList()))
        }

        @Test fun `an explicit name overrides geocoding`() = runTest {
            val h = OfflineHarness(namer = FixedNamer("Riverside"))
            h.manager.downloadNew(
                riverside.first, riverside.second, OfflineRadius.NM_90, TEST_DETAIL,
                explicitName = "Home base",
            )
            assertEquals("Home base", h.store.manifest.segments.single().displayName)
        }
    }

    @Nested inner class TravelTracking {

        @Test fun `movement inside coverage records nothing`() = runTest {
            val h = OfflineHarness()
            h.manager.downloadNew(riverside.first, riverside.second, OfflineRadius.NM_150, TEST_DETAIL)
            h.manager.observePosition(riverside.first + 0.1, riverside.second + 0.1)
            assertTrue(h.store.travel.records.isEmpty(), "in-coverage movement opened a travel record")
        }

        @Test fun `movement outside coverage opens a record without downloading`() = runTest {
            val h = OfflineHarness()
            h.manager.downloadNew(riverside.first, riverside.second, OfflineRadius.NM_90, TEST_DETAIL)
            val fetchesBefore = h.downloader.fetched.size

            h.network.state = NetworkState.CELLULAR      // travelling on cellular
            h.manager.observePosition(vancouver.first, vancouver.second)

            assertEquals(1, h.store.travel.records.size)
            assertEquals(fetchesBefore, h.downloader.fetched.size, "travel triggered a download")
        }

        @Test fun `nearby samples are dropped so the record stays small`() = runTest {
            val h = OfflineHarness()
            h.manager.downloadNew(riverside.first, riverside.second, OfflineRadius.NM_90, TEST_DETAIL)
            h.manager.observePosition(vancouver.first, vancouver.second)
            repeat(20) { i ->
                // ~0.001 degrees apart: far below the 5 nm sampling floor.
                h.manager.observePosition(vancouver.first + i * 0.001, vancouver.second)
            }
            assertEquals(1, h.store.travel.records.single().path.size, "sampling floor not applied")
        }

        @Test fun `distant samples extend the path`() = runTest {
            val h = OfflineHarness()
            h.manager.downloadNew(riverside.first, riverside.second, OfflineRadius.NM_90, TEST_DETAIL)
            h.manager.observePosition(40.0, -120.0)
            h.manager.observePosition(44.0, -121.0)
            h.manager.observePosition(vancouver.first, vancouver.second)
            assertEquals(3, h.store.travel.records.single().path.size)
        }

        @Test fun `not now keeps the record but stops it prompting`() = runTest {
            val h = OfflineHarness()
            h.manager.downloadNew(riverside.first, riverside.second, OfflineRadius.NM_90, TEST_DETAIL)
            h.manager.observePosition(40.0, -120.0)
            h.manager.observePosition(vancouver.first, vancouver.second)
            val rec = h.store.travel.records.single()

            h.manager.deferTravelSuggestion(rec.id)
            assertTrue(h.manager.pendingTravelSuggestions().isEmpty(), "deferred record still prompting")
            assertEquals(1, h.store.travel.records.size, "not now must not delete the record")
        }

        @Test fun `dismiss removes the suggestion and no map data`() = runTest {
            val h = OfflineHarness()
            h.manager.downloadNew(riverside.first, riverside.second, OfflineRadius.NM_90, TEST_DETAIL)
            val tilesBefore = h.tiles.data.keys.toSet()
            h.manager.observePosition(40.0, -120.0)
            h.manager.observePosition(vancouver.first, vancouver.second)
            val rec = h.store.travel.records.single()

            h.manager.dismissTravelSuggestion(rec.id)
            assertTrue(h.store.travel.records.isEmpty())
            assertEquals(tilesBefore, h.tiles.data.keys.toSet(), "dismiss touched map data")
        }
    }

    @Nested inner class AppendTargetSelection {

        /** Builds a completed segment centred on a point, without going through the manager. */
        private suspend fun OfflineHarness.seed(
            name: String, lat: Double, lon: Double, radius: OfflineRadius = OfflineRadius.NM_150,
        ): OfflineSegment {
            manager.downloadNew(lat, lon, radius, TEST_DETAIL, explicitName = name)
            return store.manifest.segments.first { it.displayName == name }
        }

        @Test fun `the region containing the destination wins`() = runTest {
            val h = OfflineHarness()
            val riversideSeg = h.seed("Riverside", riverside.first, riverside.second)
            val vancouverSeg = h.seed("Vancouver", vancouver.first, vancouver.second)

            val record = TravelRecord(
                id = "t1",
                path = listOf(
                    TravelPoint(riverside.first, riverside.second, 0),
                    TravelPoint(vancouver.first, vancouver.second, 1),
                ),
                startedAtMs = 0, lastUpdatedAtMs = 1,
                originSegmentId = riversideSeg.id,
            )
            val target = AppendTargeting.choose(record, h.store.manifest.segments, TEST_DETAIL.zoomRange)
            assertTrue(target is AppendTarget.Segment, "got $target")
            assertEquals(vancouverSeg.id, (target as AppendTarget.Segment).segmentId)
            assertEquals(AppendTarget.Reason.CONTAINS_DESTINATION, target.reason)
        }

        @Test fun `with no containing region the greatest route overlap wins`() = runTest {
            val h = OfflineHarness()
            val near = h.seed("Near", 40.0, -120.0, OfflineRadius.NM_250)
            h.seed("Far", 33.0, -100.0, OfflineRadius.NM_90)

            // The destination sits well clear of both regions, so rule 1 cannot fire
            // and the decision has to fall through to route overlap.
            val destination = TravelPoint(53.0, -124.0, 1)
            h.store.manifest.segments.forEach {
                assertFalse(
                    it.contains(destination.lat, destination.lon),
                    "${it.displayName} contains the destination; this test needs it not to",
                )
            }

            val record = TravelRecord(
                id = "t2",
                path = listOf(TravelPoint(40.2, -120.2, 0), destination),
                startedAtMs = 0, lastUpdatedAtMs = 1,
            )
            val target = AppendTargeting.choose(record, h.store.manifest.segments, TEST_DETAIL.zoomRange)
            assertTrue(target is AppendTarget.Segment, "got $target")
            assertEquals(near.id, (target as AppendTarget.Segment).segmentId)
            assertEquals(AppendTarget.Reason.GREATEST_ROUTE_OVERLAP, target.reason)
        }

        @Test fun `no suitable region offers a new segment rather than an unrelated one`() = runTest {
            val h = OfflineHarness()
            h.seed("Riverside", riverside.first, riverside.second, OfflineRadius.NM_90)

            val record = TravelRecord(
                id = "t3",
                path = listOf(TravelPoint(-33.86, 151.2, 0), TravelPoint(-33.9, 151.3, 1)),
                startedAtMs = 0, lastUpdatedAtMs = 1,
                destinationName = "Sydney",
            )
            val target = AppendTargeting.choose(record, h.store.manifest.segments, TEST_DETAIL.zoomRange)
            assertTrue(target is AppendTarget.CreateNew, "got $target")
            assertEquals("Sydney", (target as AppendTarget.CreateNew).suggestedName)
        }

        @Test fun `an empty library offers a new segment`() {
            val record = TravelRecord(
                id = "t4",
                path = listOf(TravelPoint(49.0, -123.0, 0)),
                startedAtMs = 0, lastUpdatedAtMs = 0,
                destinationName = "Vancouver",
            )
            val target = AppendTargeting.choose(record, emptyList(), TEST_DETAIL.zoomRange)
            assertTrue(target is AppendTarget.CreateNew)
        }

        @Test fun `a genuine tie asks the user instead of guessing`() = runTest {
            val h = OfflineHarness()
            // Two identical regions at the same place: any overlap is exactly equal.
            val a = h.seed("A", penticton.first, penticton.second, OfflineRadius.NM_150)
            val b = h.seed("B", penticton.first, penticton.second, OfflineRadius.NM_150)

            val record = TravelRecord(
                id = "t5",
                path = listOf(
                    TravelPoint(penticton.first, penticton.second, 0),
                    TravelPoint(penticton.first + 0.05, penticton.second + 0.05, 1),
                ),
                startedAtMs = 0, lastUpdatedAtMs = 1,
            )
            val target = AppendTargeting.choose(record, h.store.manifest.segments, TEST_DETAIL.zoomRange)
            assertTrue(target is AppendTarget.AmbiguousChoice, "got $target")
            assertEquals(
                listOf(a.id, b.id).sorted(),
                (target as AppendTarget.AmbiguousChoice).candidateSegmentIds,
            )
        }

        @Test fun `the append decision is logged with its reason`() = runTest {
            val h = OfflineHarness()
            h.seed("Riverside", riverside.first, riverside.second)
            h.store.travel = TravelLog(
                records = listOf(
                    TravelRecord(
                        id = "t6",
                        path = listOf(
                            TravelPoint(riverside.first, riverside.second, 0),
                            TravelPoint(riverside.first + 0.2, riverside.second + 0.2, 1),
                        ),
                        startedAtMs = 0, lastUpdatedAtMs = 1,
                    ),
                ),
            )
            h.manager.chooseAppendTarget("t6", TEST_DETAIL)
            val decision = h.logger.of("append").single()
            assertEquals("t6", decision.fields["record"])
            assertNotNull(decision.fields["reason"])
        }
    }
}
