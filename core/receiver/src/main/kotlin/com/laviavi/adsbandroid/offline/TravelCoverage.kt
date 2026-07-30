package com.laviavi.adsbandroid.offline

import kotlinx.serialization.Serializable

/**
 * A stretch of travel that fell outside every stored segment.
 *
 * Recorded while moving — on cellular, in the air, anywhere — but never acted on
 * there: this is a note that coverage *could* be added, not a queued download. It
 * holds only what is needed to request tiles later, so accumulating a long journey
 * costs a few kilobytes rather than the megabytes the tiles themselves would.
 */
@Serializable
data class TravelRecord(
    val id: String,
    /** Sampled positions, coarse by design — see [TravelTracker.MIN_SAMPLE_SEPARATION_NM]. */
    val path: List<TravelPoint>,
    val startedAtMs: Long,
    val lastUpdatedAtMs: Long,
    /** Segment the traveller was inside when the excursion began, if any. */
    val originSegmentId: String? = null,
    /** Place name resolved at the destination, used to name a new segment if needed. */
    val destinationName: String? = null,
    /** Set once the user has been asked and said "not now"; suppresses re-prompting. */
    val deferred: Boolean = false,
) {
    val latLonPath: List<Pair<Double, Double>> get() = path.map { it.lat to it.lon }

    val destination: TravelPoint? get() = path.lastOrNull()
    val origin: TravelPoint? get() = path.firstOrNull()

    fun bounds(): GeoBounds? {
        if (path.isEmpty()) return null
        return GeoBounds(
            southLat = path.minOf { it.lat },
            northLat = path.maxOf { it.lat },
            westLon = path.minOf { it.lon },
            eastLon = path.maxOf { it.lon },
        )
    }
}

@Serializable
data class TravelPoint(val lat: Double, val lon: Double, val atMs: Long)

/** Pending travel records, kept beside the manifest. */
@Serializable
data class TravelLog(
    val version: Int = 1,
    val records: List<TravelRecord> = emptyList(),
) {
    fun active(): TravelRecord? = records.lastOrNull()
    fun pending(): List<TravelRecord> = records.filterNot { it.deferred }

    companion object { val EMPTY = TravelLog() }
}

/**
 * Decides when movement is worth recording.
 *
 * Pure so the sampling rules are testable without a GPS. Nothing here downloads or
 * schedules anything — the tracker's entire output is a [TravelRecord], and the
 * decision to fetch tiles for it is made later, by the user, on Wi-Fi.
 */
object TravelTracker {

    /**
     * Positions closer together than this are dropped.
     *
     * The record only has to be good enough to request a tile corridor, and the
     * corridor is [DEFAULT_CORRIDOR_NM] wide — sampling finer than that produces
     * points that all fall in the same tiles, so it would grow the log without
     * changing a single tile requested.
     */
    const val MIN_SAMPLE_SEPARATION_NM = 5.0

    /** Half-width of the tile corridor requested around a travelled path. */
    const val DEFAULT_CORRIDOR_NM = 30

    /**
     * How far outside known coverage counts as "travelled away".
     *
     * A margin rather than zero because coverage edges are tile-aligned and GPS
     * wanders; without it, sitting near the rim of a segment would open and close
     * travel records repeatedly.
     */
    const val OUTSIDE_MARGIN_NM = 10.0

    /** True when the position is outside every segment's bounds by more than the margin. */
    fun isOutsideCoverage(
        lat: Double,
        lon: Double,
        segments: List<OfflineSegment>,
    ): Boolean {
        if (segments.isEmpty()) return true
        return segments.none { seg ->
            val b = seg.bounds ?: return@none false
            val expanded = TileGeometry.expand(b, OUTSIDE_MARGIN_NM.toInt())
            expanded.contains(lat, lon)
        }
    }

    /** Whether a new point is far enough from the last to be worth appending. */
    fun shouldSample(last: TravelPoint?, lat: Double, lon: Double): Boolean {
        if (last == null) return true
        return TileGeometry.distanceNm(last.lat, last.lon, lat, lon) >= MIN_SAMPLE_SEPARATION_NM
    }

    fun appendPoint(record: TravelRecord, lat: Double, lon: Double, atMs: Long): TravelRecord {
        if (!shouldSample(record.path.lastOrNull(), lat, lon)) return record
        return record.copy(
            path = record.path + TravelPoint(lat, lon, atMs),
            lastUpdatedAtMs = atMs,
        )
    }

    /** Tiles a travel record would need, minus anything already stored. */
    fun missingTilesFor(
        record: TravelRecord,
        alreadyStored: Set<String>,
        zoomRange: IntRange,
        corridorNm: Int = DEFAULT_CORRIDOR_NM,
    ): Set<TileRef> =
        TileGeometry.tilesForRoute(record.latLonPath, corridorNm, zoomRange)
            .filterNotTo(LinkedHashSet()) { it.key in alreadyStored }
}
