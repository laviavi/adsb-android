package com.laviavi.adsbandroid.offline

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Wi-Fi gating, interruption, resume and idempotency.
 *
 * The recurring assertion in this file is that nothing is ever lost: a refused
 * download leaves no trace, an interrupted one keeps every validated tile, and a
 * repeat costs only what is missing.
 */
class OfflineDownloadTests {

    private val lat = 33.95
    private val lon = -117.33

    @Nested inner class NetworkGating {

        @Test fun `download proceeds on unmetered wifi`() = runTest {
            val h = OfflineHarness()
            val outcome = h.manager.downloadNew(lat, lon, OfflineRadius.NM_90, TEST_DETAIL)
            assertTrue(outcome is DownloadOutcome.Completed, "got $outcome")
            assertTrue(h.tiles.data.isNotEmpty())
        }

        @Test fun `every non-wifi state is refused and downloads nothing`() = runTest {
            listOf(
                NetworkState.CELLULAR,
                NetworkState.WIFI_METERED,
                NetworkState.DISCONNECTED,
                NetworkState.UNKNOWN,
                NetworkState.OTHER,
            ).forEach { state ->
                val h = OfflineHarness(network = FakeNetwork(state))
                val outcome = h.manager.downloadNew(lat, lon, OfflineRadius.NM_90, TEST_DETAIL)

                assertTrue(outcome is DownloadOutcome.Rejected, "$state should be rejected, got $outcome")
                // Requirement 8: not started, not queued, not partially begun.
                assertTrue(h.tiles.data.isEmpty(), "$state wrote tiles")
                assertTrue(h.downloader.fetched.isEmpty(), "$state hit the network")
                assertTrue(h.store.manifest.segments.isEmpty(), "$state created a segment")
            }
        }

        @Test fun `rejection explains the wifi-only rule in plain language`() {
            listOf(NetworkState.CELLULAR, NetworkState.DISCONNECTED, NetworkState.UNKNOWN).forEach { state ->
                val r = OfflineDownloadPolicy.evaluate(state)
                assertTrue(r is EligibilityResult.Ineligible)
                val msg = (r as EligibilityResult.Ineligible).reason
                assertTrue(msg.contains("Wi-Fi"), "message for $state should mention Wi-Fi: $msg")
                listOf("transport", "NetworkCapabilities", "metered=", "null").forEach { jargon ->
                    assertFalse(msg.contains(jargon), "message for $state leaks jargon: $msg")
                }
            }
        }

        @Test fun `metered wifi is treated as ineligible`() {
            assertFalse(OfflineDownloadPolicy.isDownloadAllowed(NetworkState.WIFI_METERED))
            assertTrue(OfflineDownloadPolicy.isDownloadAllowed(NetworkState.WIFI_UNMETERED))
        }

        @Test fun `eligibility is logged for every check`() = runTest {
            val h = OfflineHarness(network = FakeNetwork(NetworkState.CELLULAR))
            h.manager.downloadNew(lat, lon, OfflineRadius.NM_90, TEST_DETAIL)
            assertTrue(h.logger.of("eligibility").isNotEmpty())
        }
    }

    @Nested inner class WifiLossDuringDownload {

        @Test fun `losing wifi pauses and keeps everything already downloaded`() = runTest {
            val h = OfflineHarness()
            // Allow the initial gate plus one batch, then drop to cellular.
            h.network.afterCalls = 2
            h.network.thenState = NetworkState.CELLULAR

            val outcome = h.manager.downloadNew(lat, lon, OfflineRadius.NM_250, MapDetail.DETAILED)

            assertTrue(outcome is DownloadOutcome.Paused, "got $outcome")
            val paused = outcome as DownloadOutcome.Paused
            assertTrue(paused.reason.contains("Wi-Fi"))
            // Validated content preserved, not rolled back.
            assertTrue(h.tiles.data.isNotEmpty(), "paused download discarded its tiles")
            assertTrue(paused.stored < paused.total, "should not have finished")

            val seg = h.store.manifest.segments.single()
            assertEquals(DownloadState.PAUSED, seg.state)
            assertEquals(h.tiles.data.size, seg.storedTileKeys.size)
        }

        @Test fun `wifi is rechecked per batch, not only at the start`() = runTest {
            val h = OfflineHarness()
            h.network.afterCalls = 2
            h.network.thenState = NetworkState.CELLULAR
            h.manager.downloadNew(lat, lon, OfflineRadius.NM_250, MapDetail.DETAILED)
            // More than the single up-front check means per-batch re-checking happened.
            assertTrue(h.network.checks > 2, "expected repeated checks, saw ${h.network.checks}")
            assertTrue(h.logger.of("eligibility").any { it.fields["op"] == "batch" })
        }

        @Test fun `pre-existing map data is untouched when wifi drops`() = runTest {
            val h = OfflineHarness()
            // An unrelated segment already on disk.
            h.manager.downloadNew(49.28, -123.12, OfflineRadius.NM_90, TEST_DETAIL)
            val existingTiles = h.tiles.data.keys.toSet()
            val existingSegment = h.store.manifest.segments.single()

            h.network.checks = 0
            h.network.afterCalls = 2
            h.network.thenState = NetworkState.DISCONNECTED
            h.manager.downloadNew(lat, lon, OfflineRadius.NM_250, MapDetail.DETAILED)

            assertTrue(h.tiles.data.keys.containsAll(existingTiles), "existing tiles were removed")
            val stillThere = h.store.manifest.segment(existingSegment.id)
            assertNotNull(stillThere)
            assertEquals(DownloadState.COMPLETE, stillThere!!.state)
        }
    }

