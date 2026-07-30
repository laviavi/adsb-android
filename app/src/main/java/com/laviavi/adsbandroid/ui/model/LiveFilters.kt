package com.laviavi.adsbandroid.ui.model

import androidx.compose.runtime.Immutable
import com.laviavi.adsbandroid.aircraft.AircraftState

/**
 * Altitude bands offered by the Live filter row.
 *
 * A band rather than a free numeric range: the useful question on this screen is
 * "circuit traffic / airway traffic / overflights", not an arbitrary cut.
 */
enum class AltitudeBand(val label: String, private val range: IntRange?) {
    ANY("Altitude", null),
    LOW("< FL100", 0..9_999),
    MID("FL100–FL250", 10_000..24_999),
    HIGH("> FL250", 25_000..Int.MAX_VALUE);

    /** Unknown altitude never matches a specific band — absence is not zero. */
    fun matches(altitudeFt: Int?): Boolean {
        val r = range ?: return true
        return altitudeFt != null && altitudeFt in r
    }
}

/**
 * The Live screen's filter chips. Chips AND together, per the spec — each one
 * narrows what is already showing rather than adding to it.
 */
@Immutable
data class LiveFilters(
    val airborne: Boolean = false,
    val onGround: Boolean = false,
    val withPosition: Boolean = false,
    val emergency: Boolean = false,
    val within50Mi: Boolean = false,
    val altitudeBand: AltitudeBand = AltitudeBand.ANY,
) {
    val isActive: Boolean
        get() = airborne || onGround || withPosition || emergency || within50Mi ||
            altitudeBand != AltitudeBand.ANY

    companion object {
        /** Statute miles, as the chip is labelled; the state carries nautical miles. */
        const val NEAR_RADIUS_NM = 50.0 / 1.15077945

        private val EMERGENCY_SQUAWKS = setOf("7500", "7600", "7700")

        /**
         * Airborne and On ground are both offered even though they look like a pair:
         * selecting both is how you say "anything with a known air/ground state",
         * and selecting neither is the unfiltered default. Treating them as a toggle
         * would make the second state unreachable.
         */
        fun matches(state: AircraftState, filters: LiveFilters): Boolean {
            if (filters.airborne && state.onGround) return false
            if (filters.onGround && !state.onGround) return false
            if (filters.withPosition && (state.latitude == null || state.longitude == null)) return false
            if (filters.emergency && state.squawk !in EMERGENCY_SQUAWKS && !state.tcasRaActive) return false
            if (filters.within50Mi) {
                val d = state.distanceNm ?: return false
                if (d > NEAR_RADIUS_NM) return false
            }
            if (!filters.altitudeBand.matches(state.altitudeFt)) return false
            return true
        }
    }
}
