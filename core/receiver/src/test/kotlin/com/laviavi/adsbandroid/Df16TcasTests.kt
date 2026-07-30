package com.laviavi.adsbandroid

import com.laviavi.adsbandroid.aircraft.AircraftManager
import com.laviavi.adsbandroid.crc.CrcChecker
import com.laviavi.adsbandroid.decoder.DecodedMessage
import com.laviavi.adsbandroid.decoder.MessageDecoder
import com.laviavi.adsbandroid.decoder.RawFrame
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * DF16 (TCAS long air-air surveillance) and the DF16-only `_resolve_ap_icao`
 * intruder heuristic — previously unported entirely: `LongAirAir` carried no
 * fields at all, and DF0 never set its vertical-status bit either. Both are
 * ports of `decode_tcas_df16` / `_decode_ra_mv` / `_resolve_ap_icao` in the
 * Python reference.
 */
class Df16TcasTests {

    private val ICAO = 0x4840D6

    /** Builds a 14-byte DF16 frame with the given SL, altitude AC field, and MV bytes. */
    private fun df16(sl: Int, ac: Int, mv: IntArray, ap: Int = 0): IntArray {
        val bytes = IntArray(14)
        bytes[0] = (16 shl 3) or (sl and 0x07)
        bytes[2] = (ac ushr 8) and 0x1F
        bytes[3] = ac and 0xFF
        for (i in 0 until 7) bytes[4 + i] = mv.getOrElse(i) { 0 }
        bytes[11] = (ap ushr 16) and 0xFF
        bytes[12] = (ap ushr 8) and 0xFF
        bytes[13] = ap and 0xFF
        return bytes
    }

    private fun decode(bytes: IntArray, decoder: MessageDecoder = MessageDecoder()): DecodedMessage.LongAirAir {
        val checked = CrcChecker.CheckedFrame(RawFrame(bytes), CrcChecker.CrcResult.VALID, 0, recoveredIcao = ICAO)
        return decoder.decode(checked) as DecodedMessage.LongAirAir
    }

    @Nested inner class NonRaTraffic {

        @Test fun `SL and altitude decode with no RA present`() {
            // mv[0] != 0x30: not a BDS 3,0 RA report at all.
            val msg = decode(df16(sl = 5, ac = 0x0C10, mv = intArrayOf(0x00, 0, 0, 0, 0, 0, 0)))
            assertEquals(5, msg.tcasSl)
            assertNotNull(msg.altitudeFt)
            assertFalse(msg.tcasRaActive)
            assertNull(msg.tcasRaText)
            assertFalse(msg.tcasRaTerminated)
        }
    }

    @Nested inner class ResolutionAdvisory {

        @Test fun `an active climb advisory decodes text and active=true`() {
            // ARA bit 0 ("Climb", MSB of the 14-bit field): araRaw = 1<<13.
            // araRaw = ((mv2<<8)|mv3) >>> 2, so word = 0x2000 << 2 = 0x8000.
            val msg = decode(df16(sl = 0, ac = 0, mv = intArrayOf(0x30, 0x35, 0x80, 0x00, 0, 0, 0)))
            assertTrue(msg.tcasRaActive)
            assertEquals("Climb", msg.tcasRaText)
            assertNull(msg.tcasRaComplement)
            assertFalse(msg.tcasRaTerminated)
        }

        @Test fun `RAC alone (no ARA bits) is a complement without an active advisory`() {
            // mv3=0x02 verified against the Python reference directly: ARA bits
            // and RAC bits both derive from overlapping bits of the same 16-bit
            // (mv2,mv3) word, so which raw byte values isolate one field from the
            // other is not obvious by hand — confirmed empirically rather than
            // hand-derived from the bit-position tables.
            val msg = decode(df16(sl = 0, ac = 0, mv = intArrayOf(0x30, 0x35, 0x00, 0x02, 0, 0, 0)))
            assertFalse(msg.tcasRaActive, "RAC alone does not itself constitute an active advisory")
            assertEquals("Turn left", msg.tcasRaComplement)
            assertNull(msg.tcasRaText)
        }

        @Test fun `terminated bit clears active even with ARA bits set`() {
            // Same "Climb" word as above, but with RAT (bit 0 of mv3) set too.
            val msg = decode(df16(sl = 0, ac = 0, mv = intArrayOf(0x30, 0x35, 0x80, 0x01, 0, 0, 0)))
            assertFalse(msg.tcasRaActive)
            assertTrue(msg.tcasRaTerminated)
            // Text still reflects what the advisory was — the reference decodes
            // it unconditionally, termination is a separate flag.
            assertEquals("Climb", msg.tcasRaText)
        }

        @Test fun `multiple ARA bits join with a comma, matching the reference's separator`() {
            // Bits 0 ("Climb") and 1 ("Don't climb") both set: araRaw = (1<<13)|(1<<12) = 0x3000.
            // word = 0x3000 << 2 = 0xC000 -> mv2=0xC0, mv3=0x00.
            val msg = decode(df16(sl = 0, ac = 0, mv = intArrayOf(0x30, 0x35, 0xC0, 0x00, 0, 0, 0)))
            assertEquals("Climb, Don't climb", msg.tcasRaText)
        }
    }

