package com.laviavi.adsbandroid.map

/**
 * Live-map tile source. Both are plain `{z}/{x}/{y}`-style raster XYZ services —
 * Google is deliberately not offered here: it requires the actual Maps SDK (a
 * separate widget with its own overlay API), not a pluggable tile source.
 */
enum class BaseMap(val label: String, val urlTemplate: String, val attribution: String) {
    OSM(
        "OpenStreetMap",
        "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
        "© OpenStreetMap contributors",
    ),
    ESRI_IMAGERY(
        "Esri World Imagery",
        "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
        "Esri, Maxar, Earthstar Geographics",
    ),
}
