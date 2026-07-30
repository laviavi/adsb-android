package com.laviavi.adsbandroid.observability

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Port of `observability/performance.py`'s `_write_row`. Ground truth for the
 * counting policy (valid = union of valid+corrected+recovered, parity-address
 * frames count toward total only) comes from reading `core.py:_process_raw`
 * directly, not from re-deriving it — see the divergence note on FA counters.
 */
class PerformanceMetricsTests {

    private fun counters(total: Long, valid: Long, corrected: Long, recovered: Long, bad: Long) =
        MessageCounters(total, valid, corrected, recovered, bad)

    @Nested inner class Deltas {

        @Test fun `msgs_valid unions valid, corrected and recovered like the reference`() {
            // Mirrors core.py: a corrected frame sets BOTH valid=1 and corrected=1
            // in Python's own counter, so `valid` is already inclusive there.
            // Kotlin's `valid` counter (from PipelineStats) mirrors that same
            // policy — it already includes recovered, so only `corrected` needs
            // adding on top here.
            val prev = counters(total = 0, valid = 0, corrected = 0, recovered = 0, bad = 0)
            val cur  = counters(total = 100, valid = 85, corrected = 10, recovered = 5, bad = 15)
            val row = PerformanceMetrics.computeRow(prev, cur, emptySet(), emptySet())
            // valid(85) already includes the 5 recovered; +10 corrected on top = 95.
            assertEquals(95, row.msgsValid)
            assertEquals(10, row.msgsCorrected)
            assertEquals(5, row.msgsRecovered)
            assertEquals(15, row.msgsNoise)
            assertEquals(100, row.msgsTotal)
        }

        @Test fun `deltas never go negative across a counter reset`() {
            val prev = counters(total = 1000, valid = 900, corrected = 10, recovered = 5, bad = 90)
            val cur  = counters(total = 0, valid = 0, corrected = 0, recovered = 0, bad = 0)
            val row = PerformanceMetrics.computeRow(prev, cur, emptySet(), emptySet())
            assertEquals(0, row.msgsTotal)
            assertEquals(0, row.msgsValid)
            assertEquals(0, row.msgsNoise)
        }
    }

    @Nested inner class Ratios {

        @Test fun `rate is total delta over the interval`() {
            val prev = counters(0, 0, 0, 0, 0)
            val cur = counters(total = 120, valid = 120, corrected = 0, recovered = 0, bad = 0)
            val row = PerformanceMetrics.computeRow(prev, cur, emptySet(), emptySet(), intervalSec = 60)
            assertEquals(2.0, row.msgRatePerSec, 1e-9)
        }

        @Test fun `zero total avoids a divide-by-zero and reports zero ratios`() {
            val row = PerformanceMetrics.computeRow(counters(0,0,0,0,0), counters(0,0,0,0,0), emptySet(), emptySet())
            assertEquals(0.0, row.decodeSuccessRatio)
            assertEquals(0.0, row.crcFailureRatio)
            assertEquals(0.0, row.crcRecoveryRatio)
        }
    }

    @Nested inner class ActiveAircraftAndNewIcaos {

        @Test fun `active aircraft is the size of the current ICAO set`() {
            val row = PerformanceMetrics.computeRow(
                counters(10,10,0,0,0), counters(20,20,0,0,0),
                previousIcaos = setOf("A1", "A2"),
                currentIcaos = setOf("A1", "A2", "A3"),
            )
            assertEquals(3, row.activeAircraft)
            assertEquals(1, row.uniqueIcaoInterval, "only A3 is new this interval")
        }

        @Test fun `an aircraft departing does not count as a new arrival`() {
            val row = PerformanceMetrics.computeRow(
                counters(10,10,0,0,0), counters(20,20,0,0,0),
                previousIcaos = setOf("A1", "A2"),
                currentIcaos = setOf("A1"),
            )
            assertEquals(0, row.uniqueIcaoInterval)
        }
    }