    @Nested inner class Resume {

        @Test fun `resume after wifi returns finishes the download without refetching`() = runTest {
            val h = OfflineHarness()
            h.network.afterCalls = 2
            h.network.thenState = NetworkState.CELLULAR
            val first = h.manager.downloadNew(lat, lon, OfflineRadius.NM_250, MapDetail.DETAILED)
            assertTrue(first is DownloadOutcome.Paused)

            val storedAfterPause = h.tiles.data.keys.toSet()
            val fetchesBefore = h.downloader.fetched.size

            // Wi-Fi back.
            h.network.afterCalls = null
            h.network.state = NetworkState.WIFI_UNMETERED
            val seg = h.store.manifest.segments.single()
            val cov = seg.coverage.single()
            val second = h.manager.resume(seg.id, cov.id)

            assertTrue(second is DownloadOutcome.Completed, "got $second")
            assertEquals(DownloadState.COMPLETE, h.store.manifest.segments.single().state)
            // Nothing already on disk was requested a second time.
            val refetched = h.downloader.fetched.drop(fetchesBefore).map { it.key }
            assertTrue(
                refetched.none { it in storedAfterPause },
                "resume refetched tiles it already had",
            )
        }

        @Test fun `resume is refused while off wifi`() = runTest {
            val h = OfflineHarness()
            h.manager.downloadNew(lat, lon, OfflineRadius.NM_90, TEST_DETAIL)
            val seg = h.store.manifest.segments.single()
            h.network.state = NetworkState.CELLULAR
            val outcome = h.manager.resume(seg.id, seg.coverage.single().id)
            assertTrue(outcome is DownloadOutcome.Rejected)
        }

        @Test fun `resumable coverage is discoverable`() = runTest {
            val h = OfflineHarness()
            h.network.afterCalls = 2
            h.network.thenState = NetworkState.CELLULAR
            h.manager.downloadNew(lat, lon, OfflineRadius.NM_250, MapDetail.DETAILED)
            assertEquals(1, h.manager.resumableCoverage().size)
        }
    }

    @Nested inner class Idempotency {

        @Test fun `re-running a completed download fetches nothing`() = runTest {
            val h = OfflineHarness()
            h.manager.downloadNew(lat, lon, OfflineRadius.NM_90, TEST_DETAIL)
            val fetches = h.downloader.fetched.size
            val seg = h.store.manifest.segments.single()

            val again = h.manager.resume(seg.id, seg.coverage.single().id)
            assertTrue(again is DownloadOutcome.NothingToDo, "got $again")
            assertEquals(fetches, h.downloader.fetched.size, "re-run hit the network")
        }

        @Test fun `no tile is ever fetched twice across a pause and resume`() = runTest {
            val h = OfflineHarness()
            h.network.afterCalls = 2
            h.network.thenState = NetworkState.CELLULAR
            h.manager.downloadNew(lat, lon, OfflineRadius.NM_250, MapDetail.DETAILED)

            h.network.afterCalls = null
            h.network.state = NetworkState.WIFI_UNMETERED
            val seg = h.store.manifest.segments.single()
            h.manager.resume(seg.id, seg.coverage.single().id)

            val counts = h.downloader.fetched.groupingBy { it.key }.eachCount()
            val duplicated = counts.filterValues { it > 1 }
            assertTrue(duplicated.isEmpty(), "these tiles were fetched more than once: $duplicated")
        }

        @Test fun `a second segment reuses tiles already on disk`() = runTest {
            val h = OfflineHarness()
            h.manager.downloadNew(lat, lon, OfflineRadius.NM_150, TEST_DETAIL)
            val afterFirst = h.downloader.fetched.size

            // A concentric, smaller radius at the same point is entirely contained.
            h.manager.downloadNew(lat, lon, OfflineRadius.NM_90, TEST_DETAIL, explicitName = "Inner")
            val newFetches = h.downloader.fetched.size - afterFirst
            assertEquals(0, newFetches, "overlapping download refetched $newFetches tiles")

            val inner = h.store.manifest.segments.first { it.displayName == "Inner" }
            assertEquals(DownloadState.COMPLETE, inner.state)
            assertTrue(inner.storedTileKeys.isNotEmpty(), "reused tiles must still be recorded as coverage")
        }
    }

