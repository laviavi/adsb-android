package com.laviavi.adsbandroid.offline

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt

/** One Web Mercator tile. The atomic unit of everything downloaded or stored. */
data class TileRef(val z: Int, val x: Int, val y: Int) {
    /** Stable key for manifests, reference counting and on-disk paths. */
    val key: String get() = "$z/$x/$y"

    companion object {
        fun parse(key: String): TileRef? {
            val p = key.split('/')
            if (p.size != 3) return null
            val z = p[0].toIntOrNull() ?: return null
            val x = p[1].toIntOrNull() ?: return null
            val y = p[2].toIntOrNull() ?: return null
            return TileRef(z, x, y)
        }
    }
}

/** Axis-aligned geographic bounds. Longitude is not normalised across the antimeridian — see [TileGeometry]. */
data class GeoBounds(
    val southLat: Double,
    val northLat: Double,
    val westLon: Double,
    val eastLon: Double,
) {
    fun contains(lat: Double, lon: Double): Boolean =
        lat in southLat..northLat && lon in westLon..eastLon

    fun intersects(other: GeoBounds): Boolean =
        southLat <= other.northLat && northLat >= other.southLat &&
            westLon <= other.eastLon && eastLon >= other.westLon

    companion object {
        fun union(all: List<GeoBounds>): GeoBounds? {
            if (all.isEmpty()) return null
            return GeoBounds(
                southLat = all.minOf { it.southLat },
                northLat = all.maxOf { it.northLat },
                westLon = all.minOf { it.westLon },
                eastLon = all.maxOf { it.eastLon },
            )
        }
    }
}

/**
 * Web Mercator tile math and coverage enumeration.
 *
 * Pure and side-effect free so every sizing decision — how many tiles a radius
 * costs, which tiles a route needs, what a download will weigh — is testable
 * without a network, a device, or a map library.
 */
object TileGeometry {

    /** Nautical miles per degree of latitude. A nautical mile is one arcminute, by definition. */
    const val NM_PER_DEGREE_LAT = 60.0

    const val EARTH_RADIUS_NM = 3440.065

    /**
     * Mean bytes per tile, used for pre-download estimates only.
     *
     * Raster tiles vary enormously — an empty ocean tile is around 1 KB, dense
     * urban can exceed 40 KB — so this is deliberately a mid-range figure and the
     * UI presents estimates as a range, never as a promise. Actual bytes are
     * recorded per segment after download.
     */
    const val ESTIMATED_BYTES_PER_TILE = 12_000L
    const val ESTIMATED_BYTES_PER_TILE_LOW = 8_000L
    const val ESTIMATED_BYTES_PER_TILE_HIGH = 15_000L

    // ── Projection ────────────────────────────────────────────────────────────

    fun lonToTileX(lon: Double, z: Int): Int {
        val n = 1 shl z
        val x = floor((lon + 180.0) / 360.0 * n).toInt()
        return x.coerceIn(0, n - 1)
    }

    fun latToTileY(lat: Double, z: Int): Int {
        val n = 1 shl z
        val clamped = lat.coerceIn(-85.05112878, 85.05112878)
        val rad = Math.toRadians(clamped)
        // asinh(tan φ) is the numerically stable form of ln(tan φ + sec φ).
        val y = floor((1.0 - asinh(kotlin.math.tan(rad)) / PI) / 2.0 * n).toInt()
        return y.coerceIn(0, n - 1)
    }

    fun tileXToLon(x: Int, z: Int): Double = x.toDouble() / (1 shl z) * 360.0 - 180.0

    fun tileYToLat(y: Int, z: Int): Double {
        val n = PI - 2.0 * PI * y / (1 shl z)
        return Math.toDegrees(atan(sinh(n)))
    }

    /** Centre of a tile, used for disc filtering so coverage is round, not square. */
    fun tileCenter(tile: TileRef): Pair<Double, Double> {
        val lonW = tileXToLon(tile.x, tile.z)
        val lonE = tileXToLon(tile.x + 1, tile.z)
        val latN = tileYToLat(tile.y, tile.z)
        val latS = tileYToLat(tile.y + 1, tile.z)
        return (latN + latS) / 2.0 to (lonW + lonE) / 2.0
    }

    // ── Distance ──────────────────────────────────────────────────────────────

