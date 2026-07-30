package com.laviavi.adsbandroid.decoder

/** Best-effort decode of a Comm-B (DF20/21) MB register. [bdsCode] is the detected register, e.g. "5,0". */
data class CommBFields(
    val bdsCode: String,
    val selectedAltitudeFt: Int?          = null,
    val baroSettingMbar: Float?           = null,
    val rollAngleDeg: Double?             = null,
    val trueTrackDeg: Double?             = null,
    val trackAngleRateDegPerSec: Double?  = null,
    val groundSpeedKt: Int?               = null,
    val trueAirspeedKt: Int?              = null,
    val magneticHeadingDeg: Double?       = null,
    val indicatedAirspeedKt: Int?         = null,
    val machNumber: Double?               = null,
    val verticalRateFpm: Int?             = null,
)

/**
 * Decodes Comm-B (DF20/21) MB field registers — BDS 4,0 (Selected Vertical Intention),
 * BDS 5,0 (Track and Turn Report), BDS 6,0 (Heading and Speed Report).
 * Ports the relevant subset of Python adsb_decoder.py's commb module.
 *
 * The MB field never self-identifies its BDS code (unlike DF17/18's typecode), so
 * candidates are validated by their reserved-bit and physical-range constraints —
 * the same technique dump1090/pyModeS use for BDS auto-detection.
 */
object CommBDecoder {

    /**
     * [mb] is the 7-byte (56-bit) MB field: frame bytes[4..10].
     * More than one register can pass its own plausibility check on the same bits
     * (the constraints are necessary, not sufficient); when that happens, the
     * candidate that accounts for more of the message's active status bits wins —
     * a genuine register normally reports most of its fields, an accidental
     * collision usually only satisfies the bare minimum.
     */
    fun decode(mb: IntArray): CommBFields? {
        require(mb.size == 7) { "MB field must be 7 bytes" }
        val candidates = listOfNotNull(decode40(mb), decode50(mb), decode60(mb))
        return candidates.maxByOrNull { activeFieldCount(it) }
    }

    private fun activeFieldCount(f: CommBFields): Int = listOfNotNull(
        f.selectedAltitudeFt, f.baroSettingMbar, f.rollAngleDeg, f.trueTrackDeg,
        f.trackAngleRateDegPerSec, f.groundSpeedKt, f.trueAirspeedKt,
        f.magneticHeadingDeg, f.indicatedAirspeedKt, f.machNumber, f.verticalRateFpm,
    ).size

    // ── BDS 4,0 — Selected Vertical Intention ────────────────────────────────
    private fun decode40(mb: IntArray): CommBFields? {
        val statusMcp = (mb[0] and 0x80) != 0
        val mcpAlt = ((mb[0] and 0x7F) shl 5) or (mb[1] ushr 3)
        val statusFms = (mb[1] and 0x04) != 0
        val fmsAlt = ((mb[1] and 0x03) shl 10) or (mb[2] shl 2) or (mb[3] ushr 6)
        val statusBaro = (mb[3] and 0x20) != 0
        val baro = ((mb[3] and 0x1F) shl 7) or (mb[4] ushr 1)
        // Bits 40-47 are reserved and must be zero for a genuine BDS 4,0 register.
        val reserved = ((mb[4] and 0x01) shl 7) or (mb[5] ushr 1)
        if (reserved != 0) return null
        if (!statusMcp && !statusFms && !statusBaro) return null
        if (statusMcp && mcpAlt == 0) return null
        if (statusFms && fmsAlt == 0) return null
        if (statusBaro && baro == 0) return null

        val selAlt = when {
            statusMcp -> mcpAlt * 16
            statusFms -> fmsAlt * 16
            else -> null
        }
        if (selAlt != null && selAlt !in 0..60000) return null
        val baroMbar = if (statusBaro) baro * 0.1f + 800f else null
        return CommBFields(bdsCode = "4,0", selectedAltitudeFt = selAlt, baroSettingMbar = baroMbar)
    }

