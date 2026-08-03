package com.laviavi.adsbandroid.observability

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Ground truth for every value asserted here was captured by running the
 * actual Python reference (`coverage.py`'s `_bearing_deg`/`_sector_for_bearing`/
 * `_median`/`_percentile`/`_symmetry_score`) directly, not hand-derived —
 * this project's established policy after prior hand-derivation mistakes.
 */
class CoverageMetricsTests {

    @Nested inner class SectorAssignment {

        @Test fun `sector boundaries match the Python reference exactly`() {
            assertEquals(CompassSector.N, sectorForBearing(0.0))
            assertEquals(CompassSector.N, sectorForBearing(22.4))
            assertEquals(CompassSector.NE, sectorForBearing(22.5))
            assertEquals(CompassSector.NW, sectorForBearing(337.4))
            assertEquals(CompassSector.N, sectorForBearing(337.5))
            assertEquals(CompassSector.NE, sectorForBearing(45.0))
            assertEquals(CompassSector.S, sectorForBearing(200.0))
        }

        @Test fun `bearing wraps into 0-360 before sector lookup`() {
            assertEquals(CompassSector.N, sectorForBearing(-0.1))
            assertEquals(CompassSector.N, sectorForBearing(360.0))
        }
    }

    @Nested inner class PercentileAndMedian {

        @Test fun `median of 5 values is the middle element`() {
            assertEquals(30.0, median(listOf(10.0, 20.0, 30.0, 40.0, 50.0)), 1e-9)
        }

        @Test fun `p90 of 5 values interpolates linearly`() {
            assertEquals(46.0, percentile(listOf(10.0, 20.0, 30.0, 40.0, 50.0), 90.0), 1e-9)
        }

        @Test fun `single value returns itself regardless of percentile`() {
            assertEquals(5.0, percentile(listOf(5.0), 90.0), 1e-9)
        }

        @Test fun `empty list returns zero, not a divide-by-zero crash`() {
            assertEquals(0.0, median(emptyList()), 1e-9)
        }

        @Test fun `uneven spacing interpolates correctly`() {
            val sorted = listOf(10.0, 15.0, 100.0)
            assertEquals(15.0, median(sorted), 1e-9)
            assertEquals(83.0, percentile(sorted, 90.0), 1e-9)
        }
    }

    @Nested inner class SymmetryScore {

        @Test fun `even reception in all 8 sectors scores 100`() {
            val medians = CompassSector.entries.associateWith { 50.0 }
            assertEquals(100, symmetryScore(medians))
        }

        @Test fun `reception concentrated in one sector rounds to 12, not 13`() {
            // 100 * (1/8) * 1.0 = 12.5 exactly. Python's round() (banker's
            // rounding) gives 12; naive round-half-up gives 13. This is the
            // concrete case that made the rounding mode a real bug, not a
            // theoretical one — verified against the live Python reference.
            val medians = CompassSector.entries.associateWith { 0.0 } + mapOf(CompassSector.N to 50.0)
            assertEquals(12, symmetryScore(medians))
        }

        @Test fun `no data at all scores 0`() {
            assertEquals(0, symmetryScore(emptyMap()))
            assertEquals(0, symmetryScore(CompassSector.entries.associateWith { 0.0 }))
        }

        @Test fun `mixed sector medians match the Python reference value`() {
            val values = listOf(68.0, 44.0, 61.0, 40.0, 31.0, 19.0, 22.0, 0.0)
            val medians = CompassSector.entries.zip(values).toMap()
            assertEquals(50, symmetryScore(medians))
        }

        @Test fun `two equal active sectors score 25`() {
            val medians = CompassSector.entries.associateWith { 0.0 } +
                mapOf(CompassSector.N to 30.0, CompassSector.E to 30.0)
            assertEquals(25, symmetryScore(medians))
        }
    }

    @Nested inner class BestWorstSector {

        @Test fun `best and worst pick the max and min median among active sectors`() {
            val values = listOf(68.0, 44.0, 61.0, 40.0, 31.0, 19.0, 22.0, 0.0)
            val row = CoverageMetrics.computeRow(
                observerLat = 0.0, observerLon = 0.0,
                aircraft = CompassSector.entries.zip(values).flatMap { (sector, medianMi) ->
                    if (medianMi == 0.0) emptyList()
                    else listOf(PositionedAircraft(distanceNm = medianMi / 1.15078, bearingDeg = sectorMidpoint(sector), altitudeFt = null))
                },
            )!!
            assertEquals(CompassSector.N, row.bestSector)
            assertEquals(CompassSector.SW, row.worstSector)
        }

        @Test fun `a tie breaks toward the first sector in compass declaration order`() {
            val row = CoverageMetrics.computeRow(
                observerLat = 0.0, observerLon = 0.0,
                aircraft = listOf(
                    PositionedAircraft(distanceNm = 30.0 / 1.15078, bearingDeg = sectorMidpoint(CompassSector.N), altitudeFt = null),
                    PositionedAircraft(distanceNm = 30.0 / 1.15078, bearingDeg = sectorMidpoint(CompassSector.E), altitudeFt = null),
                ),
            )!!
            assertEquals(CompassSector.N, row.bestSector)
        }

        private fun sectorMidpoint(sector: CompassSector): Double = when (sector) {
            CompassSector.N -> 0.0; CompassSector.NE -> 45.0; CompassSector.E -> 90.0
            CompassSector.SE -> 135.0; CompassSector.S -> 180.0; CompassSector.SW -> 225.0
            CompassSector.W -> 270.0; CompassSector.NW -> 315.0
        }
    }

