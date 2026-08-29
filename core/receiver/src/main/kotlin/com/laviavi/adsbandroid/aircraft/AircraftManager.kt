package com.laviavi.adsbandroid.aircraft

import com.laviavi.adsbandroid.enrich.DataSource
import com.laviavi.adsbandroid.enrich.OfflineEnrichment
import com.laviavi.adsbandroid.enrich.OperatorKind
import com.laviavi.adsbandroid.crc.CrcChecker
import com.laviavi.adsbandroid.decoder.*
import kotlin.math.log10

/**
 * Maintains the live aircraft state table.
 * Merges decoded messages into [AircraftState] records.
 * Thread-safe: all mutations happen on caller's coroutine; use a single-threaded
 * dispatcher or synchronize externally in PipelineService.
 *
 * Ports Python aircraft/manager.py.
 */
class AircraftManager(expirySeconds: Int = 60, var decoder: MessageDecoder? = null) {

    /**
     * Seconds of silence before an aircraft is dropped. A var, not a constructor
     * val: the setting is changed at runtime and must apply without restarting
     * the pipeline (see ConfigChange.requiresPipelineRestart).
     */
    var expirySeconds: Int = expirySeconds

    var observerLat: Double = 0.0
    var observerLon: Double = 0.0

    private var lookup: Map<String, IcaoEntry> = emptyMap()
    fun setLookup(map: Map<String, IcaoEntry>) { lookup = map }

    private val table = LinkedHashMap<String, AircraftState>()

    /**
     * Snapshot of tracked aircraft in first-seen order.
     *
     * `LinkedHashMap` preserves insertion order and does not reorder a key on a
     * re-`put` (only a genuinely new key is appended), so this order is stable
     * for the life of the aircraft: it never changes because of an update, only
     * because a new ICAO appeared.
     *
     * Display order used to be baked in here — nearest-first, ties broken by
     * recency — recomputed by a full sort on every decoded message. That mixed a
     * presentation choice into domain state and offered no other order. Sorting
     * is now the caller's job: see [AircraftSort].
     */
    val aircraft: List<AircraftState> get() = table.values.toList()

    /** Total aircraft ever seen (including expired). */
    var totalSeen: Int = 0; private set

    /**
     * Merge a decoded message into the state table.
     * Creates a new entry if ICAO is not yet tracked.
     * Returns the updated [AircraftState].
     */
    /**
     * Merge a decoded message into the table.
     *
     * [nowMs] is the frame's reception time. It is a parameter rather than a
     * wall-clock read so a recorded capture can be replayed on a virtual clock
     * and diffed against the Python reference — expiry, CPR pair ageing and
     * staleness are all measured from it, and none of that is testable against a
     * clock that advances with the test runner.
     */
    fun update(message: DecodedMessage, nowMs: Long = System.currentTimeMillis()): AircraftState {
        val icaoHex = message.icao.toString(16).uppercase().padStart(6, '0')
        val now = nowMs
        val existing = table[icaoHex] ?: run {
            totalSeen++
            AircraftState(icao = icaoHex, firstSeenMs = now)
        }
        val merged = mergeMessage(existing, message, now)
        val updated = applyEnrichment(merged)
        table[icaoHex] = updated
        return updated
    }

    /**
     * Remove aircraft not seen within [expirySeconds] and return their final
     * states, newest-seen first.
     *
     * The states are returned rather than just counted because expiry is the only
     * moment the last-known values are still available — an aircraft that never
     * reported a position is not in the history table, so if it is dropped here
     * without being handed back it leaves no trace at all.
     */
    fun expireStale(nowMs: Long = System.currentTimeMillis()): List<AircraftState> {
        val cutoff = nowMs - expirySeconds * 1000L
        val expired = table.values.filter { it.lastSeenMs < cutoff }
            .sortedByDescending { it.lastSeenMs }
        expired.forEach {
            table.remove(it.icao)
            it.icao.toIntOrNull(16)?.let { icao -> decoder?.purgeCpr(icao) }
        }
        return expired
    }

    /** Remove all aircraft from state table. */
    fun reset() { table.clear(); totalSeen = 0 }

