package com.laviavi.adsbandroid

import com.laviavi.adsbandroid.crc.CrcChecker
import com.laviavi.adsbandroid.crc.IcaoCache
import com.laviavi.adsbandroid.decoder.DecodedMessage
import com.laviavi.adsbandroid.decoder.MessageDecoder
import com.laviavi.adsbandroid.decoder.RawFrame
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Replays the Phase 0 golden fixtures through the Kotlin pipeline and compares
 * the decoded fields to the Python receiver's output, frame by frame.
 *
 * This is the check that was missing: the goldens were captured, then the Kotlin
 * decoder was changed without ever diffing against them. Both the squawk and the
 * Gillham altitude bugs are the kind this catches, because it compares the value
 * a person reads rather than an internal representation.
 *
 * Skips cleanly when the fixtures have not been generated
 * (`tools/phase0_goldens.py`) so a fresh clone does not fail on a missing file.
 */
class GoldenFrameParityTests {

    private val goldenDir = File("src/test/resources/fixtures/golden")

    private data class Row(
        val hex: String,
        val df: Int,
        val crcStatus: String,
        val icao: String?,
        val callsign: String?,
        val squawk: String?,
        val altBaro: Int?,
    )

    private fun load(name: String): List<Row>? {
        val f = File(goldenDir, "$name.frames.tsv")
        if (!f.exists()) return null
        val lines = f.readLines()
        if (lines.size < 2) return null
        val cols = lines.first().split("\t")
        fun idx(n: String) = cols.indexOf(n)
        val iHex = idx("hex"); val iDf = idx("df"); val iCrc = idx("crc_status")
        val iIcao = idx("icao"); val iCs = idx("callsign")
        val iSq = idx("squawk"); val iAlt = idx("altitude_baro_ft")

        return lines.drop(1).mapNotNull { line ->
            val c = line.split("\t")
            if (c.size <= maxOf(iHex, iAlt)) return@mapNotNull null
            Row(
                hex = c[iHex],
                df = c[iDf].toInt(),
                crcStatus = c[iCrc],
                icao = c[iIcao].ifEmpty { null },
                callsign = c[iCs].ifEmpty { null },
                squawk = c[iSq].ifEmpty { null },
                altBaro = c[iAlt].ifEmpty { null }?.toInt(),
            )
        }
    }

    private fun hexToBytes(hex: String) =
        IntArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16) }

    private fun runFixture(name: String) {
        val rows = load(name) ?: run {
            println("SKIP $name — run tools/phase0_goldens.py to generate")
            return
        }

        val cache = IcaoCache()
        val decoder = MessageDecoder()
        val mismatches = mutableListOf<String>()
        var compared = 0

        for (row in rows) {
            val checked = CrcChecker.check(RawFrame(hexToBytes(row.hex)), icaoCache = cache)
            val msg = decoder.decode(checked) ?: continue
            compared++

            fun note(field: String, py: Any?, kt: Any?) {
                if (py?.toString() != kt?.toString()) {
                    mismatches += "${row.hex} DF${row.df} $field: python=$py kotlin=$kt"
                }
            }

            row.icao?.let { note("icao", it, "%06X".format(msg.icao)) }

            val adsb = msg as? DecodedMessage.AdsbMessage
            val ktCallsign = when (msg) {
                is DecodedMessage.AdsbMessage    -> msg.fields.callsign
                is DecodedMessage.AltitudeReply  -> msg.callsign
                is DecodedMessage.IdentityReply  -> msg.callsign
                else -> null
            }?.trim()?.ifEmpty { null }
            // Known, deliberate divergence: DF20/21 Comm-B BDS 2,0 callsign.
            // The reference's DF20/21 handlers decode altitude and identity only
            // and never look at the MB field, so it reports no callsign here. The
            // Comm-B decoder was added on request (Session 5) and is additive —
            // it invents nothing the reference contradicts, it reads a register
            // the reference does not read. Allow-listed rather than silently
            // tolerated: any callsign divergence on a DF the reference *does*
            // decode still fails.
            val commBCallsign = row.df == 20 || row.df == 21
            if (!commBCallsign && (row.callsign != null || ktCallsign != null)) {
                note("callsign", row.callsign, ktCallsign)
            }
            if (commBCallsign && row.callsign != null) {
                note("callsign", row.callsign, ktCallsign)
            }

            // Altitude is what the Gillham port changed; compare whenever either
            // side produced one, so a spurious value is caught as well as a miss.
            val ktAlt = when (msg) {
                is DecodedMessage.AdsbMessage   -> msg.fields.altitudeFt
                is DecodedMessage.AltitudeReply -> msg.altitudeFt
                else -> null
            }
            if (row.altBaro != null || ktAlt != null) {
                note("altitude", row.altBaro, ktAlt)
            }

            val ktSquawk = (msg as? DecodedMessage.IdentityReply)?.squawk
                ?: adsb?.fields?.squawk
            if (row.squawk != null || ktSquawk != null) {
                note("squawk", row.squawk, ktSquawk)
            }
        }

        assertTrue(compared > 0, "$name decoded no frames")
        assertTrue(
            mismatches.isEmpty(),
            "$name: $compared frames compared, ${mismatches.size} mismatched:\n" +
                mismatches.take(15).joinToString("\n"),
        )
        println("$name: $compared frames match Python")
    }

    @Test fun `AVR capture 20260621 matches Python`() = runFixture("avr_20260621")

    @Test fun `AVR capture 20260622 matches Python`() = runFixture("avr_20260622")

    @Test fun `IQ fixture matches Python`() = runFixture("modes1_iq")
}