    // ── BDS 5,0 — Track and Turn Report ──────────────────────────────────────
    // ponytail: plausibility thresholds below are heuristic (dump1090/pyModeS-style
    // range sanity, not a spec-defined discriminator). Upgrade path if false-positive
    // BDS tagging shows up on real traffic: cross-check against the aircraft's last
    // confirmed register (DF17 typecode history) the way pyModeS's bds.infer does.
    private fun decode50(mb: IntArray): CommBFields? {
        val statusRoll = (mb[0] and 0x80) != 0
        val rollRaw = signExtend(((mb[0] and 0x7F) shl 3) or (mb[1] ushr 5), 10)
        val statusTrk = (mb[1] and 0x10) != 0
        val trkRaw = signExtend((((mb[1] and 0x0F) shl 7) or (mb[2] ushr 1)), 11)
        val statusGs = (mb[2] and 0x01) != 0
        val gsRaw = (mb[3] shl 2) or (mb[4] ushr 6)
        val statusRate = (mb[4] and 0x20) != 0
        val rateRaw = signExtend(((mb[4] and 0x1F) shl 5) or (mb[5] ushr 3), 10)
        val statusTas = (mb[5] and 0x04) != 0
        val tasRaw = ((mb[5] and 0x03) shl 8) or mb[6]
        if (!statusRoll && !statusTrk && !statusGs && !statusRate && !statusTas) return null

        val roll = if (statusRoll) rollRaw * 45.0 / 256.0 else null
        if (roll != null && Math.abs(roll) > 60.0) return null
        val trueTrack = if (statusTrk) normalizeDeg(trkRaw * 90.0 / 512.0) else null
        val gs = if (statusGs) gsRaw * 2 else null
        if (gs != null && gs !in 0..600) return null
        val rate = if (statusRate) rateRaw * 8.0 / 256.0 else null
        if (rate != null && Math.abs(rate) > 16.0) return null
        val tas = if (statusTas) tasRaw * 2 else null
        if (tas != null && tas !in 0..600) return null

        return CommBFields(
            bdsCode = "5,0",
            rollAngleDeg = roll,
            trueTrackDeg = trueTrack,
            groundSpeedKt = gs,
            trackAngleRateDegPerSec = rate,
            trueAirspeedKt = tas,
        )
    }

    // ── BDS 6,0 — Heading and Speed Report ───────────────────────────────────
    private fun decode60(mb: IntArray): CommBFields? {
        val statusHdg = (mb[0] and 0x80) != 0
        val hdgRaw = signExtend(((mb[0] and 0x7F) shl 4) or (mb[1] ushr 4), 11)
        val statusIas = (mb[1] and 0x08) != 0
        val iasRaw = ((mb[1] and 0x07) shl 7) or (mb[2] ushr 1)
        val statusMach = (mb[2] and 0x01) != 0
        val machRaw = (mb[3] shl 2) or (mb[4] ushr 6)
        val statusBaroRate = (mb[4] and 0x20) != 0
        val baroRateRaw = signExtend(((mb[4] and 0x1F) shl 5) or (mb[5] ushr 3), 10)
        val statusInsRate = (mb[5] and 0x04) != 0
        val insRateRaw = signExtend(((mb[5] and 0x03) shl 8) or mb[6], 10)
        if (!statusHdg && !statusIas && !statusMach && !statusBaroRate && !statusInsRate) return null

        val heading = if (statusHdg) normalizeDeg(hdgRaw * 90.0 / 512.0) else null
        val ias = if (statusIas) iasRaw else null
        if (ias != null && ias !in 0..600) return null
        val mach = if (statusMach) machRaw * 0.004 else null
        if (mach != null && mach !in 0.0..1.0) return null
        val baroRate = if (statusBaroRate) baroRateRaw * 32 else null
        if (baroRate != null && Math.abs(baroRate) > 6000) return null
        val insRate = if (statusInsRate) insRateRaw * 32 else null
        if (insRate != null && Math.abs(insRate) > 6000) return null
        // Prefer the inertial vertical-speed source when both are present (matches TC19 convention).
        val vrate = insRate ?: baroRate

        return CommBFields(
            bdsCode = "6,0",
            magneticHeadingDeg = heading,
            indicatedAirspeedKt = ias,
            machNumber = mach,
            verticalRateFpm = vrate,
        )
    }

    private fun signExtend(raw: Int, bits: Int): Int {
        val signBit = 1 shl (bits - 1)
        return if ((raw and signBit) != 0) raw - (1 shl bits) else raw
    }

    private fun normalizeDeg(deg: Double): Double { val d = deg % 360.0; return if (d < 0) d + 360.0 else d }
}