    @Nested inner class IntruderResolution {

        @Test fun `no target when no aircraft are tracked yet`() {
            val decoder = MessageDecoder() // knownIcaos defaults to empty
            val msg = decode(df16(sl = 0, ac = 0, mv = intArrayOf(0), ap = 0x123456), decoder)
            assertNull(msg.tcasTargetIcao)
        }

        @Test fun `AP of zero never resolves, even with candidates tracked`() {
            val decoder = MessageDecoder().apply { knownIcaos = setOf(0x111111, 0x222222) }
            val msg = decode(df16(sl = 0, ac = 0, mv = intArrayOf(0), ap = 0), decoder)
            assertNull(msg.tcasTargetIcao)
        }

        /**
         * `_resolve_ap_icao` is dead code in the reference, confirmed empirically
         * (see `resolveApIcao`'s doc comment: 0 hits in 200,000 randomised Python
         * trials). XOR is its own inverse, so whenever `ap == a xor b` for two
         * tracked ICAOs, both directions match simultaneously — the match count
         * is always even, never the odd-one-out `singleOrNull()` needs. This is
         * ported faithfully rather than "fixed," so the port must reproduce the
         * same permanent null, not a plausible-looking match that the reference
         * itself can never actually produce.
         */
        @Test fun `a constructed XOR pair always double-matches, never resolves`() {
            val a = 0x111111
            val b = 0x222222
            val ap = a xor b
            val decoder = MessageDecoder().apply { knownIcaos = setOf(a, b) }
            val msg = decode(df16(sl = 0, ac = 0, mv = intArrayOf(0), ap = ap), decoder)
            assertNull(msg.tcasTargetIcao, "both directions of a genuine XOR pair always match — never exactly one")
        }

        @Test fun `no random combination of tracked ICAOs ever resolves a target`() {
            val random = java.util.Random(1)
            repeat(2_000) {
                val icaos = (0 until (2 + random.nextInt(5)))
                    .map { 1 + random.nextInt(0xFFFFFE) }.toSet()
                if (icaos.size < 2) return@repeat
                val pair = icaos.toList()
                val ap = pair[0] xor pair[1]
                if (ap == 0) return@repeat
                val decoder = MessageDecoder().apply { knownIcaos = icaos }
                val msg = decode(df16(sl = 0, ac = 0, mv = intArrayOf(0), ap = ap), decoder)
                assertNull(msg.tcasTargetIcao, "icaos=$icaos ap=%06X".format(ap))
            }
        }
    }

