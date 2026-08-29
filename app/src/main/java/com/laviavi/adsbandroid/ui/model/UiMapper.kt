package com.laviavi.adsbandroid.ui.model

import com.laviavi.adsbandroid.aircraft.AircraftState
import com.laviavi.adsbandroid.aircraft.routeDestination
import com.laviavi.adsbandroid.pipeline.PipelineStats
import com.laviavi.adsbandroid.pipeline.SourceState
import com.laviavi.adsbandroid.ui.map.MapLabelField
import com.laviavi.adsbandroid.units.DistanceUnit

object UiMapper {

    private const val FRESH_THRESHOLD_MS = 5_000L
    private val EMERGENCY_SQUAWKS = setOf("7500", "7600", "7700")

    /**
     * @param dropAfterSeconds the live config's "Drop aircraft after" threshold
     * (`AppConfig.aircraftExpirySeconds`). STALE starts one second past it, so a
     * row reaches STALE (and its grey-out) only in the up-to-30s window between
     * crossing that threshold and the periodic expiry sweep actually removing it
     * — rather than the old fixed 15s, which greyed rows out for most of their
     * live-list lifetime regardless of the drop setting.
     */
    fun mapRow(
        state: AircraftState,
        nowMs: Long,
        unit: DistanceUnit = DistanceUnit.MILES,
        dropAfterSeconds: Int,
    ): AircraftRowUi {
        val ageMs = nowMs - state.lastSeenMs
        val ageingThresholdMs = (dropAfterSeconds + 1) * 1_000L
        val ageTier = when {
            ageMs <= FRESH_THRESHOLD_MS -> AgeTier.FRESH
            ageMs <= ageingThresholdMs -> AgeTier.AGEING
            else -> AgeTier.STALE
        }
        return AircraftRowUi(
            icao = state.icao,
            callsign = state.callsign,
            typeCode = state.aircraftType,
            registration = state.registration,
            registrationMark = state.registrationSource,
            operator = state.operator,
            operatorKind = state.operatorKind,
            route = state.route,
            routeMark = state.routeSource,
            altitude = formatAltitude(state.altitudeFt, state.onGround),
            vsArrow = run {
                val vr = state.verticalRateFpm
                when {
                    vr == null -> VsArrow.UNKNOWN
                    vr > 200 -> VsArrow.UP
                    vr < -200 -> VsArrow.DOWN
                    else -> VsArrow.LEVEL
                }
            },
            speed = state.groundSpeedKt?.let { "$it kt" } ?: "---",
            distance = state.distanceNm?.let { unit.formatValue(it) } ?: "---",
            distanceUnit = unit.label,
            bearing = state.bearingDeg?.let { "${it.toInt().toString().padStart(3, '0')}°" } ?: "---",
            bearingDeg = state.bearingDeg,
            signalBars = signalToBars(state.signalDbfs),
            messageCount = "${state.messageCount} msgs",
            age = formatAge(ageMs),
            ageTier = ageTier,
            emergency = state.squawk in EMERGENCY_SQUAWKS,
            raActive = state.tcasRaActive,
            raText = state.tcasRaText,
            onGround = state.onGround,
            hasPosition = state.latitude != null && state.longitude != null,
        )
    }