    // ── Message → state merge ─────────────────────────────────────────────────

    private fun mergeMessage(
        existing: AircraftState,
        message: DecodedMessage,
        now: Long,
    ): AircraftState {
        val sig = message.frame.signalLevel
        val sigHistory = if (sig > 0.0) {
            val h = existing.signalHistory.toMutableList()
            h.add(sig)
            if (h.size > AircraftState.MAX_SIGNAL_HISTORY) h.removeAt(0)
            h
        } else existing.signalHistory

        val crcStr = message.crcResult
        val validDelta = if (crcStr == CrcChecker.CrcResult.VALID ||
            crcStr == CrcChecker.CrcResult.CORRECTED ||
            crcStr == CrcChecker.CrcResult.RECOVERED) 1 else 0
        val corrDelta = if (crcStr == CrcChecker.CrcResult.CORRECTED) 1 else 0
        val badDelta = if (crcStr == CrcChecker.CrcResult.INVALID) 1 else 0

        val tc = (message as? DecodedMessage.AdsbMessage)?.typecode
        val summary = MessageSummary(
            timestampMs = now,
            downlinkFormat = message.frame.downlinkFormat,
            typecode = tc,
            crcResult = message.crcResult,
            signalLevel = sig,
        )
        val history = existing.messageHistory.toMutableList().also {
            it.add(summary)
            while (it.size > AircraftState.MAX_HISTORY) it.removeAt(0)
        }

        val base = existing.copy(
            messageCount = existing.messageCount + 1,
            lastSeenMs   = now,
            lastCrcResult = message.crcResult,
            downlinkFormat = message.frame.downlinkFormat,
            signalDbfs = if (sig > 0.0) toDbfs(sig) else existing.signalDbfs,
            signalHistory = sigHistory,
            validCount = existing.validCount + validDelta,
            correctedCount = existing.correctedCount + corrDelta,
            badCrcCount = existing.badCrcCount + badDelta,
            messageHistory = history,
        )
        return when (message) {
            is DecodedMessage.AdsbMessage    -> mergeAdsb(base, message)
            is DecodedMessage.AltitudeReply  -> mergeAltitude(base, message)
            is DecodedMessage.IdentityReply  -> mergeIdentity(base, message)
            is DecodedMessage.AllCallReply   -> mergeAllCall(base, message)
            is DecodedMessage.LongAirAir     -> mergeLongAirAir(base, message)
            is DecodedMessage.Unknown        -> base
        }
    }

    /**
     * Appends a fix to the bounded trail, skipping duplicates of the last point —
     * a stationary aircraft on the ground re-reports the same coordinates for
     * minutes and would otherwise fill the whole buffer with one location.
     */
    private fun appendPosition(
        history: List<TrackPoint>,
        lat: Double,
        lon: Double,
        timestampMs: Long,
    ): List<TrackPoint> {
        val last = history.lastOrNull()
        if (last != null && last.latitude == lat && last.longitude == lon) return history
        val appended = history + TrackPoint(lat, lon, timestampMs)
        return if (appended.size > AircraftState.MAX_POSITION_HISTORY) {
            appended.subList(appended.size - AircraftState.MAX_POSITION_HISTORY, appended.size)
        } else {
            appended
        }
    }

