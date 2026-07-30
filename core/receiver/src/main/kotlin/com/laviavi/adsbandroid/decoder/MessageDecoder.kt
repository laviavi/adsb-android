package com.laviavi.adsbandroid.decoder

import com.laviavi.adsbandroid.crc.CrcChecker

/**
 * Full Mode S / ADS-B message decoder.
 * Ports Python adsb_decoder.py. Covers all DF types and TC subtypes.
 * No Android dependencies — pure Kotlin, testable in plain JUnit.
 */
class MessageDecoder {

    var observerLat: Double = 0.0
    var observerLon: Double = 0.0

    /**
     * Currently-tracked ICAOs, used only by [resolveApIcao] to identify a DF16
     * intruder. Mirrors the Python reference's `MessageDecoder._known_icaos`,
     * kept current by whoever owns the aircraft table (see that class's own doc
     * comment — `AircraftManager._merge` calls `_decoder.update_known_icaos`
     * after every merge). Best-effort and expected to lag the true table by up
     * to one publish tick; the heuristic itself is approximate already.
     */
    var knownIcaos: Set<Int> = emptySet()

    /** Extended-squitter formats that are only ever 112 bits. */
    private val LONG_ONLY_DF = setOf(17, 18)

    private val confirmedIcaoCache = LinkedHashMap<Int, Unit>(512, 0.75f, true)
    private val AIS_CHARSET = "@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_ !\"#\$%&'()*+,-./0123456789:;<=>?"
    private val cprEven = HashMap<Int, CprFrame>()
    private val cprOdd  = HashMap<Int, CprFrame>()
    private data class CprFrame(val lat: Int, val lon: Int, val timestampMs: Long)

    companion object {
        /** `DecoderConfig.cpr_max_age_seconds` in the reference. */
        const val CPR_MAX_PAIR_AGE_MS = 10_000L
    }

    fun purgeCpr(icao: Int) {
        cprEven.remove(icao)
        cprOdd.remove(icao)
    }

    /**
     * [nowMs] is the frame's reception time, used for CPR pair ageing. It is a
     * parameter so recorded captures replay on a virtual clock; previously CPR
     * read the wall clock directly, which made every replayed pair look
     * simultaneous and the age check meaningless.
     */
    fun decode(checked: CrcChecker.CheckedFrame, nowMs: Long = System.currentTimeMillis()): DecodedMessage? {
        if (checked.crcResult == CrcChecker.CrcResult.INVALID ||
            checked.crcResult == CrcChecker.CrcResult.PARITY_ADDRESS) return null
        val frame = checked.frame
        // An extended squitter is always 112 bits. A short frame whose first 5
        // bits read as DF17/18 is malformed even if its CRC happens to pass, and
        // must not be credited with an ICAO — otherwise it invents an aircraft.
        // Matches the Python decoder's `if len(data) < 11: return` guard.
        if (frame.downlinkFormat in LONG_ONLY_DF && frame.bytes.size != 14) return null
        // A RECOVERED frame's address came from the AP/ICAO-cache lookup in
        // CrcChecker; trust it over re-deriving from the PA field (matches the
        // Python decoder's `raw.recovered_icao` branch).
        val icao = checked.recoveredIcao ?: extractIcao(frame) ?: return null
        return when (frame.downlinkFormat) {
            0  -> decodeDF0(frame, icao, checked.crcResult)
            4  -> decodeDF4(frame, icao, checked.crcResult)
            5  -> decodeDF5(frame, icao, checked.crcResult)
            11 -> decodeDF11(frame, icao, checked.crcResult, checked.crc)
            16 -> decodeDF16(frame, icao, checked.crcResult)
            17, 18 -> decodeDF17_18(frame, icao, checked.crcResult, nowMs)
            20 -> decodeDF20(frame, icao, checked.crcResult)
            21 -> decodeDF21(frame, icao, checked.crcResult)
            // DF22: military use — no standard civil decode
            // DF23: undefined for civil use
            // DF24: Comm-D ELM data-link — no position/identity data
            22, 23, 24 -> DecodedMessage.Unknown(frame, icao, checked.crcResult, frame.downlinkFormat)
            else -> DecodedMessage.Unknown(frame, icao, checked.crcResult, frame.downlinkFormat)
        }
    }

    // ── ICAO extraction ───────────────────────────────────────────────────────
    private fun extractIcao(frame: RawFrame): Int? {
        return when (frame.downlinkFormat) {
            11, 17, 18 -> {
                val icao = (frame.bytes[1] shl 16) or (frame.bytes[2] shl 8) or frame.bytes[3]
                if (icao > 0) confirmedIcaoCache[icao] = Unit
                icao.takeIf { it > 0 }
            }
            else -> {
                val pa = (frame.bytes[frame.bytes.size - 3] shl 16) or
                         (frame.bytes[frame.bytes.size - 2] shl 8) or
                         frame.bytes[frame.bytes.size - 1]
                if (confirmedIcaoCache.containsKey(pa)) pa else null
            }
        }
    }

    // ── DF decoders ───────────────────────────────────────────────────────────
    private fun decodeDF0(f: RawFrame, icao: Int, crc: CrcChecker.CrcResult): DecodedMessage.AltitudeReply {
        // Vertical status bit — DF0 has no SL field (SL is only meaningful in DF16).
        val onGround = ((f.bytes[0] ushr 2) and 0x01) != 0
        return DecodedMessage.AltitudeReply(f, icao, crc,
            decodeAc13Field(((f.bytes[2] shl 8) or f.bytes[3]) and 0x1FFF), 0, onGround = onGround)
    }

