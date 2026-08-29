package com.laviavi.adsbandroid.ui.map

/**
 * Which pieces of data appear in an aircraft's map label, user-selectable and
 * independently toggleable. An empty selection means no label at all — this
 * replaced a single "Callsign labels" on/off switch, which only ever showed
 * callsign + altitude with no way to add or drop either piece.
 */
enum class MapLabelField(val label: String) {
    CALLSIGN("Callsign"),
    ALTITUDE("Altitude"),
    DESTINATION("Destination"),
}
