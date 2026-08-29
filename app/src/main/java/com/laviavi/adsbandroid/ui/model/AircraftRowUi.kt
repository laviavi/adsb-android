package com.laviavi.adsbandroid.ui.model

import androidx.compose.runtime.Immutable
import com.laviavi.adsbandroid.enrich.DataSource
import com.laviavi.adsbandroid.enrich.OperatorKind

@Immutable
data class AircraftRowUi(
    val icao: String,
    val callsign: String?,
    val typeCode: String?,
    val registration: String?,
    val registrationMark: DataSource?,
    val operator: String?,
    /** Whether [operator] names an airline or just the registered owner. */
    val operatorKind: OperatorKind?,
    val route: String?,
    val routeMark: DataSource?,
    val altitude: String,
    val vsArrow: VsArrow,
    val speed: String,
    val distance: String,
    /** `mi` / `km` / `nm` — the column header carries the unit, the value does not. */
    val distanceUnit: String,
    val bearing: String,
    /** Raw degrees behind [bearing]'s formatted text, for rotating the bearing arrow. */
    val bearingDeg: Double?,
    val signalBars: Int,
    val messageCount: String,
    val age: String,
    val ageTier: AgeTier,
    val emergency: Boolean,
    val raActive: Boolean,
    val raText: String?,
    val onGround: Boolean,
    val hasPosition: Boolean,
)

enum class VsArrow { UP, LEVEL, DOWN, UNKNOWN }
enum class AgeTier { FRESH, AGEING, STALE }
