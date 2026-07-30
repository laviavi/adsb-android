package com.laviavi.adsbandroid.units

import kotlin.math.roundToInt

/**
 * Display unit for every distance the UI shows (range, bearing rings, coverage).
 *
 * The receiver computes distance in nautical miles throughout — that is what the
 * geometry code produces and what [AircraftState.distanceNm] carries. This enum is
 * a presentation-layer conversion applied at format time only; no decoded value is
 * ever stored in a display unit.
 */
enum class DistanceUnit(val label: String, val perNauticalMile: Double) {
    MILES("mi", 1.15077945),
    NAUTICAL("nm", 1.0),
    KILOMETERS("km", 1.852);

    fun fromNm(nm: Double): Double = nm * perNauticalMile

    fun toNm(value: Double): Double = value / perNauticalMile

    /** `14.2` — one decimal, no unit suffix (column headers carry the unit). */
    fun formatValue(nm: Double): String = formatOneDecimal(fromNm(nm))

    /** `14.2 mi` — value and unit together, for inline text. */
    fun format(nm: Double): String = "${formatValue(nm)} $label"

    /** `68 mi` — whole units, for range rings and coverage vertex labels. */
    fun formatWhole(nm: Double): String = "${fromNm(nm).roundToInt()} $label"

    private fun formatOneDecimal(v: Double): String {
        val scaled = (v * 10.0).roundToInt()
        return "${scaled / 10}.${(if (scaled < 0) -scaled else scaled) % 10}"
    }
}
