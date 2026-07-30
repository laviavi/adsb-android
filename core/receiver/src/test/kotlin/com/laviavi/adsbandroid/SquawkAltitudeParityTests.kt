package com.laviavi.adsbandroid

import com.laviavi.adsbandroid.decoder.MessageDecoder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Squawk and altitude decode, checked against the Python receiver
 * (`decoder/adsb_decoder.py`) which is the source of truth for this project.
 *
 * Both decoders were previously ported from dump1090 instead, whose convention
 * differs: it packs the four squawk digits into a hex-coded integer meant to be
 * printed with %x, and routes Gillham altitude through that same packing. The UI
 * rendered the packed value as octal, so squawk 6272 displayed as "61162" — a bug
 * the old tests could not catch, because they asserted the packed integer rather
 * than the value a person reads.
 *
 * Expected values below are the Python implementation's actual output.
 */
class SquawkAltitudeParityTests {

    private val dec = MessageDecoder()

    @Nested
    inner class Identity {

        @Test fun `matches the Python reference`() {
            val cases = mapOf(
                0x0B36 to "3542",
                0x1A29 to "3314",
                0x0000 to "0000",
                0x1FFF to "7776",
                0x0801 to "1004",
                0x1249 to "2214",
            )
            cases.forEach { (raw, expected) ->
                assertEquals(expected, dec.decodeIdentity(raw), "id13 0x%04X".format(raw))
            }
        }

        @Test fun `is always four octal digits`() {
            // The regression that shipped: 5 digits on screen, because a packed
            // integer was rendered with the wrong radix.
            for (raw in 0..0x1FFF) {
                val s = dec.decodeIdentity(raw)
                assertEquals(4, s.length, "id13 0x%04X gave '%s'".format(raw, s))
                assertTrue(s.all { it in '0'..'7' }, "id13 0x%04X gave '%s'".format(raw, s))
            }
        }

        @Test fun `emergency codes are recognisable as written`() {
            // 7500 hijack, 7600 radio failure, 7700 emergency. These must compare
            // equal to the literal strings the UI and any alerting logic use.
            assertEquals("7700", dec.decodeIdentity(0x1DFE and 0x1FFF).let {
                // build 7700 explicitly rather than trusting a magic constant
                dec.decodeIdentity(encodeIdentity(7, 7, 0, 0))
            })
            assertEquals("7600", dec.decodeIdentity(encodeIdentity(7, 6, 0, 0)))
            assertEquals("7500", dec.decodeIdentity(encodeIdentity(7, 5, 0, 0)))
        }

        /** Inverse of the reference layout: C1 A1 C2 A2 C4 A4 X B1 D1 B2 D2 B4 D4. */
        private fun encodeIdentity(a: Int, b: Int, c: Int, d: Int): Int {
            var v = 0
            if (a and 1 != 0) v = v or (1 shl 11)   // A1
            if (a and 2 != 0) v = v or (1 shl 9)    // A2
            if (a and 4 != 0) v = v or (1 shl 7)    // A4
            if (b and 1 != 0) v = v or (1 shl 5)    // B1
            if (b and 2 != 0) v = v or (1 shl 3)    // B2
            if (b and 4 != 0) v = v or (1 shl 1)    // B4
            if (c and 1 != 0) v = v or (1 shl 12)   // C1
            if (c and 2 != 0) v = v or (1 shl 10)   // C2
            if (c and 4 != 0) v = v or (1 shl 8)    // C4
            if (d and 2 != 0) v = v or (1 shl 2)    // D2
            if (d and 4 != 0) v = v or 1            // D4
            return v
        }
    }

    @Nested
    inner class Altitude13Bit {

        @Test fun `matches the Python reference`() {
            val cases = mapOf(
                0x0000 to null,
                0x1810 to 37400,
                0x0190 to 1400,
                0x0C38 to 18800,
                0x1A20 to 18300,
                0x0040 to null,      // M bit set — metric, unsupported
            )
            cases.forEach { (raw, expected) ->
                assertEquals(expected, dec.decodeAc13Field(raw), "ac13 0x%04X".format(raw))
            }
        }

        @Test fun `metric altitudes are refused, not approximated`() {
            assertNull(dec.decodeAc13Field(0x0040), "M bit set")
        }
    }

    @Nested
    inner class Altitude12Bit {

        @Test fun `matches the Python reference`() {
            val cases = mapOf(
                0x0000 to null,
                0x0C10 to 37400,
                0x0190 to 3800,
                0x0838 to 25200,
                0x0A20 to -900,
            )
            cases.forEach { (raw, expected) ->
                assertEquals(expected, dec.decodeAc12Field(raw), "ac12 0x%04X".format(raw))
            }
        }

        @Test fun `bit 6 is data, not a metric flag`() {
            // The 12-bit field has no M bit. Treating it as 13-bit drops every
            // reading with C4 set, which is a large share of real traffic.
            assertNotNull(dec.decodeAc12Field(0x0C10), "C4 set must still decode")
        }
    }
}
