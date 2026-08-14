package com.laviavi.adsbandroid.ui.map

/**
 * Font-size override for the basemap's own labels (place names, road names, etc —
 * not the app's aircraft callsign/ring labels, those are styled separately in
 * [AircraftMapLayer]). [DEFAULT] means "don't touch" — OpenFreeMap's own per-layer,
 * zoom-responsive sizing (e.g. country names bigger than village names) is left as
 * authored. The other options flatten every label layer to one fixed size.
 */
enum class MapLabelSize(val label: String, val px: Float?) {
    DEFAULT("Default", null),
    SMALL("Small", 10f),
    MEDIUM("Medium", 13f),
    LARGE("Large", 16f),
}
