package com.laviavi.adsbandroid.aircraft

import com.laviavi.adsbandroid.crc.CrcChecker
import com.laviavi.adsbandroid.enrich.DataSource
import com.laviavi.adsbandroid.enrich.OperatorKind

/**
 * Live state of a single tracked aircraft.
 * Immutable data class — AircraftManager always produces a new copy on update.
 * Annotated @Immutable in the Android module so Compose skips unnecessary recomposition.
 */
data class AircraftState(
    val icao: String,
    val callsign: String?          = null,
    val registration: String?      = null,
    val operator: String?          = null,
    val country: String?           = null,
    val aircraftType: String?      = null,
    val category: Int?             = null,

    // Position
    val latitude: Double?          = null,
    val longitude: Double?         = null,
    val altitudeFt: Int?           = null,
    /** TC 20-22 GNSS/geometric altitude — kept separate from [altitudeFt] (barometric), matching the reference. */
    val altitudeGnssFt: Int?       = null,
    val onGround: Boolean          = false,

    // Velocity
    val groundSpeedKt: Int?        = null,
    val trackDeg: Int?             = null,
    /** TC19 subtype 3/4 airspeed — distinct from [groundSpeedKt]; see [speedType]. */
    val airspeedKt: Int?           = null,
    /** TC19 subtype 3/4 magnetic heading — distinct from [trackDeg]'s true track. */
    val headingDeg: Int?           = null,
    /** `"ground"` | `"airspeed_ias"` | `"airspeed_tas"`. */
    val speedType: String?         = null,
    val verticalRateFpm: Int?      = null,

    // Transponder
    /** 4-digit octal squawk, already formatted by the decoder (e.g. "6272"). */
    val squawk: String?            = null,
    val downlinkFormat: Int?       = null,
    val typecode: Int?             = null,

    // TC29 — Target state
    val selectedAltitudeFt: Int?   = null,
    val selectedHeadingDeg: Int?   = null,
    val autoPilotEngaged: Boolean  = false,
    val baroSettingMbar: Float?    = null,

    // TC31 — Aircraft operational status
    val nacP: Int?                 = null,
    val nacV: Int?                 = null,
    val sil: Int?                  = null,
    val gva: Int?                  = null,
    val tcasOperational: Boolean   = false,
    val versionNumber: Int?        = null,

    // DF0 / DF16 — TCAS air-air surveillance and resolution advisories.
    // Distinct from tcasOperational above (TC31's "is TCAS equipped" bit) —
    // these describe an advisory actually in progress right now.
    val tcasSl: Int?               = null,
    val tcasRaActive: Boolean      = false,
    val tcasRaText: String?        = null,
    val tcasRaComplement: String?  = null,
    val tcasRaTerminated: Boolean  = false,
    /** Intruder aircraft resolved from the DF16 AP field — see `MessageDecoder.resolveApIcao`. */
    val tcasTargetIcao: String?    = null,
    /** Counts rising edges into [tcasRaActive], not every DF16 frame received during one. */
    val tcasEventCount: Int        = 0,

    // Comm-B (DF20/21 GICB registers)
    val rollAngleDeg: Double?              = null,
    val trackAngleRateDegPerSec: Double?   = null,
    val trueAirspeedKt: Int?               = null,
    val magneticHeadingDeg: Double?        = null,
    val indicatedAirspeedKt: Int?          = null,
    val machNumber: Double?                = null,
    val lastBdsCode: String?               = null,

    // DF11 — Interrogator identifiers (set of II codes, matching Python's set)
    val iiCode: Int?               = null,
    val interrogatorIds: Set<Int>  = emptySet(),

    // Signal / stats
    val messageCount: Int          = 0,
    val lastCrcResult: CrcChecker.CrcResult = CrcChecker.CrcResult.VALID,
    /** dBFS (amplitude): `20 * log10(ratio)`, clamped to [-40, 0]. Null until first frame. */
    val signalDbfs: Double?        = null,
    /** Rolling window of the last 20 signal readings (linear 0–1 ratio), for averaging. */
    val signalHistory: List<Double> = emptyList(),
    val lastSeenMs: Long           = 0L,
    val firstSeenMs: Long          = 0L,
    val lastPositionMs: Long?      = null,

    // Per-aircraft CRC counters, matching Python's valid_count/corrected_count/bad_crc_count.
    val validCount: Int            = 0,
    val correctedCount: Int        = 0,
    val badCrcCount: Int           = 0,

    /** Last 50 message summaries, newest last. Matching Python's `message_history` deque(50). */
    val messageHistory: List<MessageSummary> = emptyList(),

    /**
     * Recent decoded positions, oldest first — the backing data for map trails.
     *
     * Android-only addition with no Python counterpart: the reference CLI draws no
     * map. Bounded to [MAX_POSITION_HISTORY] so a long-lived aircraft cannot grow
     * without limit, and excluded from the parity harness's compared-field list.
     */
    val positionHistory: List<TrackPoint> = emptyList(),

    // Derived geometry
    val distanceNm: Double?        = null,
    val bearingDeg: Double?        = null,

    // Enrichment. Each value carries the source it came from so the UI can
    // distinguish something the aircraft transmitted from something we inferred.
    val route: String?             = null,
    val routeSource: DataSource?   = null,
    val registrationSource: DataSource? = null,
    val operatorSource: DataSource?     = null,
    /** Whether [operator] names an airline or just the registered owner — see [OperatorKind]. */
    val operatorKind: OperatorKind?     = null,
) {
    /** Average of the linear signal history, matching Python's `avg_signal` property. */
    val avgSignal: Double? get() = signalHistory.takeIf { it.isNotEmpty() }?.average()

    /** Average signal in dBFS — the display-ready version of [avgSignal]. */
    val avgSignalDbfs: Double? get() = avgSignal?.let {
        if (it > 0.0) (20.0 * kotlin.math.log10(it)).coerceAtLeast(-40.0) else null
    }

    val interrogatorIdsStr: String get() =
        if (interrogatorIds.isEmpty()) "" else interrogatorIds.sorted().joinToString(";")

    companion object {
        const val MAX_HISTORY = 50
        const val MAX_SIGNAL_HISTORY = 60
        /** Longest trail the map can draw (its own N ∈ {0,10,50,200} selects a suffix of this). */
        const val MAX_POSITION_HISTORY = 200
    }
}

/** One decoded position fix, for map trails. */
data class TrackPoint(
    val latitude: Double,
    val longitude: Double,
    val timestampMs: Long,
)

data class MessageSummary(
    val timestampMs: Long,
    val downlinkFormat: Int,
    val typecode: Int? = null,
    val crcResult: CrcChecker.CrcResult = CrcChecker.CrcResult.VALID,
    val signalLevel: Double = 0.0,
)