    @Nested inner class DiagnosisHint {

        @Test fun `no messages this interval hints no_messages`() {
            val row = PerformanceMetrics.computeRow(counters(0,0,0,0,0), counters(0,0,0,0,0), emptySet(), emptySet())
            assertEquals("no_messages", row.diagnosisHint)
        }

        @Test fun `success ratio below 50 percent hints high_noise before checking crc failure`() {
            // total=100, valid-union=40 (< 0.5), bad=35 (> 0.3 too) — high_noise wins,
            // matching the reference's if/elif order (checked before crc_failure_ratio).
            val row = PerformanceMetrics.computeRow(
                counters(0,0,0,0,0), counters(total = 100, valid = 40, corrected = 0, recovered = 0, bad = 35),
                emptySet(), emptySet(),
            )
            assertEquals("high_noise", row.diagnosisHint)
        }

        @Test fun `high crc failure alone hints high_crc_failure`() {
            val row = PerformanceMetrics.computeRow(
                counters(0,0,0,0,0), counters(total = 100, valid = 60, corrected = 0, recovered = 0, bad = 35),
                emptySet(), emptySet(),
            )
            assertEquals("high_crc_failure", row.diagnosisHint)
        }

        @Test fun `healthy pipeline hints nothing`() {
            val row = PerformanceMetrics.computeRow(
                counters(0,0,0,0,0), counters(total = 100, valid = 90, corrected = 0, recovered = 0, bad = 5),
                emptySet(), emptySet(),
            )
            assertEquals("", row.diagnosisHint)
        }
    }

    @Nested inner class FlightAwareColumnsAreDeadOnAndroid {

        @Test fun `FA columns are always zero - the scraper was never ported`() {
            val row = PerformanceMetrics.computeRow(counters(0,0,0,0,0), counters(100,90,0,0,10), emptySet(), emptySet())
            val values = PerformanceMetrics.toCsvValues(row, CsvTimestamps("u", "l", "z", "+00:00"))
            val faIndices = listOf("fa_queries_sent", "fa_queries_succeeded", "fa_queries_failed")
                .map { PerformanceMetrics.COLUMNS.indexOf(it) }
            faIndices.forEach { assertEquals("0", values[it]) }
        }

        @Test fun `fa_scraper_degraded can never be the hint`() {
            // Any input that would have triggered it in Python (FA failures
            // exceeding successes) simply can't arise here since FA counters
            // don't exist on this side at all.
            val row = PerformanceMetrics.computeRow(
                counters(0,0,0,0,0), counters(total = 100, valid = 90, corrected = 0, recovered = 0, bad = 5),
                emptySet(), emptySet(),
            )
            assertNotEquals("fa_scraper_degraded", row.diagnosisHint)
        }
    }

    @Nested inner class CsvFormatting {

        @Test fun `column list matches the Python reference name and order`() {
            assertEquals(
                listOf(
                    "timestamp_utc", "timestamp_local", "timezone_name", "utc_offset",
                    "interval_sec",
                    "msgs_total", "msgs_valid", "msgs_noise", "msgs_recovered", "msgs_corrected",
                    "msg_rate_per_sec",
                    "decode_success_ratio", "crc_failure_ratio", "crc_recovery_ratio",
                    "active_aircraft", "unique_icao_interval",
                    "fa_queries_sent", "fa_queries_succeeded", "fa_queries_failed",
                    "diagnosis_hint",
                ),
                PerformanceMetrics.COLUMNS,
            )
        }

        @Test fun `rate uses 2 decimals and ratios use 3, matching Python's format strings`() {
            val row = PerformanceMetrics.computeRow(
                counters(0,0,0,0,0), counters(total = 3, valid = 1, corrected = 0, recovered = 0, bad = 1),
                emptySet(), emptySet(), intervalSec = 60,
            )
            val values = PerformanceMetrics.toCsvValues(row, CsvTimestamps("u", "l", "z", "+00:00"))
            assertEquals("0.05", values[PerformanceMetrics.COLUMNS.indexOf("msg_rate_per_sec")])
            assertEquals("0.333", values[PerformanceMetrics.COLUMNS.indexOf("decode_success_ratio")])
        }
    }
}