    private fun decodeDF4(f: RawFrame, icao: Int, crc: CrcChecker.CrcResult): DecodedMessage.AltitudeReply {
        val fs = (f.bytes[0] ushr 2) and 0x07
        val onGround = fs == 1 || fs == 3 || fs == 5
        return DecodedMessage.AltitudeReply(f, icao, crc,
            decodeAc13Field(((f.bytes[2] shl 8) or f.bytes[3]) and 0x1FFF), 4, onGround = onGround)
    }

    private fun decodeDF5(f: RawFrame, icao: Int, crc: CrcChecker.CrcResult) =
        DecodedMessage.IdentityReply(f, icao, crc,
            decodeIdentity(((f.bytes[2] shl 8) or f.bytes[3]) and 0x1FFF), 5)

    /**
     * @param addr The frame's recovered "address" — `CrcChecker.computeCrc(frame.bytes)`,
     *   which is algebraically `PI XOR CRC24(DF+CA+ICAO)` (see that function's doc
     *   comment). Ported from `adsb_decoder.py`: `addr = pi ^ crc24(data[:-3]);
     *   ii_code = (addr >> 20) & 0x0F` — the *previous* Kotlin formula read the raw
     *   low nibble of the last PI byte directly, with no XOR/CRC step at all, and
     *   disagreed with the reference on 18,764/20,000 sampled values.
     *
     *   Verified against the live Python reference that this is structurally
     *   always 0 for any DF11 `CrcChecker` itself classifies VALID or RECOVERED:
     *   both acceptance paths require `addr == 0` or `addr < 80`, and 80 is far
     *   below `2^20`, so the top 4 bits — the only bits `ii_code` reads — can
     *   never be nonzero on a frame that reaches this function. A DF11 with a
     *   genuine nonzero interrogator ID produces a large `addr` and is rejected
     *   as `BAD` before decoding ever runs (confirmed empirically: ii=1/3/7/15
     *   constructed frames all classify `BAD` in the live reference). Ported
     *   faithfully — matching the formula, not "fixing" it to produce a more
     *   interesting number — per this project's parity-over-improvement policy.
     */
    private fun decodeDF11(f: RawFrame, icao: Int, crc: CrcChecker.CrcResult, addr: Int): DecodedMessage.AllCallReply {
        val capability = f.bytes[0] and 0x07
        val ii = (addr ushr 20) and 0x0F
        return DecodedMessage.AllCallReply(f, icao, crc, capability, ii)
    }

    /**
     * DF16 — Long Air-Air Surveillance (TCAS). 14 bytes:
     * [DF/VS/SL(3b) | X | RC/AC(13b) | MV(56b) | AP(24b)].
     * Port of `decode_tcas_df16` / `_decode_ra_mv` / `_resolve_ap_icao`.
     */
    private fun decodeDF16(f: RawFrame, icao: Int, crc: CrcChecker.CrcResult): DecodedMessage.LongAirAir {
        if (f.bytes.size < 14) return DecodedMessage.LongAirAir(f, icao, crc)

        val sl = f.bytes[0] and 0x07
        val altitude = decodeAc13Field(((f.bytes[2] and 0x1F) shl 8) or f.bytes[3])

        // MV field (bytes 4-10, 7 bytes). BDS 3,0 = TCAS Resolution Advisory Report.
        val mv = IntArray(7) { f.bytes[4 + it] }
        val ra = if (mv[0] == 0x30) decodeRaMv(mv) else null

        // Intruder ICAO from the AP field, only attempted once some aircraft are
        // actually tracked — matches the reference's `if known_icaos:` guard.
        val target = if (knownIcaos.isNotEmpty()) resolveApIcao(f.bytes, knownIcaos) else null

        return DecodedMessage.LongAirAir(
            frame = f, icao = icao, crcResult = crc,
            altitudeFt = altitude,
            onGround = false,
            tcasSl = sl,
            tcasRaActive = ra?.active ?: false,
            tcasRaText = ra?.text,
            tcasRaComplement = ra?.complement,
            tcasRaTerminated = ra?.terminated ?: false,
            tcasTargetIcao = target?.let { "%06X".format(it) },
        )
    }

    private class RaFields(val active: Boolean, val text: String?, val complement: String?, val terminated: Boolean)

    /**
     * BDS 3,0 TCAS RA from the 7-byte MV field (DO-185B). Port of `_decode_ra_mv` —
     * only its final, actually-used bit layout; the reference computes `ara_raw`
     * once and then recomputes it with a "wait, recompute properly" comment, and
     * only the second computation is live.
     */
    private fun decodeRaMv(mv: IntArray): RaFields {
        val araRaw = ((mv[2] shl 8) or mv[3]) ushr 2   // 14 bits
        val racByte = mv[3]
        val racRaw = (racByte ushr 1) and 0x0F
        val rat = racByte and 0x01

        val raParts = ARA_BITS.filterKeys { bit -> (araRaw and (1 shl (13 - bit))) != 0 }.values
        val racParts = RAC_BITS.filterKeys { bit -> (racRaw and (1 shl (3 - bit))) != 0 }.values

        return RaFields(
            active = raParts.isNotEmpty() && rat == 0,
            text = raParts.takeIf { it.isNotEmpty() }?.joinToString(", "),
            complement = racParts.takeIf { it.isNotEmpty() }?.joinToString(", "),
            terminated = rat != 0,
        )
    }

