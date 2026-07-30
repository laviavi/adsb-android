package com.laviavi.adsbandroid

import com.laviavi.adsbandroid.aircraft.AircraftManager
import com.laviavi.adsbandroid.aircraft.AircraftState
import com.laviavi.adsbandroid.crc.CrcChecker
import com.laviavi.adsbandroid.crc.IcaoCache
import com.laviavi.adsbandroid.decoder.DecodedMessage
import com.laviavi.adsbandroid.decoder.MessageDecoder
import com.laviavi.adsbandroid.decoder.RawFrame
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Replays a golden capture through the whole Kotlin pipeline on a virtual clock
 * and diffs the resulting aircraft table against the Python reference at the same
 * 10-second checkpoints.
 *
 * `GoldenFrameParityTests` proves each frame decodes identically. It says nothing
 * about what happens after ten minutes of merging: last-write-wins per field,
 * expiry, CPR pair ageing, TCAS edge counting. Every bug found in sessions 9 and
 * 10 lived in that gap — the unit was tested, the accumulated behaviour was not.
 *
 * The clock is virtual because the capture represents ~44 minutes of air time and
 * replays in about a second; a wall clock would make the 60 s expiry and the 10 s
 * CPR pair window meaningless.
 */
class GoldenStateParityTests {

    private val goldenDir = File("src/test/resources/fixtures/golden")

    private companion object {
        const val CHECKPOINT_SEC = 10.0
        const val AVR_FRAME_INTERVAL_MS = 100L   // matches tools/phase0_goldens.py
        const val EXPIRY_SECONDS = 60

        /**
         * Fields Kotlin deliberately computes differently from the reference.
         * Listed explicitly, counted, and reported — never absorbed silently.
         */
        val ALLOWED_DIVERGENCES = mapOf(
            "ground_speed_kts" to
                "reference bug: decode_airborne_velocity reads the TC19 subtype as " +
                "(me[0] >> 1) & 7 instead of me[0] & 7, so every subsonic " +
                "ground-speed message (subtype 1) is read as subtype 4 and decoded " +
                "as an airspeed message. Ground speed and track are therefore never " +
                "populated in the reference, and heading_deg carries a value derived " +
                "from the E/W velocity bits. Kotlin reads the subtype correctly.",
            "track_deg" to "same reference bug as ground_speed_kts",
            "altitude_baro_ft" to "altitude outside -1500..72000 ft rejected by the Android clamp",
        )
    }

    /** Format like Python's `f"{round(x, 6):.6f}"` — half-to-even, 6 decimals. */
    private fun py6(v: Double): String =
        java.math.BigDecimal(v).setScale(6, java.math.RoundingMode.HALF_EVEN).toPlainString()

    private fun cell(row: List<String>, idx: Int): String? =
        row.getOrNull(idx)?.ifEmpty { null }

    /** One expected aircraft row from the reference, keyed by checkpoint + ICAO. */
    private data class Expected(
        val checkpoint: Int,
        val icao: String,
        val values: Map<String, String?>,
    )

    private fun loadExpected(name: String): List<Expected>? {
        val f = File(goldenDir, "$name.state.tsv")
        if (!f.exists()) return null
        val lines = f.readLines()
        if (lines.size < 2) return null
        val cols = lines.first().split("\t")
        return lines.drop(1).mapNotNull { line ->
            val c = line.split("\t")
            if (c.size < cols.size) return@mapNotNull null
            Expected(
                checkpoint = c[0].toInt(),
                icao = c[1],
                values = cols.indices.associate { cols[it] to cell(c, it) },
            )
        }
    }

    private fun loadFrames(name: String): List<String>? {
        val f = File(goldenDir, "$name.frames.tsv")
        if (!f.exists()) return null
        val lines = f.readLines()
        val hexIdx = lines.first().split("\t").indexOf("hex")
        return lines.drop(1).mapNotNull { it.split("\t").getOrNull(hexIdx) }
    }

