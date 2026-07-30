package com.laviavi.adsbandroid

import com.laviavi.adsbandroid.capture.DongleInfo
import com.laviavi.adsbandroid.capture.GainOptions
import com.laviavi.adsbandroid.capture.RtlTcpGain
import com.laviavi.adsbandroid.capture.TunerType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Gain levels must come from the attached dongle, never a generic list — and
 * when the device can't be trusted to report them, nothing is applied silently.
 */
class RtlTcpGainTests {

    private fun header(tuner: Int, gains: Int): ByteArray {
        val b = ByteArray(12)
        RtlTcpGain.MAGIC.copyInto(b)
        for (i in 0..3) b[4 + i] = ((tuner shr ((3 - i) * 8)) and 0xFF).toByte()
        for (i in 0..3) b[8 + i] = ((gains shr ((3 - i) * 8)) and 0xFF).toByte()
        return b
    }

    @Nested inner class HeaderParsing {

        @Test fun `parses an R820T greeting`() {
            val info = RtlTcpGain.parseDongleInfo(header(5, 29))
            assertEquals(DongleInfo(TunerType.R820T, 29), info)
        }

        @Test fun `rejects a greeting with the wrong magic`() {
            val bad = header(5, 29).also { it[0] = 'X'.code.toByte() }
            assertNull(RtlTcpGain.parseDongleInfo(bad))
        }

        @Test fun `rejects a truncated greeting`() {
            assertNull(RtlTcpGain.parseDongleInfo(ByteArray(8)))
        }

        @Test fun `unknown tuner code maps to UNKNOWN rather than throwing`() {
            assertEquals(TunerType.UNKNOWN, RtlTcpGain.parseDongleInfo(header(99, 0))?.tuner)
        }
    }

    @Nested inner class GainTables {

        @Test fun `R820T exposes the 29 librtlsdr steps`() {
            val opts = RtlTcpGain.gainsFor(DongleInfo(TunerType.R820T, 29))
            assertTrue(opts is GainOptions.Available)
            val gains = (opts as GainOptions.Available).gainsTenths
            assertEquals(29, gains.size)
            assertEquals(0, gains.first())
            assertEquals(496, gains.last())
        }

        @Test fun `R828D shares the R82xx table`() {
            val opts = RtlTcpGain.gainsFor(DongleInfo(TunerType.R828D, 29))
            assertTrue(opts is GainOptions.Available)
        }

        @Test fun `E4000 exposes its own 14 steps`() {
            val opts = RtlTcpGain.gainsFor(DongleInfo(TunerType.E4000, 14))
            assertEquals(14, (opts as GainOptions.Available).gainsTenths.size)
            assertEquals(-10, opts.gainsTenths.first())
        }

        @Test fun `count mismatch fails safe instead of guessing`() {
            // Device says 20 steps but the known R820T table has 29 — applying a
            // value from the wrong table would set an unintended gain.
            val opts = RtlTcpGain.gainsFor(DongleInfo(TunerType.R820T, 20))
            assertTrue(opts is GainOptions.Unavailable)
            val reason = (opts as GainOptions.Unavailable).reason
            assertTrue(reason.contains("20") && reason.contains("29"), "Reason should state both counts: $reason")
        }

        @Test fun `tuner with no gain control reports a specific reason`() {
            val opts = RtlTcpGain.gainsFor(DongleInfo(TunerType.FC2580, 1))
            assertTrue(opts is GainOptions.Unavailable)
            assertTrue((opts as GainOptions.Unavailable).reason.contains("FC2580"))
        }

        @Test fun `unknown tuner reports a specific reason`() {
            val opts = RtlTcpGain.gainsFor(DongleInfo(TunerType.UNKNOWN, 1))
            assertTrue(opts is GainOptions.Unavailable)
            assertTrue((opts as GainOptions.Unavailable).reason.isNotBlank())
        }
    }

    @Nested inner class Commands {

        @Test fun `gain mode command is 5 bytes big-endian`() {
            val cmd = RtlTcpGain.command(RtlTcpGain.CMD_SET_GAIN_MODE, 1)
            assertArrayEquals(byteArrayOf(0x03, 0, 0, 0, 1), cmd)
        }

        @Test fun `gain value command encodes tenths big-endian`() {
            val cmd = RtlTcpGain.command(RtlTcpGain.CMD_SET_GAIN, 496)
            assertArrayEquals(byteArrayOf(0x04, 0, 0, 0x01, 0xF0.toByte()), cmd)
        }

        @Test fun `bias tee command is 0x0e with a 0 or 1 parameter`() {
            assertArrayEquals(byteArrayOf(0x0e, 0, 0, 0, 1), RtlTcpGain.command(RtlTcpGain.CMD_SET_BIAS_TEE, 1))
            assertArrayEquals(byteArrayOf(0x0e, 0, 0, 0, 0), RtlTcpGain.command(RtlTcpGain.CMD_SET_BIAS_TEE, 0))
        }
    }

    @Nested inner class Formatting {

        @Test fun `formats tenths with units`() {
            assertEquals("49.6 dB", RtlTcpGain.formatGain(496))
            assertEquals("0.0 dB", RtlTcpGain.formatGain(0))
        }

        @Test fun `negative gains keep a single minus sign`() {
            assertEquals("-1.0 dB", RtlTcpGain.formatGain(-10))
        }
    }
}
