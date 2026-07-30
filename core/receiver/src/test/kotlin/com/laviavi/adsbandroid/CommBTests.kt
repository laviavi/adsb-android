package com.laviavi.adsbandroid

import com.laviavi.adsbandroid.aircraft.AircraftManager
import com.laviavi.adsbandroid.crc.CrcChecker
import com.laviavi.adsbandroid.decoder.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** Comm-B (DF20/21 BDS register) decoder tests. */
class CommBTests {

    private fun valid(dec: MessageDecoder, bytes: IntArray): DecodedMessage? =
        dec.decode(CrcChecker.CheckedFrame(RawFrame(bytes.copyOf()), CrcChecker.CrcResult.VALID, 0))

    // DF20's ICAO is read from its PA field and only resolves if previously confirmed
    // via a DF17/18/11 frame (see MessageDecoder.extractIcao) — seed it first.
    private val SEED_DF17 = intArrayOf(0x8D, 0xAB, 0xCD, 0xEF, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
    private fun primeIcao(dec: MessageDecoder) { valid(dec, SEED_DF17) }

    // Packs (value, bitWidth) pairs MSB-first into a 7-byte (56-bit) MB field.
    // Two's-complement negative values are truncated to their field width correctly.
    private fun packBits(vararg fields: Pair<Int, Int>): IntArray {
        var bits = 0L
        var totalWidth = 0
        for ((value, width) in fields) {
            bits = (bits shl width) or (value.toLong() and ((1L shl width) - 1))
            totalWidth += width
        }
        require(totalWidth == 56) { "MB field must total 56 bits, got $totalWidth" }
        return IntArray(7) { i -> ((bits ushr (48 - i * 8)) and 0xFF).toInt() }
    }

    // Build a DF20 frame: bytes[0]=DF/FS, bytes[2..3]=AC13, bytes[4..10]=MB field
    private fun df20Frame(mb: IntArray): IntArray {
        require(mb.size == 7)
        val b = IntArray(14)
        b[0] = (20 shl 3)
        b[1] = 0x00; b[2] = 0x18; b[3] = 0x00 // AC13: 3000ft roughly, Q-bit set
        for (i in 0 until 7) b[4 + i] = mb[i]
        b[11] = 0xAB; b[12] = 0xCD; b[13] = 0xEF // PI field's PA = ICAO ABCDEF (seeded)
        return b
    }

    @Nested inner class Bds50Tests {

        // Roll ~2.46deg (raw=14), track 90deg (raw=512), GS=400kt (raw=200),
        // turn rate 2.0deg/s (raw=64), TAS=450kt (raw=225) — all five fields active,
        // as a real GICB BDS 5,0 report typically populates.
        private fun mb50(): IntArray = packBits(
            1 to 1, 14 to 10,
            1 to 1, 512 to 11,
            1 to 1, 200 to 10,
            1 to 1, 64 to 10,
            1 to 1, 225 to 10,
        )

        @Test fun `decodes BDS 5,0 roll track and ground speed`() {
            val dec = MessageDecoder()
            primeIcao(dec)
            val msg = valid(dec, df20Frame(mb50())) as? DecodedMessage.AltitudeReply
            assertNotNull(msg)
            val commB = msg!!.commB
            assertNotNull(commB, "Expected BDS 5,0 to be detected")
            assertEquals("5,0", commB!!.bdsCode)
            assertEquals(400, commB.groundSpeedKt)
            assertNotNull(commB.trueTrackDeg)
            assertEquals(90.0, commB.trueTrackDeg!!, 1.0)
            assertNotNull(commB.rollAngleDeg)
            assertEquals(450, commB.trueAirspeedKt)
            assertNotNull(commB.trackAngleRateDegPerSec)
            assertEquals(2.0, commB.trackAngleRateDegPerSec!!, 0.1)
        }

        @Test fun `merges into AircraftState non-destructively`() {
            val dec = MessageDecoder()
            primeIcao(dec)
            val mgr = AircraftManager()
            val msg = valid(dec, df20Frame(mb50()))!!
            val state = mgr.update(msg)
            assertEquals(400, state.groundSpeedKt)
            assertEquals("5,0", state.lastBdsCode)
        }
    }

    @Nested inner class Bds60Tests {

        // Heading 180deg (raw=1024), IAS=250kt (raw=250), Mach off, baro rate off,
        // inertial vertical rate -1024ft/min (raw=-32).
        private fun mb60(): IntArray = packBits(
            1 to 1, 1024 to 11,
            1 to 1, 250 to 10,
            0 to 1, 0 to 10,
            0 to 1, 0 to 10,
            1 to 1, (-32) to 10,
        )

        @Test fun `decodes BDS 6,0 heading IAS and vertical rate`() {
            val dec = MessageDecoder()
            primeIcao(dec)
            val msg = valid(dec, df20Frame(mb60())) as? DecodedMessage.AltitudeReply
            assertNotNull(msg)
            val commB = msg!!.commB
            assertNotNull(commB, "Expected BDS 6,0 to be detected")
            assertEquals("6,0", commB!!.bdsCode)
            assertEquals(250, commB.indicatedAirspeedKt)
            assertNotNull(commB.magneticHeadingDeg)
            assertEquals(180.0, commB.magneticHeadingDeg!!, 1.0)
            assertEquals(-1024, commB.verticalRateFpm)
        }
    }

    @Nested inner class Bds40Tests {

        // MCP selected altitude = 35008ft (raw = 2188, *16 = 35008)
        private fun mb40(): IntArray = packBits(
            1 to 1, 2188 to 12,
            0 to 1, 0 to 12,
            0 to 1, 0 to 12,
            0 to 8, // reserved
            0 to 9, // mode bits / tail — not decoded
        )

        @Test fun `decodes BDS 4,0 selected altitude`() {
            val dec = MessageDecoder()
            primeIcao(dec)
            val msg = valid(dec, df20Frame(mb40())) as? DecodedMessage.AltitudeReply
            assertNotNull(msg)
            val commB = msg!!.commB
            assertNotNull(commB, "Expected BDS 4,0 to be detected")
            assertEquals("4,0", commB!!.bdsCode)
            assertEquals(35008, commB.selectedAltitudeFt)
        }
    }

    @Nested inner class NegativeTests {

        @Test fun `all-zero MB field yields no BDS match`() {
            val commB = CommBDecoder.decode(IntArray(7))
            assertNull(commB)
        }

        @Test fun `BDS20 callsign frame is not mis-tagged as a Comm-B register`() {
            // f.bytes[4]==0x20 marks BDS 2,0 (callsign) — decodeCommB should not run for it.
            val dec = MessageDecoder()
            primeIcao(dec)
            val b = IntArray(14)
            b[0] = (20 shl 3); b[2] = 0x18; b[3] = 0x00
            b[4] = 0x20 // BDS 2,0 marker
            b[5] = 0x00; b[6] = 0x00; b[7] = 0x00; b[8] = 0x00; b[9] = 0x00; b[10] = 0x00
            b[11] = 0xAB; b[12] = 0xCD; b[13] = 0xEF
            val msg = valid(dec, b) as? DecodedMessage.AltitudeReply
            assertNotNull(msg)
            assertNull(msg!!.commB, "BDS 2,0 callsign frames should not also produce a commB match")
        }
    }
}