    /** Great-circle distance in nautical miles. Same haversine the receiver uses for aircraft range. */
    fun distanceNm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_NM * kotlin.math.atan2(sqrt(a), sqrt(1 - a))
    }

    // ── Coverage ──────────────────────────────────────────────────────────────

    /**
     * Bounding box enclosing a disc of [radiusNm] around a point.
     *
     * Longitude degrees shrink with latitude, hence the cosine term; without it a
     * box computed in California would be far too narrow by the time it is used in
     * British Columbia. Clamped at high latitude where the cosine approaches zero
     * and the correction would explode.
     */
    fun boundsForRadius(lat: Double, lon: Double, radiusNm: Int): GeoBounds {
        val dLat = radiusNm / NM_PER_DEGREE_LAT
        val cosLat = max(cos(Math.toRadians(lat)), 0.01)
        val dLon = min(dLat / cosLat, 180.0)
        return GeoBounds(
            southLat = (lat - dLat).coerceAtLeast(-85.0),
            northLat = (lat + dLat).coerceAtMost(85.0),
            westLon = (lon - dLon).coerceAtLeast(-180.0),
            eastLon = (lon + dLon).coerceAtMost(180.0),
        )
    }

    /**
     * Every tile whose centre falls inside the disc, across [zoomRange].
     *
     * Disc rather than bounding box: a radius download should mean what it says,
     * and the corners of the enclosing square are up to 41 % further out than the
     * requested range — roughly a fifth of the tiles, downloaded for coverage the
     * user never asked for.
     */
    fun tilesForRadius(
        lat: Double,
        lon: Double,
        radiusNm: Int,
        zoomRange: IntRange,
    ): Set<TileRef> {
        val bounds = boundsForRadius(lat, lon, radiusNm)
        val out = LinkedHashSet<TileRef>()
        for (z in zoomRange) {
            val xMin = lonToTileX(bounds.westLon, z)
            val xMax = lonToTileX(bounds.eastLon, z)
            // Tile y increases southward, so the north edge yields the smaller index.
            val yMin = latToTileY(bounds.northLat, z)
            val yMax = latToTileY(bounds.southLat, z)
            for (x in xMin..xMax) {
                for (y in yMin..yMax) {
                    val tile = TileRef(z, x, y)
                    val (cLat, cLon) = tileCenter(tile)
                    // Half a tile diagonal of slack, so a tile straddling the rim is
                    // kept rather than leaving a ragged edge at the boundary.
                    val slack = tileDiagonalNm(tile) / 2.0
                    if (distanceNm(lat, lon, cLat, cLon) <= radiusNm + slack) out += tile
                }
            }
        }
        return out
    }

    /** Tiles covering a bounding box across [zoomRange]. Used for route corridors. */
    fun tilesForBounds(bounds: GeoBounds, zoomRange: IntRange): Set<TileRef> {
        val out = LinkedHashSet<TileRef>()
        for (z in zoomRange) {
            val xMin = lonToTileX(bounds.westLon, z)
            val xMax = lonToTileX(bounds.eastLon, z)
            val yMin = latToTileY(bounds.northLat, z)
            val yMax = latToTileY(bounds.southLat, z)
            for (x in xMin..xMax) for (y in yMin..yMax) out += TileRef(z, x, y)
        }
        return out
    }

    /**
     * Tiles along a travelled path, widened by [corridorNm] either side.
     *
     * Each leg is expanded into its own small box rather than taking one box around
     * the whole route: a long diagonal journey has an enclosing rectangle many times
     * larger than the corridor actually flown, and downloading that would be most of
     * a region the user never went near.
     */
    fun tilesForRoute(
        path: List<Pair<Double, Double>>,
        corridorNm: Int,
        zoomRange: IntRange,
    ): Set<TileRef> {
        if (path.isEmpty()) return emptySet()
        val out = LinkedHashSet<TileRef>()
        if (path.size == 1) {
            val (lat, lon) = path.first()
            return tilesForRadius(lat, lon, corridorNm, zoomRange)
        }
        path.zipWithNext { a, b ->
            val legBounds = GeoBounds(
                southLat = min(a.first, b.first),
                northLat = max(a.first, b.first),
                westLon = min(a.second, b.second),
                eastLon = max(a.second, b.second),
            )
            out += tilesForBounds(expand(legBounds, corridorNm), zoomRange)
        }
        return out
    }

    fun expand(bounds: GeoBounds, marginNm: Int): GeoBounds {
        val dLat = marginNm / NM_PER_DEGREE_LAT
        val midLat = (bounds.southLat + bounds.northLat) / 2.0
        val cosLat = max(cos(Math.toRadians(midLat)), 0.01)
        val dLon = min(dLat / cosLat, 180.0)
        return GeoBounds(
            southLat = (bounds.southLat - dLat).coerceAtLeast(-85.0),
            northLat = (bounds.northLat + dLat).coerceAtMost(85.0),
            westLon = (bounds.westLon - dLon).coerceAtLeast(-180.0),
            eastLon = (bounds.eastLon + dLon).coerceAtMost(180.0),
        )
    }

    fun boundsOf(tiles: Collection<TileRef>): GeoBounds? {
        if (tiles.isEmpty()) return null
        var s = 90.0; var n = -90.0; var w = 180.0; var e = -180.0
        tiles.forEach { t ->
            val latN = tileYToLat(t.y, t.z)
            val latS = tileYToLat(t.y + 1, t.z)
            val lonW = tileXToLon(t.x, t.z)
            val lonE = tileXToLon(t.x + 1, t.z)
            s = min(s, latS); n = max(n, latN); w = min(w, lonW); e = max(e, lonE)
        }
        return GeoBounds(s, n, w, e)
    }

    private fun tileDiagonalNm(tile: TileRef): Double {
        val latN = tileYToLat(tile.y, tile.z)
        val latS = tileYToLat(tile.y + 1, tile.z)
        val lonW = tileXToLon(tile.x, tile.z)
        val lonE = tileXToLon(tile.x + 1, tile.z)
        return distanceNm(latS, lonW, latN, lonE)
    }

    // ── Estimates ─────────────────────────────────────────────────────────────

    fun estimateBytes(tileCount: Int): Long = tileCount * ESTIMATED_BYTES_PER_TILE
    fun estimateBytesLow(tileCount: Int): Long = tileCount * ESTIMATED_BYTES_PER_TILE_LOW
    fun estimateBytesHigh(tileCount: Int): Long = tileCount * ESTIMATED_BYTES_PER_TILE_HIGH

    fun formatBytes(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> "%.2f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576     -> "%.0f MB".format(bytes / 1_048_576.0)
        bytes >= 1024          -> "%.0f KB".format(bytes / 1024.0)
        else                   -> "$bytes B"
    }
}