    /**
     * Identify a DF16 intruder from the AP (Address/Parity) field. Port of
     * `_resolve_ap_icao` — faithfully, including a real limitation confirmed in
     * the reference itself: **this can never actually resolve a target.**
     *
     * The intended heuristic: AP = ICAO_interrogator XOR CRC24(message); since
     * the full CRC can't be recomputed from AP alone, XOR AP against every
     * currently-tracked ICAO and see if the result is *also* tracked. But XOR is
     * its own inverse, so if `ap XOR a == b` for two tracked ICAOs, then
     * `ap XOR b == a` holds simultaneously — whenever a genuine pair exists in
     * the known set, **both** directions match, always producing an even count
     * (2, 4, ...), never the single match `matches.singleOrNull()` requires.
     * Confirmed empirically against the Python reference: 0 non-null results in
     * 200,000 randomised trials. The reference's own `if len(matches) == 1`
     * branch is dead code, not a heuristic that sometimes works.
     *
     * Ported as-is rather than "fixed," per this project's standard: parity
     * with the reference, not a silent improvement on it. The field this feeds
     * ([DecodedMessage.LongAirAir.tcasTargetIcao]) will always be null; nothing
     * downstream should assume it is ever populated.
     */
    private fun resolveApIcao(bytes: IntArray, knownIcaos: Set<Int>): Int? {
        if (bytes.size < 3) return null
        val ap = (bytes[bytes.size - 3] shl 16) or (bytes[bytes.size - 2] shl 8) or bytes[bytes.size - 1]
        if (ap == 0) return null
        val matches = knownIcaos.mapNotNull { known ->
            val candidate = ap xor known
            candidate.takeIf { it in knownIcaos && it != known }
        }
        return matches.singleOrNull()
    }

    private fun decodeDF20(f: RawFrame, icao: Int, crc: CrcChecker.CrcResult): DecodedMessage {
        val alt = decodeAc13Field(((f.bytes[2] shl 8) or f.bytes[3]) and 0x1FFF)
        val cs  = if (f.bytes[4] == 0x20) decodeCallsign(f, 5) else null
        val commB = if (cs == null) decodeCommB(f) else null
        return DecodedMessage.AltitudeReply(f, icao, crc, alt, 20, callsign = cs, commB = commB)
    }

    private fun decodeDF21(f: RawFrame, icao: Int, crc: CrcChecker.CrcResult): DecodedMessage {
        val sq = decodeIdentity(((f.bytes[2] shl 8) or f.bytes[3]) and 0x1FFF)
        val cs = if (f.bytes[4] == 0x20) decodeCallsign(f, 5) else null
        val commB = if (cs == null) decodeCommB(f) else null
        return DecodedMessage.IdentityReply(f, icao, crc, sq, 21, callsign = cs, commB = commB)
    }

    // MB field is bytes[4..10] of the 7-byte (56-bit) DF20/21 Comm-B reply.
    private fun decodeCommB(f: RawFrame): CommBFields? {
        if (f.bytes.size < 11) return null
        return CommBDecoder.decode(IntArray(7) { f.bytes[4 + it] })
    }

    private fun decodeDF17_18(f: RawFrame, icao: Int, crc: CrcChecker.CrcResult, nowMs: Long): DecodedMessage {
        if (f.bytes.size != 14) return DecodedMessage.Unknown(f, icao, crc, f.downlinkFormat)
        val tc      = f.bytes[4] ushr 3
        val subtype = if (tc == 29) (f.bytes[4] and 6) ushr 1 else f.bytes[4] and 7
        val fields  = decodeAdsbFields(f, icao, tc, subtype, nowMs)
        return DecodedMessage.AdsbMessage(f, icao, crc, tc, subtype, fields)
    }

    // ── ADS-B field decoder (TC dispatch) ────────────────────────────────────
    private fun decodeAdsbFields(f: RawFrame, icao: Int, tc: Int, subtype: Int, nowMs: Long): AdsbFields {
        val fields = AdsbFields()
        when {
            tc in 1..4   -> {
                fields.callsign = decodeCallsign(f, 5)
                fields.emitterCategory = (tc - 1) * 8 + (f.bytes[4] and 0x07)
            }
            tc in 5..8   -> decodeGroundPosition(f, icao, fields, nowMs)
            tc in 9..18  -> decodeAirbornePositionBaro(f, icao, fields, nowMs)
            tc == 19     -> decodeVelocity(f, subtype, fields)
            tc in 20..22 -> decodeAirbornePositionGnss(f, icao, fields, nowMs)
            // TC 23 and TC 28 deliberately decode nothing, matching
            // `_decode_me` in the reference (`elif tc == 28: pass`). Both
            // previously pulled a squawk out of these messages — a dump1090
            // behaviour, not the reference's — which put a squawk on 731 frames
            // in the golden capture that the reference reports no squawk for.
            tc == 29     -> decodeTC29(f, subtype, fields)
            tc == 31     -> decodeTC31(f, subtype, fields)
        }
        return fields
    }