    @Nested inner class Df0OnGround {

        @Test fun `DF0 vertical-status bit sets onGround`() {
            // DF0 byte0: DF(5 bits)=0, then VS at bit position 2 (from LSB) per
            // decodeDF0 -> (bytes[0] ushr 2) and 0x01.
            val airborne = IntArray(7).also { it[0] = 0x00 }
            val ground   = IntArray(7).also { it[0] = 0x04 } // VS bit set

            val decoder = MessageDecoder()
            val airborneMsg = decoder.decode(
                CrcChecker.CheckedFrame(RawFrame(airborne), CrcChecker.CrcResult.VALID, 0, recoveredIcao = ICAO),
            ) as DecodedMessage.AltitudeReply
            val groundMsg = decoder.decode(
                CrcChecker.CheckedFrame(RawFrame(ground), CrcChecker.CrcResult.VALID, 0, recoveredIcao = ICAO),
            ) as DecodedMessage.AltitudeReply

            assertEquals(false, airborneMsg.onGround)
            assertEquals(true, groundMsg.onGround)
        }

        @Test fun `DF4 reads flight status and sets onGround`() {
            // fs = (byte0 >> 2) & 7. byte0 = 4<<3 = 0x20, fs = (0x20>>2)&7 = 0 → airborne.
            val df4Airborne = IntArray(7).also { it[0] = (4 shl 3) }
            val msg = MessageDecoder().decode(
                CrcChecker.CheckedFrame(RawFrame(df4Airborne), CrcChecker.CrcResult.VALID, 0, recoveredIcao = ICAO),
            ) as DecodedMessage.AltitudeReply
            assertEquals(false, msg.onGround)

            // fs=1 → on ground. byte0 = (4<<3) or (1<<2) = 0x24
            val df4Ground = IntArray(7).also { it[0] = (4 shl 3) or (1 shl 2) }
            val msg2 = MessageDecoder().decode(
                CrcChecker.CheckedFrame(RawFrame(df4Ground), CrcChecker.CrcResult.VALID, 0, recoveredIcao = ICAO),
            ) as DecodedMessage.AltitudeReply
            assertEquals(true, msg2.onGround)
        }
    }

    @Nested inner class ManagerMerge {

        private fun decoded(bytes: IntArray, decoder: MessageDecoder): DecodedMessage =
            decoder.decode(CrcChecker.CheckedFrame(RawFrame(bytes), CrcChecker.CrcResult.VALID, 0, recoveredIcao = ICAO))!!

        @Test fun `event count increments once per rising edge, not per frame`() {
            val decoder = MessageDecoder()
            val mgr = AircraftManager()
            val climbing = df16(0, 0, intArrayOf(0x30, 0x35, 0x80, 0x00, 0, 0, 0))

            mgr.update(decoded(climbing, decoder))
            mgr.update(decoded(climbing, decoder)) // same RA still active, second frame
            mgr.update(decoded(climbing, decoder))

            val state = mgr.aircraft.single()
            assertEquals(1, state.tcasEventCount, "three frames of the SAME advisory is one event")
            assertTrue(state.tcasRaActive)
        }

        @Test fun `a new RA after termination counts as a second event`() {
            val decoder = MessageDecoder()
            val mgr = AircraftManager()
            val climbing   = df16(0, 0, intArrayOf(0x30, 0x35, 0x80, 0x00, 0, 0, 0))
            val terminated = df16(0, 0, intArrayOf(0x30, 0x35, 0x80, 0x01, 0, 0, 0))

            mgr.update(decoded(climbing, decoder))
            mgr.update(decoded(terminated, decoder))
            mgr.update(decoded(climbing, decoder))

            val state = mgr.aircraft.single()
            assertEquals(2, state.tcasEventCount)
        }

        @Test fun `DF4 flight-status ground flag overwrites prior state`() {
            val decoder = MessageDecoder()
            val mgr = AircraftManager()
            // DF0 sets onGround=true (VS bit)
            val df0Ground = IntArray(7).also { it[0] = 0x04 }
            // DF4 with fs=0 → airborne, overwrites the DF0 ground flag
            val df4 = IntArray(7).also { it[0] = (4 shl 3) }

            mgr.update(decoded(df0Ground, decoder))
            assertTrue(mgr.aircraft.single().onGround)

            mgr.update(decoded(df4, decoder))
            assertFalse(mgr.aircraft.single().onGround)
        }
    }
}
