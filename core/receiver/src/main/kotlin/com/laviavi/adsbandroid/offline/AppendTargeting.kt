package com.laviavi.adsbandroid.offline

/**
 * Which segment should receive appended travel coverage.
 *
 * Every outcome is explicit, including the two that refuse to decide. Silently
 * picking a "closest" segment is the failure this type exists to prevent: coverage
 * attached to the wrong region is invisible until someone goes looking for a map
 * that is filed somewhere they would never think to look.
 */
sealed interface AppendTarget {
    /** A single segment won on a deterministic rule. */
    data class Segment(val segmentId: String, val reason: Reason) : AppendTarget

    /** Two or more segments are equally good. The user picks; the app must not. */
    data class AmbiguousChoice(val candidateSegmentIds: List<String>) : AppendTarget

    /** Nothing suitable exists. Offer a new segment rather than attaching to a stranger. */
    data class CreateNew(val suggestedName: String?) : AppendTarget

    enum class Reason {
        /** The destination falls inside this segment's bounds. */
        CONTAINS_DESTINATION,
        /** No segment contains the destination; this one overlaps the route most. */
        GREATEST_ROUTE_OVERLAP,
    }
}

/**
 * The append-target rules, applied in fixed order.
 *
 * Ordering matters and is the specified one: destination containment first, then
 * greatest route overlap, then ask, then offer a new segment. Encoded here as pure
 * functions so the precedence is asserted directly rather than inferred from the
 * behaviour of the manager that calls it.
 */
object AppendTargeting {

    /**
     * Overlap counts must clear this before a segment can win on overlap alone.
     *
     * A route brushing one tile of a distant region is not evidence it belongs
     * there; without a floor, any segment anywhere along a long journey could
     * capture the append.
     */
    const val MIN_OVERLAP_TILES = 4

    fun choose(
        record: TravelRecord,
        segments: List<OfflineSegment>,
        zoomRange: IntRange,
        corridorNm: Int = TravelTracker.DEFAULT_CORRIDOR_NM,
    ): AppendTarget {
        if (segments.isEmpty()) return AppendTarget.CreateNew(record.destinationName)

        // Rule 1 — the region containing the destination.
        val destination = record.destination
        if (destination != null) {
            val containing = segments.filter { it.contains(destination.lat, destination.lon) }
            if (containing.size == 1) {
                return AppendTarget.Segment(containing.first().id, AppendTarget.Reason.CONTAINS_DESTINATION)
            }
            if (containing.size > 1) {
                // Several regions contain the destination. Break the tie on which one
                // covers more of how the traveller actually got there; if that is also
                // level, the user decides.
                val routeTiles = TileGeometry.tilesForRoute(record.latLonPath, corridorNm, zoomRange)
                    .mapTo(HashSet()) { it.key }
                val ranked = containing
                    .map { it to (it.allTileKeys intersect routeTiles).size }
                    .sortedByDescending { it.second }
                val best = ranked.first().second
                val winners = ranked.filter { it.second == best }
                return if (winners.size == 1) {
                    AppendTarget.Segment(winners.first().first.id, AppendTarget.Reason.CONTAINS_DESTINATION)
                } else {
                    AppendTarget.AmbiguousChoice(winners.map { it.first.id }.sorted())
                }
            }
        }

        // Rule 2 — greatest overlap with the travelled route.
        val routeTiles = TileGeometry.tilesForRoute(record.latLonPath, corridorNm, zoomRange)
            .mapTo(HashSet()) { it.key }
        if (routeTiles.isNotEmpty()) {
            val ranked = segments
                .map { it to (it.allTileKeys intersect routeTiles).size }
                .filter { it.second >= MIN_OVERLAP_TILES }
                .sortedByDescending { it.second }

            if (ranked.isNotEmpty()) {
                val best = ranked.first().second
                val winners = ranked.filter { it.second == best }
                return if (winners.size == 1) {
                    AppendTarget.Segment(winners.first().first.id, AppendTarget.Reason.GREATEST_ROUTE_OVERLAP)
                } else {
                    // Rule 3 — a genuine tie goes to the user.
                    AppendTarget.AmbiguousChoice(winners.map { it.first.id }.sorted())
                }
            }
        }

        // Rule 4 — nothing overlaps meaningfully; do not adopt an unrelated segment.
        return AppendTarget.CreateNew(record.destinationName)
    }
}