    // TC 1-4: Identification
    private fun decodeCallsign(f: RawFrame, offset: Int): String? {
        val b = f.bytes
        if (offset + 5 >= b.size) return null
        return buildString {
            append(AIS_CHARSET[(b[offset]   and 0xFC) ushr 2])
            append(AIS_CHARSET[((b[offset]   and 0x03) shl 4) or ((b[offset+1] and 0xF0) ushr 4)])
            append(AIS_CHARSET[((b[offset+1] and 0x0F) shl 2) or ((b[offset+2] and 0xC0) ushr 6)])
            append(AIS_CHARSET[b[offset+2]  and 0x3F])
            append(AIS_CHARSET[(b[offset+3] and 0xFC) ushr 2])
            append(AIS_CHARSET[((b[offset+3] and 0x03) shl 4) or ((b[offset+4] and 0xF0) ushr 4)])
            append(AIS_CHARSET[((b[offset+4] and 0x0F) shl 2) or ((b[offset+5] and 0xC0) ushr 6)])
            append(AIS_CHARSET[b[offset+5]  and 0x3F])
        }.trimEnd('@', ' ').ifEmpty { null }
    }

    // TC 5-8: Ground position
    private fun decodeGroundPosition(f: RawFrame, icao: Int, fields: AdsbFields, nowMs: Long) {
        fields.onGround = true
        val movement = ((f.bytes[4] shl 4) or (f.bytes[5] ushr 4)) and 0x007F
        if (movement in 1..124) fields.groundSpeedKt = decodeMovementField(movement)
        if ((f.bytes[5] and 0x08) != 0) {
            fields.trackDeg = (((f.bytes[5] shl 4) or (f.bytes[6] ushr 4)) and 0x007F * 45) ushr 4
        }
        decodeCprPosition(f, icao, fields, nowMs)
    }

    // TC 9-18: Airborne position, barometric altitude (Gillham/Q-bit 12-bit AC field).
    private fun decodeAirbornePositionBaro(f: RawFrame, icao: Int, fields: AdsbFields, nowMs: Long) {
        fields.onGround = false
        val ac12 = ((f.bytes[5] shl 4) or (f.bytes[6] ushr 4)) and 0x0FFF
        if (ac12 > 0) fields.altitudeFt = decodeAc12Field(ac12)
        decodeCprPosition(f, icao, fields, nowMs)
    }

    /**
     * TC 20-22: Airborne position, GNSS (geometric) altitude.
     *
     * Previously routed through the same Gillham/Q-bit decode as barometric
     * altitude and written into [AdsbFields.altitudeFt] — wrong formula (GNSS
     * altitude has no Q-bit/Gray-code structure at all, it's a plain 25-ft-step
     * count) and wrong field (it silently overwrote barometric altitude via the
     * merge, when the reference keeps `altitude_baro_ft`/`altitude_gnss_ft`
     * separate). Port of `_decode_airborne_position_gnss`:
     * `alt_raw * 25 - 1000 if alt_raw else None`. Note the mask is the full
     * 13 bits (`0x1FFF`), not the 12-bit `0x0FFF` the barometric path uses —
     * there is no Gillham decode here to absorb a stray 13th bit.
     */
    private fun decodeAirbornePositionGnss(f: RawFrame, icao: Int, fields: AdsbFields, nowMs: Long) {
        fields.onGround = false
        val altRaw = ((f.bytes[5] shl 4) or (f.bytes[6] ushr 4)) and 0x1FFF
        if (altRaw > 0) fields.altitudeGnssFt = altRaw * 25 - 1000
        decodeCprPosition(f, icao, fields, nowMs)
    }

    // CPR position decode — global + relative fallback (FA pattern)
    private fun decodeCprPosition(f: RawFrame, icao: Int, fields: AdsbFields, nowMs: Long) {
        val odd = (f.bytes[6] and 0x04) != 0
        val rawLat = ((f.bytes[6] and 3) shl 15) or (f.bytes[7] shl 7) or (f.bytes[8] ushr 1)
        val rawLon = ((f.bytes[8] and 1) shl 16) or (f.bytes[9] shl 8) or f.bytes[10]
        val frame = CprFrame(rawLat, rawLon, nowMs)
        if (odd) cprOdd[icao] = frame else cprEven[icao] = frame

        // fields.onGround is always set by the caller (decodeGroundPosition /
        // decodeAirbornePositionBaro/Gnss) before this runs; the fallback only
        // guards the type, which is nullable so TC1-4/19/29/31 can leave it
        // unset for the merge (see #10 in docs/correction_plan.md).
        val onGround = fields.onGround ?: false
        val pos = decodeCprGlobal(icao, onGround, nowMs)
            ?: decodeCprRelative(icao, odd, onGround)
        if (pos != null) {
            fields.latitude  = pos.first
            fields.longitude = pos.second
        }
    }

