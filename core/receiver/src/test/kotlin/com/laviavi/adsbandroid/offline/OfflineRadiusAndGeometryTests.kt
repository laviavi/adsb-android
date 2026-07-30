package com.laviavi.adsbandroid.offline

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** Radius validation and the tile geometry every size estimate depends on. */
class OfflineRadiusAndGeometryTests {

    @Nested inner class RadiusSelection {

        @Test fun `the three allowed radii are accepted`() {
            listOf(90, 150, 250).forEach { nm ->
                assertNotNull(OfflineRadius.fromNauticalMiles(nm), "$nm NM must be selectable")
                assertTrue(OfflineRadius.isSupported(nm))
            }
        }

        @Test fun `arbitrary radii are rejected`() {
            // Including values that look plausible - the set is closed, not a range.
            listOf(0, 1, 89, 91, 100, 149, 151, 200, 249, 251, 500, -90).forEach { nm ->
                assertNull(OfflineRadius.fromNauticalMiles(nm), "$nm NM must not be accepted")
                assertFalse(OfflineRadius.isSupported(nm))
            }
        }

        @Test fun `radii carry their nautical-mile values`() {
            assertEquals(90, OfflineRadius.NM_90.nauticalMiles)
            assertEquals(150, OfflineRadius.NM_150.nauticalMiles)
            assertEquals(250, OfflineRadius.NM_250.nauticalMiles)
        }

        @Test fun `detail levels never exceed the zoom the renderer can display`() {
            // MapScreen.computeZoom tops out at ~11.5, so z12 is the last useful level.
            MapDetail.entries.forEach {
                assertTrue(it.maxZoom <= 12, "${it.name} would download tiles the map never draws")
                assertTrue(it.minZoom >= 8, "${it.name} would download levels below the widest range step")
            }
        }
    }

    @Nested inner class Projection {

        @Test fun `tile coordinates round-trip through lat lon`() {
            val z = 10
            listOf(33.95 to -117.33, 49.28 to -123.12, 60.0 to -135.0, -33.86 to 151.2).forEach { (lat, lon) ->
                val x = TileGeometry.lonToTileX(lon, z)
                val y = TileGeometry.latToTileY(lat, z)
                val backLon = TileGeometry.tileXToLon(x, z)
                val backLat = TileGeometry.tileYToLat(y, z)
                // Back-conversion lands on the tile's NW corner, so it must be within
                // one tile of the input, on the correct side.
                assertTrue(backLon <= lon + 1e-6, "lon $lon -> x $x -> $backLon")
                assertTrue(backLat >= lat - 1e-6, "lat $lat -> y $y -> $backLat")
            }
        }

        @Test fun `tile count quadruples per zoom level`() {
            val lat = 33.95; val lon = -117.33
            val at8 = TileGeometry.tilesForRadius(lat, lon, 150, 8..8).size
            val at9 = TileGeometry.tilesForRadius(lat, lon, 150, 9..9).size
            val at10 = TileGeometry.tilesForRadius(lat, lon, 150, 10..10).size
            // Disc filtering makes this approximate rather than exact 4x.
            assertTrue(at9 in (at8 * 3)..(at8 * 5), "z8=$at8 z9=$at9")
            assertTrue(at10 in (at9 * 3)..(at9 * 5), "z9=$at9 z10=$at10")
        }

        @Test fun `larger radius covers strictly more tiles`() {
            val lat = 33.95; val lon = -117.33
            val small = TileGeometry.tilesForRadius(lat, lon, 90, 8..9)
            val mid = TileGeometry.tilesForRadius(lat, lon, 150, 8..9)
            val big = TileGeometry.tilesForRadius(lat, lon, 250, 8..9)
            assertTrue(small.size < mid.size)
            assertTrue(mid.size < big.size)
            // Concentric: a wider radius must be a superset, or an append after
            // widening would re-download the middle.
            assertTrue(mid.containsAll(small), "150 NM must contain everything in 90 NM")
            assertTrue(big.containsAll(mid), "250 NM must contain everything in 150 NM")
        }

        @Test fun `coverage is a disc, not the enclosing square`() {
            val lat = 33.95; val lon = -117.33
            val radius = 150
            val disc = TileGeometry.tilesForRadius(lat, lon, radius, 10..10)
            val box = TileGeometry.tilesForBounds(
                TileGeometry.boundsForRadius(lat, lon, radius), 10..10,
            )
            assertTrue(disc.size < box.size, "disc ${disc.size} should be smaller than box ${box.size}")
            // Every kept tile is within the radius plus at most half a tile of slack.
            disc.forEach { t ->
                val (cLat, cLon) = TileGeometry.tileCenter(t)
                val d = TileGeometry.distanceNm(lat, lon, cLat, cLon)
                assertTrue(d <= radius * 1.15, "tile ${t.key} at ${d}nm is outside ${radius}nm")
            }
        }

        @Test fun `longitude span widens with latitude`() {
            // The same radius must cover more degrees of longitude in BC than in
            // California, or northern downloads come out too narrow.
            val south = TileGeometry.boundsForRadius(34.0, -117.0, 150)
            val north = TileGeometry.boundsForRadius(55.0, -123.0, 150)
            val southSpan = south.eastLon - south.westLon
            val northSpan = north.eastLon - north.westLon
            assertTrue(northSpan > southSpan, "north span $northSpan should exceed south $southSpan")
        }

        @Test fun `distance matches known separations`() {
            // LAX to SFO is ~293 nm.
            val d = TileGeometry.distanceNm(33.9425, -118.4081, 37.6213, -122.3790)
            assertTrue(d in 280.0..305.0, "expected ~293 nm, got $d")
        }
    }