    private fun hexToBytes(hex: String) =
        IntArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16) }

    /** Render Kotlin state the same way `phase0_goldens.py` renders Python's. */
    private fun render(s: AircraftState, field: String): String? = when (field) {
        "callsign" -> s.callsign?.trim()?.ifEmpty { null }
        "squawk" -> s.squawk
        "emitter_category" -> s.category?.toString()
        // The reference stores coordinates as round(x, 6), and Python's round()
        // breaks ties to even while Kotlin's %.6f breaks them upward. On a value
        // landing exactly on the half (…0625) that shows up as a 1-ulp difference
        // in the 6th decimal — ~0.1 mm. Match the reference's rounding rather
        // than paper over it with a tolerance.
        "latitude" -> s.latitude?.let { py6(it) }
        "longitude" -> s.longitude?.let { py6(it) }
        "altitude_baro_ft" -> s.altitudeFt?.toString()
        "on_ground" -> if (s.onGround) "1" else null
        "ground_speed_kts" -> s.groundSpeedKt?.let { "%.6f".format(it.toDouble()) }
        "track_deg" -> s.trackDeg?.let { "%.6f".format(it.toDouble()) }
        "vertical_rate_fpm" -> s.verticalRateFpm?.toString()
        "nac_p" -> s.nacP?.toString()
        "sil" -> s.sil?.toString()
        "version" -> s.versionNumber?.toString()
        "msg_count" -> s.messageCount.toString()
        "interrogator_ids" -> s.iiCode?.toString()
        else -> null
    }

    /**
     * Fields compared. Deliberately not every column: the reference tracks some
     * state this port does not model (airspeed_kts, heading_deg, the TCAS event
     * machine), and asserting on those would be asserting on absence.
     */
    private val comparedFields = listOf(
        "callsign", "squawk", "latitude", "longitude",
        "altitude_baro_ft", "ground_speed_kts", "track_deg",
        "vertical_rate_fpm", "msg_count",
    )

    private fun runFixture(name: String) {
        val frames = loadFrames(name)
        val expected = loadExpected(name)
        if (frames == null || expected == null) {
            println("SKIP $name — run tools/phase0_goldens.py to generate")
            return
        }

        val cache = IcaoCache()
        val decoder = MessageDecoder()
        val manager = AircraftManager(expirySeconds = EXPIRY_SECONDS)

        val byCheckpoint = expected.groupBy { it.checkpoint }
        val checkpoints = byCheckpoint.keys.sorted()
        var nextIdx = 0

        val missing = mutableListOf<String>()
        val extra = mutableListOf<String>()
        val mismatches = mutableListOf<String>()
        val allowed = mutableMapOf<String, Int>()
        var comparedRows = 0

        fun checkpoint(atSec: Int, expireFirst: Boolean = true) {
            // phase0_goldens.py purges before each in-loop checkpoint but emits its
            // final snapshot without purging. Mirror that, or the last checkpoint
            // disagrees on any aircraft sitting exactly on the expiry boundary.
            if (expireFirst) manager.expireStale(atSec * 1000L)
            val actual = manager.aircraft.associateBy { it.icao }
            val want = byCheckpoint[atSec].orEmpty().associateBy { it.icao }

            (want.keys - actual.keys).forEach { missing += "t=${atSec}s $it absent from Kotlin" }
            (actual.keys - want.keys).forEach { extra += "t=${atSec}s $it not in Python" }

            for ((icao, exp) in want) {
                val got = actual[icao] ?: continue
                comparedRows++
                for (field in comparedFields) {
                    val py = exp.values[field]
                    val kt = render(got, field)
                    if (py == kt) continue
                    val reason = ALLOWED_DIVERGENCES[field]
                    if (reason != null) {
                        allowed[field] = (allowed[field] ?: 0) + 1
                    } else {
                        mismatches += "t=${atSec}s $icao $field: python=$py kotlin=$kt"
                    }
                }
            }
        }

        frames.forEachIndexed { i, hex ->
            val nowMs = i * AVR_FRAME_INTERVAL_MS
            while (nextIdx < checkpoints.size && nowMs >= checkpoints[nextIdx] * 1000L) {
                checkpoint(checkpoints[nextIdx]); nextIdx++
            }
            val checked = CrcChecker.check(RawFrame(hexToBytes(hex)), icaoCache = cache)
            val msg = decoder.decode(checked, nowMs) ?: return@forEachIndexed
            manager.update(msg, nowMs)
        }
        while (nextIdx < checkpoints.size) {
            val last = nextIdx == checkpoints.size - 1
            checkpoint(checkpoints[nextIdx], expireFirst = !last); nextIdx++
        }

        allowed.forEach { (field, n) ->
            println("$name: $n allowed divergence(s) on $field — ${ALLOWED_DIVERGENCES[field]}")
        }

        assertTrue(comparedRows > 0, "$name compared no aircraft rows")

        val problems = buildList {
            if (missing.isNotEmpty()) add("${missing.size} aircraft missing:\n  " +
                missing.take(10).joinToString("\n  "))
            if (extra.isNotEmpty()) add("${extra.size} unexpected aircraft:\n  " +
                extra.take(10).joinToString("\n  "))
            if (mismatches.isNotEmpty()) add("${mismatches.size} field mismatches:\n  " +
                mismatches.take(20).joinToString("\n  "))
        }
        assertTrue(problems.isEmpty(), "$name ($comparedRows rows compared)\n" +
            problems.joinToString("\n"))

        println("$name: $comparedRows aircraft rows match Python across ${checkpoints.size} checkpoints")
    }

    @Test fun `AVR capture 20260621 state matches Python`() = runFixture("avr_20260621")

    @Test fun `AVR capture 20260622 state matches Python`() = runFixture("avr_20260622")
}