    // ── CPR Global decode (DO-260B §A.1.7.3) ─────────────────────────────────
    private fun decodeCprGlobal(icao: Int, onGround: Boolean, nowMs: Long): Pair<Double, Double>? {
        val even = cprEven[icao] ?: return null
        val odd  = cprOdd[icao]  ?: return null
        // Both frames must be recent relative to *now*, which is what
        // `AircraftManager._handle_cpr` in the reference requires. Comparing the
        // two frames to each other instead let a pair that was minutes stale
        // decode as a current position, as long as the two arrived close
        // together — that produced positions hundreds of miles off.
        if (nowMs - even.timestampMs > CPR_MAX_PAIR_AGE_MS) return null
        if (nowMs - odd.timestampMs > CPR_MAX_PAIR_AGE_MS) return null

        val dLat0 = if (onGround) 90.0 / 60.0 else 360.0 / 60.0
        val dLat1 = if (onGround) 90.0 / 59.0 else 360.0 / 59.0
        val lat0 = even.lat.toDouble(); val lat1 = odd.lat.toDouble()
        val lon0 = even.lon.toDouble(); val lon1 = odd.lon.toDouble()

        val j = Math.floor(((59 * lat0 - 60 * lat1) / 131072) + 0.5).toInt()
        var rlat0 = dLat0 * (cprMod(j, 60) + lat0 / 131072)
        var rlat1 = dLat1 * (cprMod(j, 59) + lat1 / 131072)

        if (!onGround) {
            if (rlat0 >= 270) rlat0 -= 360
            if (rlat1 >= 270) rlat1 -= 360
        }
        if (rlat0 < -90 || rlat0 > 90 || rlat1 < -90 || rlat1 > 90) return null
        if (cprNL(rlat0) != cprNL(rlat1)) return null

        // Use most recent frame to pick lat/lon
        val useOdd = odd.timestampMs > even.timestampMs
        val lat: Double; val lon: Double
        if (useOdd) {
            lat = rlat1
            val ni = cprN(rlat1, true)
            val m = Math.floor(((lon0 * (cprNL(rlat1) - 1) - lon1 * cprNL(rlat1)) / 131072.0) + 0.5).toInt()
            lon = cprDlon(rlat1, true, onGround) * (cprMod(m, ni) + lon1 / 131072)
        } else {
            lat = rlat0
            val ni = cprN(rlat0, false)
            val m = Math.floor(((lon0 * (cprNL(rlat0) - 1) - lon1 * cprNL(rlat0)) / 131072.0) + 0.5).toInt()
            lon = cprDlon(rlat0, false, onGround) * (cprMod(m, ni) + lon0 / 131072)
        }
        val finalLon = if (lon > 180) lon - 360 else lon
        return Pair(lat, finalLon)
    }

    // ── CPR Relative decode (DO-260B §A.1.7.3.3) ─────────────────────────────
    private fun decodeCprRelative(icao: Int, odd: Boolean, onGround: Boolean): Pair<Double, Double>? {
        val frame = (if (odd) cprOdd[icao] else cprEven[icao]) ?: return null
        if (observerLat == 0.0 && observerLon == 0.0) return null

        val dLat = (if (onGround) 90.0 else 360.0) / (if (odd) 59.0 else 60.0)
        val rawLat = frame.lat.toDouble()
        val rawLon = frame.lon.toDouble()

        val j = Math.floor(observerLat / dLat).toInt() +
                Math.floor(0.5 + cprModD(observerLat, dLat) / dLat - rawLat / 131072.0).toInt()
        var rlat = dLat * (j + rawLat / 131072.0)
        if (rlat >= 270.0) rlat -= 360.0
        if (rlat < -90.0 || rlat > 90.0) return null

        val ni = maxOf(1, cprNL(rlat) - if (odd) 1 else 0)
        val dLon = (if (onGround) 90.0 else 360.0) / ni

        val m = Math.floor(observerLon / dLon).toInt() +
                Math.floor(0.5 + cprModD(observerLon, dLon) / dLon - rawLon / 131072.0).toInt()
        var rlon = dLon * (m + rawLon / 131072.0)
        if (rlon >= 180.0) rlon -= 360.0
        if (rlon < -180.0) rlon += 360.0

        return Pair(rlat, rlon)
    }

    private fun cprModD(a: Double, b: Double): Double { val r = a % b; return if (r < 0) r + b else r }

    // ── CPR helper functions (NL table from DO-260B, verbatim) ───────────────
    private fun cprNL(lat: Double): Int {
        val a = Math.abs(lat)
        return when {
            a < 10.47047130 -> 59; a < 14.82817437 -> 58; a < 18.18626357 -> 57
            a < 21.02939493 -> 56; a < 23.54504487 -> 55; a < 25.82924707 -> 54
            a < 27.93898710 -> 53; a < 29.91135686 -> 52; a < 31.77209708 -> 51
            a < 33.53993436 -> 50; a < 35.22899598 -> 49; a < 36.85025108 -> 48
            a < 38.41241892 -> 47; a < 39.92256684 -> 46; a < 41.38651832 -> 45
            a < 42.80914012 -> 44; a < 44.19454951 -> 43; a < 45.54626723 -> 42
            a < 46.86733252 -> 41; a < 48.16039128 -> 40; a < 49.42776439 -> 39
            a < 50.67150166 -> 38; a < 51.89342469 -> 37; a < 53.09516153 -> 36
            a < 54.27817472 -> 35; a < 55.44378444 -> 34; a < 56.59318756 -> 33
            a < 57.72747354 -> 32; a < 58.84763776 -> 31; a < 59.95459277 -> 30
            a < 61.04917774 -> 29; a < 62.13216659 -> 28; a < 63.20427479 -> 27
            a < 64.26616523 -> 26; a < 65.31845310 -> 25; a < 66.36171008 -> 24
            a < 67.39646774 -> 23; a < 68.42322022 -> 22; a < 69.44242631 -> 21
            a < 70.45451075 -> 20; a < 71.45986473 -> 19; a < 72.45884545 -> 18
            a < 73.45177442 -> 17; a < 74.43893416 -> 16; a < 75.42056257 -> 15
            a < 76.39684391 -> 14; a < 77.36789461 -> 13; a < 78.33374083 -> 12
            a < 79.29428225 -> 11; a < 80.24923213 -> 10; a < 81.19801349 ->  9
            a < 82.13956981 ->  8; a < 83.07199445 ->  7; a < 83.99173563 ->  6
            a < 84.89166191 ->  5; a < 85.75541621 ->  4; a < 86.53536998 ->  3
            a < 87.00000000 ->  2; else -> 1
        }
    }

