package com.laviavi.adsbandroid.observability

import kotlin.math.sqrt

/** Statute miles per nautical mile — matches `enrich/distance.py`'s `_NM_TO_MI`. */
private const val NM_TO_MI = 1.15078

/** 8-way compass sector, declared N/NE/E/SE/S/SW/W/NW — the reference's `_SECTOR_LABELS` order. */
enum class CompassSector { N, NE, E, SE, S, SW, W, NW }

/** North wraps across 0°, so it is checked before the contiguous 45°-wide sectors below it. */
fun sectorForBearing(bearingDeg: Double): CompassSector {
    val b = ((bearingDeg % 360) + 360) % 360
    return when {
        b >= 337.5 || b < 22.5 -> CompassSector.N
        b < 67.5  -> CompassSector.NE
        b < 112.5 -> CompassSector.E
        b < 157.5 -> CompassSector.SE
        b < 202.5 -> CompassSector.S
        b < 247.5 -> CompassSector.SW
        b < 292.5 -> CompassSector.W
        else      -> CompassSector.NW
    }
}

/** Linear-interpolation percentile (numpy's default "linear" method), 0-100. */
fun percentile(sortedValues: List<Double>, p: Double): Double {
    if (sortedValues.isEmpty()) return 0.0
    val n = sortedValues.size
    val idx = (p / 100.0) * (n - 1)
    val lo = idx.toInt()
    val hi = minOf(lo + 1, n - 1)
    val frac = idx - lo
    return sortedValues[lo] * (1 - frac) + sortedValues[hi] * frac
}

fun median(sortedValues: List<Double>): Double = percentile(sortedValues, 50.0)

/**
 * Coverage symmetry score, 0-100: 100 = even reception in every sector with
 * data, 0 = concentrated in one sector or no data at all. Combines the
 * fraction of sectors with any reception and the evenness (1 - coefficient of
 * variation) of their median ranges — direct port of `_symmetry_score`.
 *
 * Uses round-half-to-even (`Math.rint`) to match Python's `round()`, not
 * `Math.round`'s round-half-up — this is not a cosmetic choice: an
 * all-reception-in-one-sector reading (a real, common case — 100 * 1/8 * 1.0
 * = 12.5) rounds to 12 in the reference and 13 under naive round-half-up.
 */
fun symmetryScore(sectorMedians: Map<CompassSector, Double>): Int {
    val nonzero = sectorMedians.values.filter { it > 0 }
    if (nonzero.isEmpty()) return 0
    val coverageFrac = nonzero.size.toDouble() / sectorMedians.size
    val mean = nonzero.sum() / nonzero.size
    if (mean == 0.0) return 0
    val variance = nonzero.sumOf { (it - mean) * (it - mean) } / nonzero.size
    val evenness = (1.0 - sqrt(variance) / mean).coerceAtLeast(0.0)
    return Math.rint(100 * coverageFrac * evenness).toInt().coerceIn(0, 100)
}

/** Non-overlapping, contiguous altitude bands (feet) — declaration order doubles as match order. */
enum class AltitudeBand(val loFt: Int?, val hiFt: Int?) {
    BELOW_3000(null, 3000),
    BAND_3000_10000(3000, 10000),
    BAND_10000_30000(10000, 30000),
    ABOVE_30000(30000, null);

    fun contains(altitudeFt: Int): Boolean =
        (loFt == null || altitudeFt >= loFt) && (hiFt == null || altitudeFt < hiFt)
}

data class SectorStats(
    val count: Int, val maxMi: Double, val medianMi: Double, val p90Mi: Double,
    val medianSignalDbfs: Double? = null,
)

data class CoverageMetricsRow(
    val intervalSec: Int,
    val observerLat: Double,
    val observerLon: Double,
    val aircraftWithPosition: Int,
    val sectors: Map<CompassSector, SectorStats>,
    val altitudeCounts: Map<AltitudeBand, Int>,
    val symmetryScore: Int,
    val bestSector: CompassSector?,
    val worstSector: CompassSector?,
)

/** An aircraft with a known position, pre-resolved to distance/bearing from the observer. */
data class PositionedAircraft(
    val distanceNm: Double, val bearingDeg: Double, val altitudeFt: Int?,
    val signalDbfs: Double? = null,
)

/**
 * Port of `observability/coverage.py`: one row per 5-minute interval,
 * breaking reception down by compass sector and altitude band to surface
 * directional problems (an obstruction, a badly-placed antenna) that a single
 * "max range" number can't show.
 *
 * Distance/bearing are **not** recomputed here — every [PositionedAircraft]
 * carries the same `distanceNm`/`bearingDeg` `AircraftManager` already
 * computes on every ADS-B position merge (identical haversine formula and
 * Earth radius, 3440.065 nm, as `enrich/distance.py`), so this is a pure
 * aggregation step over values already proven correct elsewhere.
 */
