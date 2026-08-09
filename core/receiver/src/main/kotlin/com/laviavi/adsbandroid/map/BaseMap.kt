package com.laviavi.adsbandroid.map

/**
 * Live-map tile source. Both are plain `{z}/{x}/{y}`-style raster XYZ services —
 * Google is deliberately not offered here: it requires the actual Maps SDK (a
 * separate widget with its own overlay API), not a pluggable tile source.
 *
 * @param labelUrlTemplate a transparent-background companion layer of
 *   city/place labels and boundaries, drawn on top of [urlTemplate]. Null for
 *   OSM, whose own tiles already carry labels; Esri's raw satellite imagery
 *   has none, so it needs this second layer or it reads as a blank photo.
 */
enum class BaseMap(
    val label: String,
    val urlTemplate: String,
    val attribution: String,
    val labelUrlTemplate: String? = null,
) {
    OSM(
        "OpenStreetMap",
        "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
        "© OpenStreetMap contributors",
    ),
    ESRI_IMAGERY(
        "Esri World Imagery",
        "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
        "Esri, Maxar, Earthstar Geographics",
        labelUrlTemplate = "https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Boundaries_and_Places/MapServer/tile/{z}/{y}/{x}",
    ),
    ESRI_STREET(
        "Esri World Street Map",
        "https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/{z}/{y}/{x}",
        "Esri, HERE, Garmin, FAO, NOAA, USGS",
    ),
    CARTO_DARK(
        "CARTO Dark Matter",
        "https://basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png",
        "© CARTO, © OpenStreetMap contributors",
    ),
    CARTO_VOYAGER(
        "CARTO Voyager",
        "https://basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}.png",
        "© CARTO, © OpenStreetMap contributors",
    ),
    CARTO_POSITRON(
        "CARTO Positron",
        "https://basemaps.cartocdn.com/light_all/{z}/{x}/{y}.png",
        "© CARTO, © OpenStreetMap contributors",
    ),
}