    private fun cprN(lat: Double, odd: Boolean): Int = maxOf(1, cprNL(lat) - if (odd) 1 else 0)
    private fun cprDlon(lat: Double, odd: Boolean, ground: Boolean) =
        (if (ground) 90.0 else 360.0) / cprN(lat, odd)
    private fun cprMod(a: Int, b: Int): Int { val r = a % b; return if (r < 0) r + b else r }

    // ── TC19: Velocity ────────────────────────────────────────────────────────
    private fun decodeVelocity(f: RawFrame, subtype: Int, fields: AdsbFields) {
        // Vertical rate (common to all subtypes)
        val vrRaw = ((f.bytes[8] and 0x07) shl 6) or (f.bytes[9] ushr 2)
        if (vrRaw > 0) {
            var vr = (vrRaw - 1) * 64
            if ((f.bytes[8] and 0x08) != 0) vr = -vr
            fields.verticalRateFpm = vr
        }
        when (subtype) {
            1, 2 -> { // Ground speed
                val ewRaw = ((f.bytes[5] and 0x03) shl 8) or f.bytes[6]
                val nsRaw = ((f.bytes[7] and 0x7F) shl 3) or (f.bytes[8] ushr 5)
                var ewVel = ewRaw - 1; var nsVel = nsRaw - 1
                if (subtype == 2) { ewVel = ewVel shl 2; nsVel = nsVel shl 2 }
                if ((f.bytes[5] and 0x04) != 0) ewVel = -ewVel
                if ((f.bytes[7] and 0x80) != 0) nsVel = -nsVel
                if (ewRaw > 0 && nsRaw > 0) {
                    fields.groundSpeedKt = Math.hypot(nsVel.toDouble(), ewVel.toDouble()).toInt()
                    var hdg = Math.toDegrees(Math.atan2(ewVel.toDouble(), nsVel.toDouble())).toInt()
                    if (hdg < 0) hdg += 360
                    fields.trackDeg = hdg
                    fields.speedType = "ground"
                }
            }
            // Airspeed variant. Previously written into fields.groundSpeedKt/
            // trackDeg — airspeed reported as ground speed, and magnetic
            // heading (this field) reported as true track (that one derived
            // from N/S+E/W velocity in the subtype 1/2 branch above). Ported
            // from `decode_airborne_velocity`'s `elif subtype in (3, 4)`
            // branch: `heading_deg`/`airspeed_kts`/`speed_type` are distinct
            // fields from `track_deg`/`ground_speed_kts` in the reference.
            3, 4 -> { // Airspeed
                var spd = ((f.bytes[7] and 0x7F) shl 3) or (f.bytes[8] ushr 5)
                if (spd > 0) {
                    spd--
                    if (subtype == 4) spd = spd shl 2
                    fields.airspeedKt = spd
                    fields.speedType = if ((f.bytes[7] and 0x80) != 0) "airspeed_tas" else "airspeed_ias"
                }
                if ((f.bytes[5] and 0x04) != 0) {
                    var hdg = (((f.bytes[5] and 0x03) shl 8) or f.bytes[6]) * 45 ushr 7
                    if (hdg < 0) hdg += 360
                    fields.headingDeg = hdg
                }
            }
        }
    }

    // ── TC29: Target State and Status ─────────────────────────────────────────
    private fun decodeTC29(f: RawFrame, subtype: Int, fields: AdsbFields) {
        when (subtype) {
            0 -> { // DO-260A format
                fields.altitudeSource = if ((f.bytes[5] and 0x40) != 0) "ASL" else "BARO"
                val selAlt = ((f.bytes[5] shl 9) or (f.bytes[6] shl 1) or (f.bytes[7] ushr 7)) and 0x3FF
                if (selAlt < 1011) fields.selectedAltitudeFt = selAlt * 100 - 1000
                val selHdg = ((f.bytes[7] shl 4) or (f.bytes[8] ushr 4)) and 0x1FF
                if (selHdg < 360) {
                    if ((f.bytes[8] and 0x8) == 0) fields.selectedHeadingDeg = selHdg
                }
                fields.autoPilotEngaged = (f.bytes[8] and 0x4) != 0
                val eCode = f.bytes[10] and 0x7
                if (eCode == 1) fields.squawk = "7700"
                else if (eCode == 4) fields.squawk = "7600"
                else if (eCode == 5) fields.squawk = "7500"
            }
            1 -> { // DO-260B format
                fields.altitudeSource = if ((f.bytes[5] and 0x80) != 0) "FMS" else "MCP/FCU"
                val selAlt = ((f.bytes[5] shl 4) or (f.bytes[6] ushr 4)) and 0x7FF
                if (selAlt > 0) fields.selectedAltitudeFt = selAlt * 32 - 32
                val baro = (((f.bytes[6] shl 5) or (f.bytes[7] ushr 3)) and 0x1FF) * 0.8f - 0.8f
                fields.baroSettingMbar = baro
                if ((f.bytes[7] and 0x4) != 0) {
                    var hdg = ((f.bytes[7] shl 7) or (f.bytes[8] ushr 1)) and 0xFF
                    hdg = Math.round(hdg * 180.0 / 256).toInt()
                    if ((f.bytes[7] and 0x10) != 0) hdg = -hdg
                    if (hdg < 0) hdg += 360
                    fields.selectedHeadingDeg = hdg
                }
                if ((f.bytes[9] and 0x2) != 0) {
                    fields.autoPilotEngaged = (f.bytes[9] and 0x1) != 0
                }
            }
        }
    }

