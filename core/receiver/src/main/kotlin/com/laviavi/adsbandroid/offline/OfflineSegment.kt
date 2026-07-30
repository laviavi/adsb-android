package com.laviavi.adsbandroid.offline

import kotlinx.serialization.Serializable

/** Whether a body of coverage was the original radius download or a later append. */
@Serializable
enum class CoverageSource { INITIAL_DOWNLOAD, APPENDED_TRAVEL }

/** Lifecycle of a download. Only [COMPLETE] means every tile in the manifest is on disk. */
@Serializable
enum class DownloadState {
    COMPLETE,
    /** Started and stopped without finishing — resumable; existing tiles are kept. */
    INCOMPLETE,
    /** Stopped deliberately, or by Wi-Fi loss. Distinct from INCOMPLETE so the UI can say which. */
    PAUSED,
    /** Stopped by an error that retrying may not fix. Still resumable; nothing is discarded. */
    FAILED,
}

/**
 * One body of coverage inside a segment.
 *
 * Appends are recorded as additional entries rather than by rewriting the original,
 * so the segment's history stays intact and an append can never erase the download
 * it was added to. The tile set is stored per entry; what the segment *owns* is the
 * union across entries.
 */
@Serializable
data class CoverageEntry(
    val id: String,
    val source: CoverageSource,
    /** Tile keys ([TileRef.key]) this entry requested. */
    val tileKeys: Set<String>,
    val minZoom: Int,
    val maxZoom: Int,
    val createdAtMs: Long,
    val state: DownloadState,
    /** Tile keys confirmed written to disk. Equals [tileKeys] once COMPLETE. */
    val storedTileKeys: Set<String> = emptySet(),
    val bytesStored: Long = 0L,
    /** Populated for INITIAL_DOWNLOAD; null for appends, which are route-shaped. */
    val radiusNm: Int? = null,
    val centerLat: Double? = null,
    val centerLon: Double? = null,
    val note: String? = null,
) {
    val isComplete: Boolean get() = state == DownloadState.COMPLETE
    val remainingTileKeys: Set<String> get() = tileKeys - storedTileKeys
    val progressPercent: Int
        get() = if (tileKeys.isEmpty()) 100 else (storedTileKeys.size * 100 / tileKeys.size)
}

/**
 * An independently managed offline map.
 *
 * Segments are the unit the user names, inspects and deletes — deliberately not one
 * opaque cache, so deleting "Vancouver" cannot take "Riverside" with it and a failed
 * download cannot poison unrelated coverage.
 */
@Serializable
data class OfflineSegment(
    val id: String,
    val displayName: String,
    /** Place name resolved at creation, before any duplicate suffix was applied. */
    val locationName: String,
    val centerLat: Double,
    val centerLon: Double,
    /** The radius originally requested. Null only if the segment did not start as a radius download. */
    val requestedRadiusNm: Int?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val coverage: List<CoverageEntry>,
) {
    /** Every tile this segment references, across the initial download and all appends. */
    val allTileKeys: Set<String> get() = coverage.flatMapTo(LinkedHashSet()) { it.tileKeys }

    val storedTileKeys: Set<String> get() = coverage.flatMapTo(LinkedHashSet()) { it.storedTileKeys }

    val bytesStored: Long get() = coverage.sumOf { it.bytesStored }

    val hasAppendedCoverage: Boolean get() = coverage.any { it.source == CoverageSource.APPENDED_TRAVEL }

    val zoomRange: IntRange?
        get() = coverage.takeIf { it.isNotEmpty() }
            ?.let { c -> c.minOf { it.minZoom }..c.maxOf { it.maxZoom } }

    /**
     * Worst state across entries — a segment with one unfinished append is not
     * complete, and saying otherwise would hide resumable work from the user.
     */
    val state: DownloadState
        get() = when {
            coverage.isEmpty() -> DownloadState.INCOMPLETE
            coverage.any { it.state == DownloadState.FAILED } -> DownloadState.FAILED
            coverage.any { it.state == DownloadState.PAUSED } -> DownloadState.PAUSED
            coverage.any { it.state == DownloadState.INCOMPLETE } -> DownloadState.INCOMPLETE
            else -> DownloadState.COMPLETE
        }

    val bounds: GeoBounds?
        get() = TileGeometry.boundsOf(allTileKeys.mapNotNull { TileRef.parse(it) })

    fun contains(lat: Double, lon: Double): Boolean = bounds?.contains(lat, lon) == true

    val progressPercent: Int
        get() {
            val total = allTileKeys.size
            return if (total == 0) 100 else (storedTileKeys.size * 100 / total)
        }
}

/** The whole offline library. Serialised as one document so updates are atomic. */
@Serializable
data class OfflineManifest(
    val version: Int = CURRENT_VERSION,
    val segments: List<OfflineSegment> = emptyList(),
) {
    /**
     * How many segments reference each tile. Deletion consults this so shared tiles
     * survive until the last segment referencing them is gone.
     */
    fun referenceCounts(): Map<String, Int> {
        val counts = HashMap<String, Int>()
        segments.forEach { seg ->
            // Per segment, not per entry: two appends inside one segment covering the
            // same tile must count once, or deleting that segment would leave the tile
            // orphaned on disk with a positive count.
            seg.storedTileKeys.forEach { key -> counts[key] = (counts[key] ?: 0) + 1 }
        }
        return counts
    }

    /** Tiles that would become unreferenced if [segmentId] were removed. */
    fun tilesExclusivelyOwnedBy(segmentId: String): Set<String> {
        val target = segments.firstOrNull { it.id == segmentId } ?: return emptySet()
        val othersTiles = segments.filter { it.id != segmentId }
            .flatMapTo(HashSet()) { it.storedTileKeys }
        return target.storedTileKeys - othersTiles
    }

    fun segment(id: String): OfflineSegment? = segments.firstOrNull { it.id == id }

    val totalBytes: Long get() = segments.sumOf { it.bytesStored }

    /** Distinct tiles on disk — less than the sum of segments when coverage overlaps. */
    val distinctTileCount: Int get() = segments.flatMapTo(HashSet()) { it.storedTileKeys }.size

    companion object {
        const val CURRENT_VERSION = 1
        val EMPTY = OfflineManifest()
    }
}
