package com.laviavi.adsbandroid.observability

import kotlin.math.roundToLong

/**
 * Running totals of every frame the CRC checker has classified, since process
 * start or the last [reset]. Mirrors the Python reference's `MessageStats`
 * fields one-for-one:
 *  - [valid] is already a union (VALID + CORRECTED + RECOVERED), because that
 *    is how the reference's own `valid` counter is incremented at the point of
 *    classification — not something summed later.
 *  - [corrected] and [recovered] are the same frames counted a second time, as
 *    their own breakout columns.
 *  - [badCrc] counts only genuinely-invalid CRCs. Parity-address frames
 *    (`CrcResult.PARITY_ADDRESS`) increment [total] only, exactly as the
 *    reference's `parity_addr` bucket does — they are neither valid nor bad.
 */
data class MessageCounters(
    val total: Long = 0,
    val valid: Long = 0,
    val corrected: Long = 0,
    val recovered: Long = 0,
    val badCrc: Long = 0,
)

data class PerformanceMetricsRow(
    val intervalSec: Int,
    val msgsTotal: Long,
    val msgsValid: Long,
    val msgsNoise: Long,
    val msgsRecovered: Long,
    val msgsCorrected: Long,
    val msgRatePerSec: Double,
    val decodeSuccessRatio: Double,
    val crcFailureRatio: Double,
    val crcRecoveryRatio: Double,
    val activeAircraft: Int,
    val uniqueIcaoInterval: Int,
    val diagnosisHint: String,
)

/**
 * Port of `observability/performance.py`: one row per 60 s interval,
 * summarising decode-pipeline health for before/after comparison when
 * changing antenna, gain, or software.
 *
 * FlightAware query counters (`fa_queries_sent/succeeded/failed`) are **not**
 * ported — the FA scraper itself is deliberately not ported (see
 * `docs/ANDROID_MIGRATION_PLAN.md` §3.3: ToS / fragility / battery). The three
 * columns are kept in [COLUMNS] for name-and-order parity with the reference
 * CSV, always emitted as `0`, and the reference's `fa_scraper_degraded` hint
 * is consequently unreachable here.
 */
object PerformanceMetrics {

    const val INTERVAL_SEC = 60

    val COLUMNS: List<String> = listOf(
        "timestamp_utc", "timestamp_local", "timezone_name", "utc_offset",
        "interval_sec",
        "msgs_total", "msgs_valid", "msgs_noise", "msgs_recovered", "msgs_corrected",
        "msg_rate_per_sec",
        "decode_success_ratio", "crc_failure_ratio", "crc_recovery_ratio",
        "active_aircraft", "unique_icao_interval",
        "fa_queries_sent", "fa_queries_succeeded", "fa_queries_failed",
        "diagnosis_hint",
    )

    /**
     * @param currentIcaos the full set of currently-tracked aircraft ICAOs —
     *   doubles as both the active-aircraft count and the new-ICAO delta, same
     *   as the reference computing both from one `mgr.all()` snapshot.
     */
    fun computeRow(
        previous: MessageCounters,
        current: MessageCounters,
        previousIcaos: Set<String>,
        currentIcaos: Set<String>,
        intervalSec: Int = INTERVAL_SEC,
    ): PerformanceMetricsRow {
        val dTotal     = (current.total - previous.total).coerceAtLeast(0)
        val dCorrected = (current.corrected - previous.corrected).coerceAtLeast(0)
        val dRecovered = (current.recovered - previous.recovered).coerceAtLeast(0)
        val dBad       = (current.badCrc - previous.badCrc).coerceAtLeast(0)
        // valid is already the (valid+corrected+recovered) union at the source,
        // so the delta of `valid` alone double-counts corrected+recovered once
        // more here — matching the reference's own `msgs_valid` semantics.
        val dValidUnion = ((current.valid - previous.valid) + dCorrected).coerceAtLeast(0)

        val rate          = if (intervalSec > 0) dTotal.toDouble() / intervalSec else 0.0
        val successRatio  = if (dTotal > 0) dValidUnion.toDouble() / dTotal else 0.0
        val failureRatio  = if (dTotal > 0) dBad.toDouble() / dTotal else 0.0
        val recoveryRatio = if (dTotal > 0) dRecovered.toDouble() / dTotal else 0.0

        val newIcaos = (currentIcaos - previousIcaos).size

        val hint = when {
            dTotal == 0L -> "no_messages"
            successRatio < 0.5 -> "high_noise"
            failureRatio > 0.3 -> "high_crc_failure"
            else -> ""
        }

        return PerformanceMetricsRow(
            intervalSec = intervalSec,
            msgsTotal = dTotal,
            msgsValid = dValidUnion,
            msgsNoise = dBad,
            msgsRecovered = dRecovered,
            msgsCorrected = dCorrected,
            msgRatePerSec = rate,
            decodeSuccessRatio = successRatio,
            crcFailureRatio = failureRatio,
            crcRecoveryRatio = recoveryRatio,
            activeAircraft = currentIcaos.size,
            uniqueIcaoInterval = newIcaos,
            diagnosisHint = hint,
        )
    }

    /**
     * Formats [row] as CSV field values in [COLUMNS] order, using the
     * reference's exact decimal precision (`.2f` rate, `.3f` ratios) so a
     * column-diff against a Python-produced file compares like for like.
     */
    fun toCsvValues(row: PerformanceMetricsRow, timestamps: CsvTimestamps): List<String> = listOf(
        timestamps.utc, timestamps.local, timestamps.zoneName, timestamps.utcOffset,
        row.intervalSec.toString(),
        row.msgsTotal.toString(), row.msgsValid.toString(), row.msgsNoise.toString(),
        row.msgsRecovered.toString(), row.msgsCorrected.toString(),
        "%.2f".format(row.msgRatePerSec),
        "%.3f".format(row.decodeSuccessRatio),
        "%.3f".format(row.crcFailureRatio),
        "%.3f".format(row.crcRecoveryRatio),
        row.activeAircraft.toString(), row.uniqueIcaoInterval.toString(),
        "0", "0", "0",
        row.diagnosisHint,
    )
}

/** UTC + local timestamp columns shared by both CSV exports. See [CsvTimestamps.now]. */
data class CsvTimestamps(val utc: String, val local: String, val zoneName: String, val utcOffset: String) {
    companion object {
        /**
         * `zoneName` is whatever the platform's default `TimeZone` display
         * name resolves to — like Python's `tzname()`, this is OS/locale
         * supplied and not something a cross-platform string-diff can
         * meaningfully assert on.
         */
        fun now(clock: () -> Long = System::currentTimeMillis): CsvTimestamps {
            val tz = java.util.TimeZone.getDefault()
            val instant = java.util.Date(clock())
            val utcFmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }
            val localFmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).apply {
                timeZone = tz
            }
            val offsetMs = tz.getOffset(instant.time)
            val totalMinutes = (kotlin.math.abs(offsetMs) / 60_000.0).roundToLong()
            val sign = if (offsetMs >= 0) "+" else "-"
            val offset = "%s%02d:%02d".format(sign, totalMinutes / 60, totalMinutes % 60)
            return CsvTimestamps(
                utc = utcFmt.format(instant),
                local = localFmt.format(instant),
                zoneName = tz.getDisplayName(tz.inDaylightTime(instant), java.util.TimeZone.SHORT),
                utcOffset = offset,
            )
        }
    }
}