    /**
     * TC31: Aircraft Operational Status (DO-260B §A.1.7.9).
     *
     * `nac_p` and `sil` previously read from `f.bytes[9]`/`f.bytes[10]`
     * (= `me[5]`/`me[6]`) — the wrong bytes entirely, and for `sil`, a
     * subtype-dependent branch the reference doesn't have at all: Python's
     * `_decode_operational_status` computes `version`/`nac_p`/`nac_v`/`sil`
     * identically regardless of airborne (subtype 0) vs surface (subtype 1).
     * Ported: `version = (me[5]>>5)&7`, `nac_p = me[3]&0x0F`,
     * `sil = (me[4]>>1)&0x03` (`me[3]` = `f.bytes[7]`, `me[4]` = `f.bytes[8]`,
     * `me[5]` = `f.bytes[9]`). `nac_v` (`(me[4]>>3)&0x07`) is in the reference
     * but still not decoded here — out of scope for this fix.
     */
    private fun decodeTC31(f: RawFrame, subtype: Int, fields: AdsbFields) {
        if (f.bytes.size < 11) return
        fields.versionNumber   = (f.bytes[9] ushr 5) and 0x07
        fields.nicSupplementA  = (f.bytes[9] and 0x10) != 0
        fields.nacP            = f.bytes[7] and 0x0F
        fields.nacV            = (f.bytes[8] ushr 3) and 0x07
        fields.sil              = (f.bytes[8] ushr 1) and 0x03
        if (subtype == 0) { // airborne — GVA and TCAS-operational have no Python equivalent, Android-only
            // TCAS/ACAS operational: bit 45 of message = f.bytes[5] bit 2 from LSB
            fields.tcasOperational = (f.bytes[5] and 0x04) != 0
            // f.bytes[10]: GVA(2) | ...
            fields.gva = (f.bytes[10] and 0xC0) ushr 6
        }
    }

    // -- Altitude decode -------------------------------------------------------
    /** Gillham Gray code to plain binary. Port of `_gray2int`. */
    private fun gray2int(value: Int): Int {
        var n = value
        n = n xor (n shr 8)
        n = n xor (n shr 4)
        n = n xor (n shr 2)
        n = n xor (n shr 1)
        return n
    }

    /**
     * Shared Q=0 Gillham tail. The caller assembles [gc500] and [gc100] because
     * the 12- and 13-bit fields carry those bits at different positions.
     */
    private fun gillhamFeet(gc500: Int, gc100: Int): Int? {
        val n500 = gray2int(gc500)
        var n100 = gray2int(gc100)
        if (n100 == 0 || n100 == 5 || n100 == 6) return null
        if (n100 == 7) n100 = 5
        if (n500 % 2 != 0) n100 = 6 - n100
        return n500 * 500 + n100 * 100 - 1300
    }

    /**
     * Decode a 12-bit AC field from a DF17/18 airborne position message.
     * Port of `decode_altitude_12bit`.
     *
     * DO-260B A.2.3.3.1: this field is 12 bits and has no M (metric) bit --
     * bit 6 is a data bit. Treating it as 13-bit silently drops valid readings
     * whenever C4 is set.
     */
    fun decodeAc12Field(ac12: Int): Int? {
        if (ac12 == 0) return null
        val qBit = (ac12 ushr 4) and 1

        if (qBit == 1) {
            val n = ((ac12 ushr 5) shl 4) or (ac12 and 0xF)
            if (n == 0) return null
            return n * 25 - 1000
        }

        fun b(pos: Int) = (ac12 ushr (11 - pos)) and 1
        val c1 = b(0); val a1 = b(1)
        val c2 = b(2); val a2 = b(3)
        val c4 = b(4); val b1 = b(5)
        val b2 = b(7); val d2 = b(8)
        val b4 = b(9); val d4 = b(10)

        val gc500 = (d2 shl 7) or (d4 shl 6) or (a1 shl 5) or (a2 shl 4) or
            (b1 shl 2) or (b2 shl 1) or b4
        val gc100 = (c1 shl 2) or (c2 shl 1) or c4
        return gillhamFeet(gc500, gc100)
    }

    /**
     * Decode a 13-bit AC field (DF4 / DF20 surveillance and Comm-B replies).
     * Port of `decode_altitude_13bit` / `_decode_gillham`.
     */
    fun decodeAc13Field(ac13: Int): Int? {
        if (ac13 == 0) return null
        val mBit = (ac13 ushr 6) and 1
        val qBit = (ac13 ushr 4) and 1

        if (mBit == 1) return null           // metric altitude is not supported

        if (qBit == 1) {
            val n = ((ac13 ushr 2) and 0x7E0) or ((ac13 ushr 1) and 0x10) or (ac13 and 0xF)
            return n * 25 - 1000
        }

        fun b(pos: Int) = (ac13 ushr (12 - pos)) and 1
        val c1 = b(0); val a1 = b(1)
        val c2 = b(2); val a2 = b(3)
        val c4 = b(4); val a4 = b(5)
        val b1 = b(7)
        val b2 = b(9); val d2 = b(10)
        val b4 = b(11); val d4 = b(12)

        val gc500 = (d2 shl 7) or (d4 shl 6) or (a1 shl 5) or (a2 shl 4) or
            (a4 shl 3) or (b1 shl 2) or (b2 shl 1) or b4
        val gc100 = (c1 shl 2) or (c2 shl 1) or c4
        return gillhamFeet(gc500, gc100)
    }