    private fun mergeAdsb(state: AircraftState, msg: DecodedMessage.AdsbMessage): AircraftState {
        val f = msg.fields
        val rawSpeed = f.groundSpeedKt
        val rawAlt   = f.altitudeFt
        val lat = f.latitude?.let { Math.round(it * 1_000_000.0) / 1_000_000.0 } ?: state.latitude
        val lon = f.longitude?.let { Math.round(it * 1_000_000.0) / 1_000_000.0 } ?: state.longitude
        val newPos = f.latitude != null && f.longitude != null
        val hasObserver = observerLat != 0.0 || observerLon != 0.0
        val distNm = if (lat != null && lon != null && hasObserver) haversineNm(observerLat, observerLon, lat, lon) else state.distanceNm
        val bear   = if (lat != null && lon != null && hasObserver) bearingDeg(observerLat, observerLon, lat, lon)  else state.bearingDeg
        return state.copy(
            typecode          = msg.typecode,
            callsign          = f.callsign?.takeIf { it.isNotEmpty() } ?: state.callsign,
            category          = f.emitterCategory ?: state.category,
            onGround          = f.onGround ?: state.onGround,
            latitude          = lat,
            longitude         = lon,
            lastPositionMs    = if (newPos) state.lastSeenMs else state.lastPositionMs,
            positionHistory   = if (newPos && lat != null && lon != null)
                appendPosition(state.positionHistory, lat, lon, state.lastSeenMs)
            else state.positionHistory,
            altitudeFt        = rawAlt?.takeIf { it in -1500..72000 } ?: state.altitudeFt,
            altitudeGnssFt    = f.altitudeGnssFt ?: state.altitudeGnssFt,
            groundSpeedKt     = rawSpeed?.takeIf { it in 0..700 } ?: state.groundSpeedKt,
            trackDeg          = f.trackDeg ?: state.trackDeg,
            airspeedKt        = f.airspeedKt ?: state.airspeedKt,
            headingDeg        = f.headingDeg ?: state.headingDeg,
            speedType         = f.speedType ?: state.speedType,
            verticalRateFpm   = f.verticalRateFpm ?: state.verticalRateFpm,
            squawk            = f.squawk ?: state.squawk,
            selectedAltitudeFt  = f.selectedAltitudeFt ?: state.selectedAltitudeFt,
            selectedHeadingDeg  = f.selectedHeadingDeg ?: state.selectedHeadingDeg,
            autoPilotEngaged    = if (f.autoPilotEngaged) true else state.autoPilotEngaged,
            baroSettingMbar     = f.baroSettingMbar ?: state.baroSettingMbar,
            nacP                = f.nacP ?: state.nacP,
            nacV                = f.nacV ?: state.nacV,
            sil                 = f.sil ?: state.sil,
            gva                 = f.gva ?: state.gva,
            tcasOperational     = if (f.tcasOperational) true else state.tcasOperational,
            versionNumber       = f.versionNumber ?: state.versionNumber,
            distanceNm          = distNm,
            bearingDeg          = bear,
        )
    }

