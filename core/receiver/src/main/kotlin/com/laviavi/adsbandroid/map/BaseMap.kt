package com.laviavi.adsbandroid.map

/**
 * Live-map style. A full MapLibre style JSON URL (vector tiles + sprite + glyphs),
 * not a raster tile template — v2.0 moved off osmdroid/raster tiles onto MapLibre
 * + OpenFreeMap, which ships genuinely different-looking styles (including a real
 * dark style) instead of one raster source plus a client-side color-invert hack.
 */
enum class BaseMap(
    val label: String,
    val styleUrl: String,
    val attribution: String,
) {
    LIBERTY(
        "OpenFreeMap Liberty",
        "https://tiles.openfreemap.org/styles/liberty",
        "© OpenFreeMap, © OpenMapTiles, © OpenStreetMap contributors",
    ),
    BRIGHT(
        "OpenFreeMap Bright",
        "https://tiles.openfreemap.org/styles/bright",
        "© OpenFreeMap, © OpenMapTiles, © OpenStreetMap contributors",
    ),
    POSITRON(
        "OpenFreeMap Positron",
        "https://tiles.openfreemap.org/styles/positron",
        "© OpenFreeMap, © OpenMapTiles, © OpenStreetMap contributors",
    ),
    DARK(
        "OpenFreeMap Dark",
        "https://tiles.openfreemap.org/styles/dark",
        "© OpenFreeMap, © OpenMapTiles, © OpenStreetMap contributors",
    ),
}