    @Nested inner class RouteCoverage {

        @Test fun `a sampled route corridor beats the enclosing box`() {
            // Waypoints matter: with only two points the single leg's box *is* the
            // whole enclosing box, so the corridor can only be larger once widened.
            // The saving comes from the path being sampled, which is what
            // TravelTracker actually produces.
            val path = listOf(
                33.95 to -117.33,
                37.0 to -119.0,
                40.5 to -120.3,
                44.0 to -121.5,
                47.0 to -122.4,
                49.28 to -123.12,
            )
            val corridor = TileGeometry.tilesForRoute(path, 30, 8..8)
            val box = TileGeometry.tilesForBounds(GeoBounds(33.95, 49.28, -123.12, -117.33), 8..8)
            assertTrue(
                corridor.size < box.size,
                "corridor ${corridor.size} should beat naive box ${box.size}",
            )
        }

        @Test fun `an unsampled two-point leg is widened, not shrunk`() {
            // Documents the limitation above rather than hiding it: a single long leg
            // gets the corridor margin added on all sides.
            val path = listOf(33.95 to -117.33, 49.28 to -123.12)
            val corridor = TileGeometry.tilesForRoute(path, 30, 8..8)
            val box = TileGeometry.tilesForBounds(GeoBounds(33.95, 49.28, -123.12, -117.33), 8..8)
            assertTrue(corridor.size >= box.size)
        }

        @Test fun `single-point route falls back to a radius`() {
            val tiles = TileGeometry.tilesForRoute(listOf(33.95 to -117.33), 30, 8..8)
            assertTrue(tiles.isNotEmpty())
        }

        @Test fun `empty route needs no tiles`() {
            assertTrue(TileGeometry.tilesForRoute(emptyList(), 30, 8..10).isEmpty())
        }
    }

    @Nested inner class Estimates {

        @Test fun `estimate range brackets the mid estimate`() {
            val n = 5_000
            assertTrue(TileGeometry.estimateBytesLow(n) < TileGeometry.estimateBytes(n))
            assertTrue(TileGeometry.estimateBytes(n) < TileGeometry.estimateBytesHigh(n))
        }

        @Test fun `byte formatting is human readable`() {
            assertEquals("512 B", TileGeometry.formatBytes(512))
            assertEquals("1 KB", TileGeometry.formatBytes(1024))
            assertEquals("5 MB", TileGeometry.formatBytes(5L * 1024 * 1024))
            assertEquals("1.50 GB", TileGeometry.formatBytes((1.5 * 1024 * 1024 * 1024).toLong()))
        }
    }
}