object CoverageMetrics {

    const val INTERVAL_SEC = 300

    val COLUMNS: List<String> = buildList {
        addAll(listOf(
            "timestamp_utc", "timestamp_local", "timezone_name", "utc_offset",
            "interval_sec", "observer_lat", "observer_lon", "aircraft_with_position",
        ))
        for (sector in CompassSector.entries) {
            add("sector_${sector.name}_count")
            add("sector_${sector.name}_max_mi")
            add("sector_${sector.name}_median_mi")
            add("sector_${sector.name}_p90_mi")
            add("sector_${sector.name}_median_signal_dbfs")
        }
        addAll(listOf(
            "alt_below_3000ft", "alt_3000_to_10000ft", "alt_10000_to_30000ft", "alt_above_30000ft",
            "symmetry_score", "best_sector", "worst_sector", "notes",
        ))
    }

    /**
     * Returns `null` when there is nothing positioned to report — mirrors the
     * reference silently skipping the row rather than writing an all-zero one.
     * [aircraft] must already be filtered to those with a known position;
     * there is no "observer position unset" skip here because, unlike the
     * reference's `Optional[float]` defaulting to `None`, `AppConfig` always
     * carries *some* observer coordinates.
     */
    fun computeRow(
        observerLat: Double,
        observerLon: Double,
        aircraft: List<PositionedAircraft>,
        intervalSec: Int = INTERVAL_SEC,
    ): CoverageMetricsRow? {
        if (aircraft.isEmpty()) return null

        val sectorRanges = CompassSector.entries.associateWith { mutableListOf<Double>() }
        val sectorSignals = CompassSector.entries.associateWith { mutableListOf<Double>() }
        val altCounts = AltitudeBand.entries.associateWith { 0 }.toMutableMap()

        for (ac in aircraft) {
            val sector = sectorForBearing(ac.bearingDeg)
            val distMi = ac.distanceNm * NM_TO_MI
            sectorRanges.getValue(sector).add(distMi)
            ac.signalDbfs?.let { sectorSignals.getValue(sector).add(it) }
            ac.altitudeFt?.let { alt ->
                AltitudeBand.entries.firstOrNull { it.contains(alt) }?.let { band ->
                    altCounts[band] = altCounts.getValue(band) + 1
                }
            }
        }

        val sectorMedians = LinkedHashMap<CompassSector, Double>()
        val sectorStats = LinkedHashMap<CompassSector, SectorStats>()
        for (sector in CompassSector.entries) {
            val ranges = sectorRanges.getValue(sector).sorted()
            val signals = sectorSignals.getValue(sector).sorted()
            val med = median(ranges)
            sectorMedians[sector] = med
            sectorStats[sector] = SectorStats(
                count = ranges.size,
                maxMi = ranges.lastOrNull() ?: 0.0,
                medianMi = med,
                p90Mi = percentile(ranges, 90.0),
                medianSignalDbfs = if (signals.isNotEmpty()) median(signals) else null,
            )
        }

        // Ties break toward the first sector in declaration order (N..NW),
        // matching Python's max()/min() over a dict built in that same order.
        val activeSectors = sectorMedians.filterValues { it > 0 }
        val best  = activeSectors.maxByOrNull { it.value }?.key
        val worst = activeSectors.minByOrNull { it.value }?.key

        return CoverageMetricsRow(
            intervalSec = intervalSec,
            observerLat = observerLat,
            observerLon = observerLon,
            aircraftWithPosition = aircraft.size,
            sectors = sectorStats,
            altitudeCounts = altCounts,
            symmetryScore = symmetryScore(sectorMedians),
            bestSector = best,
            worstSector = worst,
        )
    }

    fun toCsvValues(row: CoverageMetricsRow, timestamps: CsvTimestamps): List<String> = buildList {
        add(timestamps.utc); add(timestamps.local); add(timestamps.zoneName); add(timestamps.utcOffset)
        add(row.intervalSec.toString())
        add("%.4f".format(row.observerLat))
        add("%.4f".format(row.observerLon))
        add(row.aircraftWithPosition.toString())
        for (sector in CompassSector.entries) {
            val s = row.sectors.getValue(sector)
            add(s.count.toString())
            add("%.1f".format(s.maxMi))
            add("%.1f".format(s.medianMi))
            add("%.1f".format(s.p90Mi))
            add(s.medianSignalDbfs?.let { "%.1f".format(it) } ?: "")
        }
        for (band in AltitudeBand.entries) add(row.altitudeCounts.getValue(band).toString())
        add(row.symmetryScore.toString())
        add(row.bestSector?.name ?: "")
        add(row.worstSector?.name ?: "")
        add("")
    }
}
