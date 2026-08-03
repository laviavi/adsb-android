package com.laviavi.adsbandroid.ui.stats

import com.laviavi.adsbandroid.data.AircraftVisitEntity

/**
 * One row per distinct ICAO across every [AircraftVisitEntity] ever recorded.
 * Identity fields (registration/operator/aircraftType/isAirline) come from that
 * aircraft's most recent visit — the freshest enrichment wins, same as the live
 * table does moment to moment.
 */
data class AircraftSummary(
    val icao: String,
    val registration: String?,
    val operator: String?,
    val aircraftType: String?,
    val isAirline: Boolean,
    val timesSeen: Int,
    val firstSeenEverMs: Long,
    val lastSeenEverMs: Long,
)

/** Groups a flat visit log into one summary per aircraft. Pure — no I/O, no Compose. */
fun summarizeVisits(visits: List<AircraftVisitEntity>): List<AircraftSummary> =
    visits.groupBy { it.icao }.map { (icao, group) ->
        val latest = group.maxBy { it.firstSeenMs }
        AircraftSummary(
            icao = icao,
            registration = latest.registration,
            operator = latest.operator,
            aircraftType = latest.aircraftType,
            isAirline = latest.isAirline,
            timesSeen = group.size,
            firstSeenEverMs = group.minOf { it.firstSeenMs },
            lastSeenEverMs = group.maxOf { it.lastSeenMs },
        )
    }
