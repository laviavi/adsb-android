package com.laviavi.adsbandroid.ui.model

import com.laviavi.adsbandroid.aircraft.AircraftState
import com.laviavi.adsbandroid.pipeline.PipelineStats
import com.laviavi.adsbandroid.pipeline.SourceState
import com.laviavi.adsbandroid.units.DistanceUnit

object UiMapper {

    private const val FRESH_THRESHOLD_MS = 5_000L
    private const val AGEING_THRESHOLD_MS = 15_000L
    private val EMERGENCY_SQUAWKS = setOf("7500", "7600", "7700")

    fun mapRow(
        state: AircraftState,
        nowMs: Long,
        unit: DistanceUnit = DistanceUnit.MILES,
    ): AircraftRowUi {
        val ageMs = nowMs - state.lastSeenMs
        val ageTier = when {
            ageMs <= FRESH_THRESHOLD_MS -> AgeTier.FRESH
            ageMs <= AGEING_THRESHOLD_MS -> AgeTier.AGEING
            else -> AgeTier.STALE
        }
        return AircraftRowUi(
            icao = state.icao,
            callsign = state.callsign,
            typeCode = state.aircraftType,
            registration = state.registration,
            registrationMark = state.registrationSource,
            operator = state.operator,
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
    ): MapMarker? {
        val lat = state.latitude ?: return null
        val lon = state.longitude ?: return null
        val ra = state.tcasRaActive
        val trail = if (trailLength <= 0) emptyList()
        else state.positionHistory.takeLast(trailLength)

        val altLabel = when {
            state.onGround -> "GND"
            state.altitudeFt != null -> "${state.altitudeFt!! / 100}"
            else -> null
        }
        val label = state.callsign?.let { cs ->
            when {
                ra -> "$cs RA"
                altLabel != null -> "$cs $altLabel"
                else -> cs
            }
        }

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
            onGround = state.onGround,
            isStale = (nowMs - state.lastSeenMs) > AGEING_THRESHOLD_MS,
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

    /**
     * [sessionMaxRangeNm] is the running max for the current receiver session
     * (survives an aircraft leaving range; resets only on app start or a
     * dongle reconnect — see `PipelineService.clearSessionState()`), not an
     * instantaneous max over [aircraft].
     */
    fun mapMetrics(
        aircraft: List<AircraftState>,
        stats: PipelineStats.Snapshot,
        sparkline: List<Float>,
        config: com.laviavi.adsbandroid.pipeline.AppConfig,
        sessionMaxRangeNm: Double? = null,
    ): LiveMetrics {
        return LiveMetrics(
            trackedCount = aircraft.size,
            framesPerSecond = stats.messagesPerSecond.toInt().toString(),
            validPercent = if (stats.windowTested > 0)
                "%.1f".format(stats.windowAcceptRatePercent) else "0",
            maxRangeMi = sessionMaxRangeNm?.let { config.distanceUnit.formatValue(it) } ?: "0",
            gainDb = "---", // filled from GainOptions by caller
            sparklineData = sparkline,
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
            gainDb = "---",
            uptime = formatUptime(stats.uptimeMs),
            msgRate = "${stats.messagesPerSecond.toInt()}/s",
            crcPercent = if (stats.windowTested > 0)
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