    @Nested inner class Estimation {

        @Test fun `estimate reports new tiles separately from already-stored ones`() = runTest {
            val h = OfflineHarness()
            val before = h.manager.estimateForRadius(lat, lon, OfflineRadius.NM_90, TEST_DETAIL)
            assertEquals(before.totalTiles, before.newTiles, "nothing stored yet")

            h.manager.downloadNew(lat, lon, OfflineRadius.NM_90, TEST_DETAIL)
            val after = h.manager.estimateForRadius(lat, lon, OfflineRadius.NM_90, TEST_DETAIL)
            assertEquals(0, after.newTiles, "everything is already stored")
            assertEquals(after.totalTiles, after.alreadyStoredTiles)
        }

        @Test fun `estimate needs no network`() {
            val h = OfflineHarness(network = FakeNetwork(NetworkState.DISCONNECTED))
            val e = h.manager.estimateForRadius(lat, lon, OfflineRadius.NM_150, TEST_DETAIL)
            assertTrue(e.totalTiles > 0)
            assertTrue(h.downloader.fetched.isEmpty())
        }
    }

    @Nested inner class UnsupportedRadius {

        @Test fun `an unsupported radius is refused before any network use`() = runTest {
            val h = OfflineHarness()
            val outcome = h.manager.downloadNewByNauticalMiles(lat, lon, 137, TEST_DETAIL)
            assertTrue(outcome is DownloadOutcome.Failed, "got $outcome")
            assertTrue((outcome as DownloadOutcome.Failed).reason.contains("90"))
            assertTrue(h.downloader.fetched.isEmpty())
            assertTrue(h.store.manifest.segments.isEmpty())
        }

        @Test fun `each supported radius is accepted through the checked entry point`() = runTest {
            listOf(90, 150, 250).forEach { nm ->
                val h = OfflineHarness()
                val outcome = h.manager.downloadNewByNauticalMiles(lat, lon, nm, TEST_DETAIL)
                assertTrue(outcome is DownloadOutcome.Completed, "$nm NM gave $outcome")
                assertEquals(nm, h.store.manifest.segments.single().requestedRadiusNm)
            }
        }
    }

    @Nested inner class PartialFailures {

        @Test fun `tiles the provider refuses leave the download resumable, not failed`() = runTest {
            val h = OfflineHarness()
            val wanted = TileGeometry.tilesForRadius(lat, lon, 90, TEST_DETAIL.zoomRange)
            h.downloader.unavailable += wanted.take(3).map { it.key }

            val outcome = h.manager.downloadNew(lat, lon, OfflineRadius.NM_90, TEST_DETAIL)
            assertTrue(outcome is DownloadOutcome.Paused, "got $outcome")
            assertEquals(DownloadState.INCOMPLETE, h.store.manifest.segments.single().state)
            // The tiles that did arrive are kept.
            assertEquals(wanted.size - 3, h.tiles.data.size)
        }

        @Test fun `a storage failure is logged and does not lose other tiles`() = runTest {
            val h = OfflineHarness()
            val wanted = TileGeometry.tilesForRadius(lat, lon, 90, TEST_DETAIL.zoomRange)
            h.tiles.failWrites += wanted.first().key

            h.manager.downloadNew(lat, lon, OfflineRadius.NM_90, TEST_DETAIL)
            assertTrue(h.logger.of("storageError").isNotEmpty())
            assertEquals(wanted.size - 1, h.tiles.data.size)
        }
    }
}