    @Nested inner class ComputeRow {

        @Test fun `no positioned aircraft returns null instead of an all-zero row`() {
            assertNull(CoverageMetrics.computeRow(0.0, 0.0, emptyList()))
        }

        @Test fun `altitude bands are mutually exclusive and cover the full range`() {
            val row = CoverageMetrics.computeRow(
                observerLat = 0.0, observerLon = 0.0,
                aircraft = listOf(
                    PositionedAircraft(1.0, 0.0, altitudeFt = 1000),
                    PositionedAircraft(1.0, 0.0, altitudeFt = 3000),
                    PositionedAircraft(1.0, 0.0, altitudeFt = 15000),
                    PositionedAircraft(1.0, 0.0, altitudeFt = 40000),
                    PositionedAircraft(1.0, 0.0, altitudeFt = null),
                ),
            )!!
            assertEquals(1, row.altitudeCounts.getValue(AltitudeBand.BELOW_3000))
            assertEquals(1, row.altitudeCounts.getValue(AltitudeBand.BAND_3000_10000))
            assertEquals(1, row.altitudeCounts.getValue(AltitudeBand.BAND_10000_30000))
            assertEquals(1, row.altitudeCounts.getValue(AltitudeBand.ABOVE_30000))
            assertEquals(5, row.aircraftWithPosition, "null-altitude aircraft still counts toward the total")
        }

        @Test fun `distance is converted nm to statute miles before bucketing`() {
            val row = CoverageMetrics.computeRow(
                observerLat = 0.0, observerLon = 0.0,
                aircraft = listOf(PositionedAircraft(distanceNm = 100.0, bearingDeg = 0.0, altitudeFt = null)),
            )!!
            assertEquals(115.078, row.sectors.getValue(CompassSector.N).maxMi, 1e-6)
        }
    }

    @Nested inner class CsvColumns {

        @Test fun `column count matches 8 timestamp+meta, 40 sector, 4 altitude, 4 trailing`() {
            assertEquals(8 + 8 * 5 + 4 + 4, CoverageMetrics.COLUMNS.size)
        }

        @Test fun `column list starts and ends exactly like the Python reference`() {
            assertEquals(
                listOf("timestamp_utc", "timestamp_local", "timezone_name", "utc_offset",
                    "interval_sec", "observer_lat", "observer_lon", "aircraft_with_position"),
                CoverageMetrics.COLUMNS.take(8),
            )
            assertEquals(
                listOf("alt_below_3000ft", "alt_3000_to_10000ft", "alt_10000_to_30000ft", "alt_above_30000ft",
                    "symmetry_score", "best_sector", "worst_sector", "notes"),
                CoverageMetrics.COLUMNS.takeLast(8),
            )
        }
    }

    @Nested inner class SynthesizeAllTimeRow {

        @Test fun `empty history yields a row with no active sectors`() {
            val row = CoverageMetrics.synthesizeAllTimeRow(emptyList(), 1.0, 2.0)
            assertNull(row.bestSector)
            assertNull(row.worstSector)
            assertEquals(0, row.aircraftWithPosition)
            assertTrue(row.sectors.values.all { it.count == 0 && it.maxMi == 0.0 })
        }

        @Test fun `count and max range are carried through per sector`() {
            val row = CoverageMetrics.synthesizeAllTimeRow(
                listOf(SectorTotal(CompassSector.N, count = 12, maxMi = 87.5)), 1.0, 2.0,
            )
            val n = row.sectors.getValue(CompassSector.N)
            assertEquals(12, n.count)
            assertEquals(87.5, n.maxMi, 1e-9)
            // Deliberate simplification: median/p90 collapse to max, since only
            // count/max survive the persisted aggregation.
            assertEquals(87.5, n.medianMi, 1e-9)
            assertEquals(87.5, n.p90Mi, 1e-9)
        }

        @Test fun `best and worst sector are the widest and narrowest max range`() {
            val row = CoverageMetrics.synthesizeAllTimeRow(
                listOf(
                    SectorTotal(CompassSector.N, count = 5, maxMi = 100.0),
                    SectorTotal(CompassSector.S, count = 5, maxMi = 20.0),
                ),
                1.0, 2.0,
            )
            assertEquals(CompassSector.N, row.bestSector)
            assertEquals(CompassSector.S, row.worstSector)
        }

        @Test fun `sectors absent from history report zero, not missing`() {
            val row = CoverageMetrics.synthesizeAllTimeRow(
                listOf(SectorTotal(CompassSector.N, count = 1, maxMi = 10.0)), 1.0, 2.0,
            )
            assertEquals(8, row.sectors.size)
            assertEquals(0, row.sectors.getValue(CompassSector.E).count)
        }
    }
}
