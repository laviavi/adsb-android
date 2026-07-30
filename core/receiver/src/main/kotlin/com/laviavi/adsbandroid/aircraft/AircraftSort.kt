package com.laviavi.adsbandroid.aircraft

/**
 * Live-list display order, chosen by the user in Settings.
 *
 * [FIRST_SEEN] is the default. It is the only order that does not reshuffle rows
 * while someone is looking at them — distance, altitude and message count all
 * change continuously in flight, so any live sort key makes rows swap places
 * under a finger about to tap one. The Python reference makes the same choice
 * deliberately: `ui/summary.py:_update_active_order` keeps a stable append-only
 * order for exactly this reason, and only appends newly-seen ICAOs rather than
 * re-sorting the whole table on every redraw.
 *
 * This enum and [AircraftSort] exist so that ordering is a presentation concern,
 * not something [AircraftManager] bakes into its state. Previously
 * `AircraftManager.aircraft` always returned nearest-first order, computed by a
 * full re-sort on every decoded message — sorting is now applied once, by
 * whichever layer is about to display the list, at whatever rate that layer
 * actually redraws.
 */
enum class AircraftSortOrder(val label: String) {
    FIRST_SEEN("First seen"),
    NEAREST("Nearest first"),
    ALTITUDE("Altitude, highest first"),
    CALLSIGN("Callsign, A–Z"),
    MESSAGE_COUNT("Message count, most first"),
    LAST_SEEN("Last seen, most recent first"),
}

/**
 * Applies an [AircraftSortOrder] to a snapshot of tracked aircraft.
 *
 * Pure and stateless — takes a list, returns a list — so it can be tested without
 * an [AircraftManager] and reused wherever a sorted view is needed (Settings
 * preview, a future Live screen, CSV export ordering).
 */
object AircraftSort {

    fun apply(aircraft: List<AircraftState>, order: AircraftSortOrder): List<AircraftState> =
        when (order) {
            // AircraftManager.aircraft is already first-seen (table insertion)
            // order, so there is nothing to do — this is the identity case, not
            // an oversight.
            AircraftSortOrder.FIRST_SEEN -> aircraft

            AircraftSortOrder.NEAREST -> aircraft.sortedWith(
                compareBy<AircraftState> { it.distanceNm ?: Double.MAX_VALUE }
                    .thenByDescending { it.lastSeenMs }
            )

            AircraftSortOrder.ALTITUDE -> aircraft.sortedWith(
                compareByDescending { it.altitudeFt ?: Int.MIN_VALUE }
            )

            AircraftSortOrder.CALLSIGN -> aircraft.sortedWith(
                // Blank/unknown callsigns sort last regardless of case, rather
                // than clustering at the top of an alphabetical sort.
                compareBy<AircraftState> { it.callsign?.trim().isNullOrEmpty() }
                    .thenBy { it.callsign?.trim()?.uppercase() ?: "" }
            )

            AircraftSortOrder.MESSAGE_COUNT -> aircraft.sortedByDescending { it.messageCount }

            AircraftSortOrder.LAST_SEEN -> aircraft.sortedByDescending { it.lastSeenMs }
        }
}