    private fun haversineNm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 3440.065
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2).let { it * it } +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2).let { it * it }
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    private fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLon = Math.toRadians(lon2 - lon1)
        val y = Math.sin(dLon) * Math.cos(Math.toRadians(lat2))
        val x = Math.cos(Math.toRadians(lat1)) * Math.sin(Math.toRadians(lat2)) -
                Math.sin(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.cos(dLon)
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360
    }

    private fun mergeAltitude(state: AircraftState, msg: DecodedMessage.AltitudeReply) =
        applyCommB(
            state.copy(
                altitudeFt = msg.altitudeFt?.takeIf { it in -1500..72000 } ?: state.altitudeFt,
                callsign   = msg.callsign?.takeIf { it.isNotEmpty() } ?: state.callsign,
                // Only DF0 reports this (its vertical-status bit); DF4/DF20
                // leave msg.onGround null and must not touch the existing value.
                onGround   = msg.onGround ?: state.onGround,
            ),
            msg.commB,
        )

    private fun mergeIdentity(state: AircraftState, msg: DecodedMessage.IdentityReply) =
        applyCommB(
            state.copy(
                squawk   = msg.squawk,
                callsign = msg.callsign?.takeIf { it.isNotEmpty() } ?: state.callsign,
            ),
            msg.commB,
        )

    private fun mergeAllCall(state: AircraftState, msg: DecodedMessage.AllCallReply): AircraftState {
        val ids = if (msg.iiCode != null) state.interrogatorIds + msg.iiCode else state.interrogatorIds
        return state.copy(
            typecode = null,
            iiCode   = msg.iiCode ?: state.iiCode,
            interrogatorIds = ids,
        )
    }

    /**
     * DF16 — TCAS air-air surveillance. Port of the reference's TCAS handling in
     * `AircraftManager._merge`: `tcas_event_count` increments only on a *rising*
     * edge into an active RA, not on every DF16 frame received while one is in
     * progress, and a terminated advisory keeps its last-known text rather than
     * being cleared to null.
     */
    private fun mergeLongAirAir(state: AircraftState, msg: DecodedMessage.LongAirAir): AircraftState {
        var next = state.copy(
            altitudeFt = msg.altitudeFt?.takeIf { it in -1500..72000 } ?: state.altitudeFt,
            onGround   = msg.onGround ?: state.onGround,
            tcasSl     = msg.tcasSl ?: state.tcasSl,
            tcasTargetIcao = msg.tcasTargetIcao ?: state.tcasTargetIcao,
        )
        next = if (msg.tcasRaActive) {
            next.copy(
                tcasEventCount   = if (!next.tcasRaActive) next.tcasEventCount + 1 else next.tcasEventCount,
                tcasRaActive     = true,
                tcasRaText       = msg.tcasRaText,
                tcasRaComplement = msg.tcasRaComplement,
                tcasRaTerminated = false,
            )
        } else if (msg.tcasRaTerminated) {
            next.copy(
                tcasRaActive     = false,
                tcasRaTerminated = true,
                // Direct assignment, matching the reference exactly — not a
                // null-coalesce. A terminated frame's text is a live decode of
                // that same frame's ARA bits (see decodeRaMv), so this is
                // "keep last known" in practice, but a termination frame with
                // no ARA bits at all genuinely nulls it out in Python too.
                tcasRaText       = msg.tcasRaText,
            )
        } else {
            next
        }
        return next
    }

    // Comm-B fields are supplementary GICB data — never overwrite a value already
    // known from ADS-B extended squitter, only fill gaps.
    private fun applyCommB(state: AircraftState, commB: CommBFields?): AircraftState {
        if (commB == null) return state
        return state.copy(
            selectedAltitudeFt        = state.selectedAltitudeFt ?: commB.selectedAltitudeFt,
            baroSettingMbar           = state.baroSettingMbar ?: commB.baroSettingMbar,
            trackDeg                  = state.trackDeg ?: commB.trueTrackDeg?.toInt(),
            groundSpeedKt             = state.groundSpeedKt ?: commB.groundSpeedKt,
            verticalRateFpm           = state.verticalRateFpm ?: commB.verticalRateFpm,
            rollAngleDeg              = commB.rollAngleDeg ?: state.rollAngleDeg,
            trackAngleRateDegPerSec   = commB.trackAngleRateDegPerSec ?: state.trackAngleRateDegPerSec,
            trueAirspeedKt            = commB.trueAirspeedKt ?: state.trueAirspeedKt,
            magneticHeadingDeg        = commB.magneticHeadingDeg ?: state.magneticHeadingDeg,
            indicatedAirspeedKt       = commB.indicatedAirspeedKt ?: state.indicatedAirspeedKt,
            machNumber                = commB.machNumber ?: state.machNumber,
            lastBdsCode                = commB.bdsCode,
        )
    }

    /**
     * Merge the offline enrichment sources into a state.
     *
     * Runs on every update rather than once at creation because the callsign
     * usually arrives several messages after the ICAO does, and the callsign is
     * what yields the operator. Applied weakest-first so a bundled database entry
     * can displace an algorithmic guess but never the other way round.
     */
    private fun applyEnrichment(state: AircraftState): AircraftState {
        val offline = OfflineEnrichment.enrich(state.icao, state.callsign)
        val db = lookup[state.icao]

        var registration = state.registration
        var registrationSource = state.registrationSource
        if (offline.registrationSource.betterThanOrNew(registrationSource) && offline.registration != null) {
            registration = offline.registration
            registrationSource = offline.registrationSource
        }
        if (db?.registration != null && DataSource.DATABASE.betterThan(registrationSource)) {
            registration = db.registration
            registrationSource = DataSource.DATABASE
        }

        var operator = state.operator
        var operatorSource = state.operatorSource
        var operatorKind = state.operatorKind
        if (offline.operator != null && offline.operatorSource.betterThanOrNew(operatorSource)) {
            operator = offline.operator
            operatorSource = offline.operatorSource
            operatorKind = offline.operatorKind
        }
        // db (the legacy bundled-lookup table) has no way to say whether its operator
        // name is an airline or an owner, so it's treated as OWNER — the same
        // conservative default setAircraftMeta uses for the same reason.
        if (db?.operator != null && DataSource.DATABASE.betterThan(operatorSource)) {
            operator = db.operator
            operatorSource = DataSource.DATABASE
            operatorKind = OperatorKind.OWNER
        }

        return state.copy(
            registration = registration,
            registrationSource = registrationSource,
            operator = operator,
            operatorSource = operatorSource,
            operatorKind = operatorKind,
            aircraftType = state.aircraftType ?: db?.aircraftType,
        )
    }

    private fun DataSource?.betterThanOrNew(current: DataSource?): Boolean =
        this != null && (current == null || this.ordinal >= current.ordinal)

    /** Patch in a route resolved asynchronously from the network. */
    fun setRoute(icaoHex: String, route: String) {
        table[icaoHex]?.let {
            table[icaoHex] = it.copy(route = route, routeSource = DataSource.NETWORK)
        }
    }

    /**
     * Patch in metadata (registration, owner, display type) from a network enrichment
     * source — typically the global aircraft mirror, i.e. FAA-style registry data.
     *
     * [owner] is the *registered owner*, not necessarily who operates the aircraft — a
     * leased/financed airliner is routinely registered to a trust bank. It only ever
     * fills [AircraftState.operator] when no airline name is already known there
     * (`operatorKind != AIRLINE`); it must never overwrite one, regardless of which
     * arrived first — see [setFaResult] and `OfflineEnrichment.enrich`, the two
     * sources that actually represent an airline.
     */
    fun setAircraftMeta(icaoHex: String, registration: String?, owner: String?, typeDisplay: String?) {
        table[icaoHex]?.let { existing ->
            val useOwner = owner != null && existing.operatorKind != OperatorKind.AIRLINE
            table[icaoHex] = existing.copy(
                registration = registration ?: existing.registration,
                registrationSource = if (registration != null) DataSource.NETWORK else existing.registrationSource,
                operator = if (useOwner) owner else existing.operator,
                operatorSource = if (useOwner) DataSource.NETWORK else existing.operatorSource,
                operatorKind = if (useOwner) OperatorKind.OWNER else existing.operatorKind,
                aircraftType = typeDisplay ?: existing.aircraftType,
            )
        }
    }

    /**
     * Patch in FA scrape result (route, airline, type) resolved asynchronously.
     * [airlineName] is FlightAware's own resolved operating carrier — an actual
     * airline, not a registry lookup — so per the original design ("FlightAware
     * scrape overrides all other sources for airline"), it always wins outright.
     */
    fun setFaResult(icaoHex: String, route: String?, airlineName: String?, typeDisplay: String?) {
        table[icaoHex]?.let { existing ->
            table[icaoHex] = existing.copy(
                route = route ?: existing.route,
                routeSource = if (route != null) DataSource.NETWORK else existing.routeSource,
                operator = airlineName ?: existing.operator,
                operatorSource = if (airlineName != null) DataSource.NETWORK else existing.operatorSource,
                operatorKind = if (airlineName != null) OperatorKind.AIRLINE else existing.operatorKind,
                aircraftType = typeDisplay ?: existing.aircraftType,
            )
        }
    }

    companion object {
        /** Matches Python's `20 * math.log10(signal_level)`, clamped to avoid -Infinity. */
        fun toDbfs(linearRatio: Double): Double =
            (20.0 * log10(linearRatio)).coerceAtLeast(DBFS_FLOOR)

        const val DBFS_FLOOR = -40.0

        // wiedehopf auto-gain heuristic: frames at or above -3 dBFS are "strong"
        // (near ADC full-scale). Too many → gain too high; too few → gain too low.
        const val STRONG_SIGNAL_THRESHOLD_DBFS = -3.0
        const val STRONG_SIGNAL_WARN_PCT = 5.0
        const val STRONG_SIGNAL_ALERT_PCT = 7.0
    }
}
