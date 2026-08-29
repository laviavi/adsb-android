package com.laviavi.adsbandroid.ui.model

import androidx.compose.runtime.Immutable
import com.laviavi.adsbandroid.aircraft.TrackPoint

/**
 * One aircraft as the map needs it. Every string is pre-formatted by `UiMapper` on
 * `Dispatchers.Default` — the map overlay places values and never computes them.
 */
@Immutable
data class MapMarker(
    val icao: String,
    val lat: Double,
    val lon: Double,
    val trackDeg: Int?,
    val altitudeFt: Int?,
    val callsign: String?,
    val registration: String?,
    val route: String?,
    val onGround: Boolean,
    val isStale: Boolean,
    val emergency: Boolean,
    val raActive: Boolean,
    /**
     * Space-joined from whichever of [com.laviavi.adsbandroid.ui.map.MapLabelField]
     * the user has selected (e.g. `UAL2184 350`, `UAL2184 LAX`), plus a trailing `RA`
     * during an advisory regardless of selection. Null when nothing applies.
     */
    val label: String?,
    /** Oldest first, already truncated to the configured trail length. */
    val trail: List<TrackPoint> = emptyList(),
    /** `14.2 mi · 073°` for the selection sheet. */
    val distanceBearing: String? = null,
    /** `FL350 ↑ · 442 kt · trail 50 pts` for the selection sheet. */
    val detailLine: String = "",
) {
    /** Marker shape, chosen so state never depends on colour alone (spec §Map). */
    val shape: MarkerShape get() = when {
        raActive || emergency -> MarkerShape.TRIANGLE
        onGround -> MarkerShape.SQUARE
        trackDeg == null -> MarkerShape.CIRCLE
        else -> MarkerShape.PLANE
    }
}

/**
 * `CIRCLE` is used whenever track is unknown — never a triangle, which would point
 * at a heading the receiver never decoded.
 */
enum class MarkerShape { PLANE, SQUARE, CIRCLE, TRIANGLE }