    fun mapMarker(
        state: AircraftState,
        nowMs: Long,
        unit: DistanceUnit = DistanceUnit.MILES,
        trailLength: Int = 0,
        dropAfterSeconds: Int,
        labelFields: Set<MapLabelField> = setOf(MapLabelField.CALLSIGN, MapLabelField.ALTITUDE),
    ): MapMarker? {
        val lat = state.latitude ?: return null
        val lon = state.longitude ?: return null
        val ra = state.tcasRaActive
        val trail = if (trailLength <= 0) emptyList()
        else state.positionHistory.takeLast(trailLength)

        val labelParts = buildList {
            // No callsign yet (it only exists once a DF17 ident message decodes, which
            // can lag well behind position) — fall back to registration, which is
            // already resolved offline-first from the global aircraft mirror
            // (AircraftMetaEnrichment.lookup(), not US-only), then the bare ICAO hex
            // as a last resort that's always available for any aircraft, anywhere.
            if (MapLabelField.CALLSIGN in labelFields) {
                add(
                    state.callsign?.trim()?.takeIf { it.isNotEmpty() }
                        ?: state.registration?.trim()?.takeIf { it.isNotEmpty() }
                        ?: state.icao
                )
            }
            if (MapLabelField.ALTITUDE in labelFields) {
                when {
                    state.onGround -> add("GND")
                    // Full altitude, not the flight-level-style /100 shorthand (e.g. "140" for
                    // 14,000 ft) — indistinguishable from a 3-digit reading at a glance.
                    state.altitudeFt != null -> add("%,d".format(state.altitudeFt))
                }
            }
            if (MapLabelField.DESTINATION in labelFields) {
                routeDestination(state.route)?.let { add(it) }
            }
            // RA is a safety-critical advisory — always shown regardless of field selection.
            if (ra) add("RA")
        }
        val label = labelParts.joinToString(" ").ifBlank { null }

        val vs = when {
            state.verticalRateFpm == null -> ""
            state.verticalRateFpm!! > 200 -> " ↑"
            state.verticalRateFpm!! < -200 -> " ↓"
            else -> ""
        }
        val detail = buildString {
            append(formatAltitude(state.altitudeFt, state.onGround))
            append(vs)
            state.groundSpeedKt?.let { append(" · $it kt") }
            if (trail.isNotEmpty()) append(" · trail ${trail.size} pts")
        }

        return MapMarker(
            icao = state.icao,
            lat = lat,
            lon = lon,
            trackDeg = state.trackDeg,
            altitudeFt = state.altitudeFt,
            callsign = state.callsign,
            registration = state.registration,
            route = state.route,
            onGround = state.onGround,
            isStale = (nowMs - state.lastSeenMs) > (dropAfterSeconds + 1) * 1_000L,
            emergency = state.squawk in EMERGENCY_SQUAWKS,
            raActive = ra,
            label = label,
            trail = trail,
            distanceBearing = state.distanceNm?.let { d ->
                val b = state.bearingDeg
                if (b == null) unit.format(d)
                else "${unit.format(d)} · ${b.toInt().toString().padStart(3, '0')}°"
            },
            detailLine = detail,
        )
    }

    fun mapReceiverStatus(
        sourceState: SourceState,
        stats: PipelineStats.Snapshot,
    ): ReceiverStatusUi {
        val (state, label, sourceName, errorMsg) = when (sourceState) {
            is SourceState.Running -> Quad(ReceiverState.RUNNING, "RUNNING", sourceState.sourceName, null)
            is SourceState.Connecting -> Quad(ReceiverState.STARTING, "STARTING", null, null)
            is SourceState.DriverNotInstalled -> Quad(ReceiverState.NO_SDR, "NO SDR", null, null)
            is SourceState.Error -> Quad(ReceiverState.ERROR, "ERROR", null, sourceState.message)
            is SourceState.Idle -> Quad(ReceiverState.STOPPED, "STOPPED", null, null)
        }
        return ReceiverStatusUi(
            state = state,
            stateLabel = label,
            uptime = formatUptime(stats.uptimeMs),
            msgRate = "${stats.messagesPerSecond.toInt()}",
            validPercent = if (stats.windowTested > 0)
                "%.1f%%".format(stats.windowAcceptRatePercent) else "0%",
            sourceName = sourceName,
            errorMessage = errorMsg,
        )
    }

    private fun formatAltitude(ft: Int?, onGround: Boolean): String = when {
        onGround -> "GND"
        ft == null -> "---"
        ft >= 18000 -> "FL${ft / 100}"
        else -> "$ft"
    }

    private fun formatAge(ms: Long): String = when {
        ms < 1000 -> "<1s"
        ms < 60_000 -> "${ms / 1000}s"
        ms < 3600_000 -> "${ms / 60_000}m"
        else -> "${ms / 3600_000}h"
    }

    private fun signalToBars(dbfs: Double?): Int {
        if (dbfs == null) return 0
        return when {
            dbfs >= -6 -> 3
            dbfs >= -15 -> 2
            dbfs >= -25 -> 1
            else -> 0
        }
    }

    fun formatUptime(ms: Long): String {
        val s = ms / 1000
        return "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