    // ── Squawk decode ─────────────────────────────────────────────────────────
    /**
     * Decode a 13-bit identity code to a 4-digit octal squawk string.
     *
     * Port of `decoder/adsb_decoder.py:_decode_identity`. Returns the formatted
     * string, exactly as the reference does, rather than a packed integer: the
     * previous version returned dump1090's hex-coded layout, which the UI then
     * rendered as octal and turned 6272 into "61162". Keeping the formatting
     * inside the decoder makes that class of mistake impossible.
     *
     * Bit layout: C1 A1 C2 A2 C4 A4 X B1 D1 B2 D2 B4 D4
     * X (M bit) is unused and D1 is SPI — neither is part of the code.
     */
    fun decodeIdentity(id13: Int): String {
        val c1 = (id13 ushr 12) and 1
        val a1 = (id13 ushr 11) and 1
        val c2 = (id13 ushr 10) and 1
        val a2 = (id13 ushr 9) and 1
        val c4 = (id13 ushr 8) and 1
        val a4 = (id13 ushr 7) and 1
        val b1 = (id13 ushr 5) and 1
        val b2 = (id13 ushr 3) and 1
        val d2 = (id13 ushr 2) and 1
        val b4 = (id13 ushr 1) and 1
        val d4 = id13 and 1

        val a = a4 * 4 + a2 * 2 + a1
        val b = b4 * 4 + b2 * 2 + b1
        val c = c4 * 4 + c2 * 2 + c1
        val d = d4 * 4 + d2 * 2          // D1 is SPI, not part of the code
        return "$a$b$c$d"
    }

    private fun decodeMovementField(movement: Int): Int = when {
        movement > 123 -> 199
        movement > 108 -> (movement - 108) * 5 + 100
        movement > 93  -> (movement - 93) * 2 + 70
        movement > 38  -> (movement - 38) + 15
        movement > 12  -> ((movement - 11) ushr 1) + 2
        movement > 8   -> ((movement - 6) ushr 2) + 1
        else           -> 0
    }
}

/** ARA bit position (0=MSB of the 14-bit field) -> advisory label. DO-185B Table A-9. */
private val ARA_BITS = mapOf(
    0 to "Climb", 1 to "Don't climb",
    2 to "Descend", 3 to "Don't descend",
    4 to "Turn right", 5 to "Don't turn right",
    6 to "Turn left", 7 to "Don't turn left",
    8 to "Increase climb", 9 to "Increase descent",
    10 to "Crossing climb", 11 to "Crossing descent",
    12 to "Sense reversal", 13 to "Vertical speed limit",
)

/** RAC bit position (0=MSB of the 4-bit field) -> complement label. */
private val RAC_BITS = mapOf(
    0 to "Don't pass below", 1 to "Don't pass above",
    2 to "Turn right", 3 to "Turn left",
)

/** Decoded ADS-B field bag — mutable during decoding, then attached to AdsbMessage. */
data class AdsbFields(
    var callsign: String?           = null,
    var emitterCategory: Int?       = null,
    /**
     * Null = this TC doesn't report ground status at all (TC1-4/19/29/31) —
     * distinct from an explicit `false`. `AircraftManager.mergeAdsb` relies on
     * this to know when to leave the aircraft's previous value alone versus
     * overwrite it either direction. Was `Boolean = false` (non-nullable),
     * which made "not reported" indistinguishable from "explicitly airborne" —
     * see #10 in `docs/correction_plan.md`.
     */
    var onGround: Boolean?          = null,
    var latitude: Double?           = null,
    var longitude: Double?          = null,
    var altitudeFt: Int?            = null,
    /** TC 20-22 only — GNSS/geometric altitude, kept separate from [altitudeFt] (barometric). */
    var altitudeGnssFt: Int?        = null,
    var groundSpeedKt: Int?         = null,
    var trackDeg: Int?              = null,
    /** TC19 subtype 3/4 only — magnetic heading, distinct from [trackDeg]'s true track. */
    var headingDeg: Int?            = null,
    /** TC19 subtype 3/4 only — distinct from [groundSpeedKt]. */
    var airspeedKt: Int?            = null,
    /** `"ground"` | `"airspeed_ias"` | `"airspeed_tas"` — which of [groundSpeedKt]/[airspeedKt] is populated and how. */
    var speedType: String?          = null,
    var verticalRateFpm: Int?       = null,
    var squawk: String?             = null,
    var selectedAltitudeFt: Int?    = null,
    var selectedHeadingDeg: Int?    = null,
    var altitudeSource: String?     = null,
    var baroSettingMbar: Float?     = null,
    var autoPilotEngaged: Boolean   = false,
    // TC31 Aircraft Operational Status
    var versionNumber: Int?         = null,
    var nicSupplementA: Boolean     = false,
    var nacP: Int?                  = null,
    var nacV: Int?                  = null,
    var sil: Int?                   = null,
    var gva: Int?                   = null,
    var tcasOperational: Boolean    = false,
)
