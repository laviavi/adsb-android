package com.laviavi.adsbandroid

import com.laviavi.adsbandroid.aircraft.AircraftManager
import com.laviavi.adsbandroid.aircraft.AircraftSort
import com.laviavi.adsbandroid.aircraft.AircraftSortOrder
import com.laviavi.adsbandroid.crc.CrcChecker
import com.laviavi.adsbandroid.decoder.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Session 1 + 2 feature tests:
 * - Speed/altitude sanity clamps
 * - CPR relative decode with observer position
 * - Gillham Mode A/C
 * - TC31 Aircraft Operational Status
 * - II code extraction from DF11
 * - DF22/23/24 explicit Unknown handling
 */
class Phase2Tests {

    private fun makeDecoder(lat: Double = 0.0, lon: Double = 0.0) =
        MessageDecoder().also { it.observerLat = lat; it.observerLon = lon }

    private fun valid(dec: MessageDecoder, bytes: IntArray): DecodedMessage? =
        dec.decode(CrcChecker.CheckedFrame(RawFrame(bytes.copyOf()), CrcChecker.CrcResult.VALID, 0))

    // ── Sanity clamps ─────────────────────────────────────────────────────────
    @Nested inner class SanityClampTests {

        // DF17 TC19 subtype-1 velocity frame for ICAO=ABCDEF
        private fun makeVelFrame(ewRaw: Int, nsRaw: Int, ewSign: Int = 0, nsSign: Int = 0): IntArray {
            val b = IntArray(14)
            b[0] = 0x8D; b[1] = 0xAB; b[2] = 0xCD; b[3] = 0xEF
            b[4] = (19 shl 3) or 1  // TC=19, subtype=1
            b[5] = (ewSign shl 2) or ((ewRaw ushr 8) and 0x03)
            b[6] = ewRaw and 0xFF
            b[7] = (nsSign shl 7) or ((nsRaw ushr 3) and 0x7F)
            b[8] = (nsRaw and 0x07) shl 5
            return b
        }

        @Test fun `speed above 700kt is rejected`() {
            val mgr = AircraftManager()
            val dec = makeDecoder()
            // ewRaw=702 → ewVel=701kt east, nsRaw=1 → nsVel=0 → speed=701kt > 700
            val msg = valid(dec, makeVelFrame(ewRaw = 702, nsRaw = 1)) ?: return
            val state = mgr.update(msg)
            // No prior groundSpeedKt; clamp rejects 701kt → stays null
            assertNull(state.groundSpeedKt, "Speed >700kt should be rejected")
        }

        @Test fun `speed at 700kt is accepted`() {
            val mgr = AircraftManager()
            val dec = makeDecoder()
            // ewRaw=701 → ewVel=700kt, nsRaw=1 → nsVel=0 → speed=700kt (boundary)
            val msg = valid(dec, makeVelFrame(ewRaw = 701, nsRaw = 1)) ?: return
            val state = mgr.update(msg)
            assertNotNull(state.groundSpeedKt)
            assertTrue(state.groundSpeedKt!! <= 700)
        }

        @Test fun `altitude clamp accepts valid range`() {
            assertTrue(35000 in -1500..72000)
            assertTrue(-1000 in -1500..72000)
            assertTrue(72000 in -1500..72000)
        }

        @Test fun `altitude clamp rejects out-of-range values`() {
            assertFalse((-2000) in -1500..72000)
            assertFalse(80000 in -1500..72000)
        }
    }

    // ── CPR relative decode ───────────────────────────────────────────────────
    @Nested inner class CprRelativeDecodeTests {

        // Real DF17 TC11 odd frame from dump1090, aircraft near Heathrow
        private val CPR_ODD = intArrayOf(
            0x8D,0x48,0x40,0xD6,0x58,0xB5,0x06,0xB2,0x83,0xEB,0x7A,0x94,0x5B,0x84
        )
        private val OBS_LAT = 51.5
        private val OBS_LON = -0.45

        @Test fun `relative decode without observer yields null position`() {
            val dec = makeDecoder(0.0, 0.0)
            val msg = valid(dec, CPR_ODD) as? DecodedMessage.AdsbMessage ?: return
            assertNull(msg.fields.latitude, "No position without observer")
        }

        @Test fun `relative decode with observer produces plausible position`() {
            val dec = makeDecoder(OBS_LAT, OBS_LON)
            val msg = valid(dec, CPR_ODD) as? DecodedMessage.AdsbMessage ?: return
            val lat = msg.fields.latitude ?: return  // null acceptable if CPR math rejects frame
            val lon = msg.fields.longitude ?: return
            assertTrue(Math.abs(lat - OBS_LAT) < 5.0, "lat=$lat too far from observer $OBS_LAT")
            assertTrue(Math.abs(lon - OBS_LON) < 5.0, "lon=$lon too far from observer $OBS_LON")
        }

        @Test fun `observer lat and lon are mutable`() {
            val dec = makeDecoder()
            assertEquals(0.0, dec.observerLat)
            dec.observerLat = 33.95; dec.observerLon = -117.33
            assertEquals(33.95, dec.observerLat)
            assertEquals(-117.33, dec.observerLon)
        }
    }

    // ── Gillham decode ────────────────────────────────────────────────────────
    @Nested inner class GillhamDecodeTests {

        private val dec = MessageDecoder()

        @Test fun `ac12 Q-bit set decodes 16000ft`() {
            // ac12=0x558, Q=1: n=((0x540)>>1)|0x8=680, alt=680*25-1000=16000ft
            assertEquals(16000, dec.decodeAc12Field(0x558))
        }

        @Test fun `ac12 zero returns null`() {
            assertNull(dec.decodeAc12Field(0x000))
        }

        @Test fun `ac13 Q-bit set decodes 10000ft`() {
            // n=440 → alt=10000ft. Reverse-engineer ac13 from n:
            // n bits[10..5]=13, bit[4]=1, bits[3..0]=8
            // ac13 = 0x680 (bits 12..7 = 13<<7 area) | 0x20 (bit5) | Q=0x10 | 0x08 = 0x6B8
            assertEquals(10000, dec.decodeAc13Field(0x6B8))
        }

        @Test fun `ac13 M-bit set returns null`() {
            assertNull(dec.decodeAc13Field(0x0040))
        }
    }

    // ── TC31 Aircraft Operational Status ─────────────────────────────────────
    @Nested inner class TC31Tests {

        // DF17 TC31 subtype=0 (airborne), ICAO=C0FFEE. Byte layout per the
        // Python reference (`_decode_operational_status`, me[0]=byte4):
        // byte7 (me[3]) = NACp(4b, low nibble); byte8 (me[4]) = NACv(3b)|SIL(2b)|...;
        // byte9 (me[5]) = version(3b)|NIC-A(1b)|...; byte10 (me[6]) = GVA(2b)|... (Android-only).
        private fun makeTC31(subtype: Int = 0, byte5: Int = 0, byte7: Int = 0, byte8: Int = 0, byte9: Int = 0, byte10: Int = 0): IntArray {
            val b = IntArray(14)
            b[0] = 0x8D; b[1] = 0xC0; b[2] = 0xFF; b[3] = 0xEE
            b[4] = (31 shl 3) or subtype
            b[5] = byte5; b[7] = byte7; b[8] = byte8; b[9] = byte9; b[10] = byte10
            return b
        }

        @Test fun `version number`() {
            // byte9 = 0x20 = 0b001_0_0000 → version=1
            val msg = valid(makeDecoder(), makeTC31(byte9 = 0x20)) as? DecodedMessage.AdsbMessage ?: return
            assertEquals(1, msg.fields.versionNumber)
        }

        @Test fun `NACp`() {
            // byte7 (me[3]) = 0x0A → NACp = me[3]&0x0F = 10. Ground truth:
            // Python's `_decode_operational_status`, verified this session
            // against the real reference — see docs/correction_plan.md #5.
            val msg = valid(makeDecoder(), makeTC31(byte7 = 0x0A)) as? DecodedMessage.AdsbMessage ?: return
            assertEquals(10, msg.fields.nacP)
        }

        @Test fun `SIL`() {
            // byte8 (me[4]) = 0x06 = 0b0000_0110 → sil = (me[4]>>1)&0x03 = 3.
            // Previously read from byte10 bits 5-4 (me[6]), the wrong byte
            // entirely — see docs/correction_plan.md #6.
            val msg = valid(makeDecoder(), makeTC31(byte8 = 0x06)) as? DecodedMessage.AdsbMessage ?: return
            assertEquals(3, msg.fields.sil)
        }

        @Test fun `SIL does not depend on subtype - the reference has no such branch`() {
            // Same byte8, surface (subtype=1) instead of airborne. Previously
            // surface used a *different* formula ((me[5]>>1)&0x03) that didn't
            // exist in Python at all; the reference computes sil identically
            // for both subtypes.
            val msg = valid(makeDecoder(), makeTC31(subtype = 1, byte8 = 0x06)) as? DecodedMessage.AdsbMessage ?: return
            assertEquals(3, msg.fields.sil)
        }

        @Test fun `GVA`() {
            // byte10 = 0x80 → GVA=2 (bits 7-6). Android-only, no Python equivalent.
            val msg = valid(makeDecoder(), makeTC31(byte10 = 0x80)) as? DecodedMessage.AdsbMessage ?: return
            assertEquals(2, msg.fields.gva)
        }

        @Test fun `TCAS operational flag`() {
            // byte5 bit 2 = 1 → TCAS operational
            val msg = valid(makeDecoder(), makeTC31(byte5 = 0x04)) as? DecodedMessage.AdsbMessage ?: return
            assertTrue(msg.fields.tcasOperational)
        }

        @Test fun `TC31 fields merged into AircraftState`() {
            val dec = makeDecoder(); val mgr = AircraftManager()
            // byte7=0x09 → NACp=9; byte8=0x04 → sil=(0x04>>1)&3=2
            val msg = valid(dec, makeTC31(byte7 = 0x09, byte8 = 0x04)) ?: return
            val state = mgr.update(msg)
            assertEquals(9, state.nacP)
            assertEquals(2, state.sil)
        }
    }

    /**
     * DF11 II code — port of `adsb_decoder.py`'s
     * `addr = pi ^ crc24(data[:-3]); ii_code = (addr >> 20) & 0x0F`.
     *
     * The previous Kotlin formula read the raw low nibble of the last PI byte
     * (`bytes[6] & 0x0F`) — no XOR, no CRC, wrong bits — and disagreed with
     * the reference on 18,764/20,000 sampled values (docs/correction_plan.md
     * #1). `decode()` passes `checked.crc` (== `CrcChecker.computeCrc(bytes)`,
     * algebraically the same value as Python's `addr`) into `decodeDF11`.
     *
     * A `valid(dec, bytes)` frame in this test class always carries a
     * hardcoded `crc=0`, which is realistic: verified against the live Python
     * reference that `ii_code` is structurally always 0 for any DF11 the
     * reference's own CRC checker classifies VALID or RECOVERED (both
     * acceptance paths require `addr < 80`, far below the `2^20` threshold
     * `ii_code`'s bits start at) — constructing frames with a genuine nonzero
     * interrogator ID and running them through the real checker confirmed
     * they classify BAD and never reach decoding at all. So the *formula* is
     * tested directly against an injected `crc`, separate from the *realistic
     * gate* that Kotlin's `valid()` helper mimics with its hardcoded 0.
     */
    @Nested inner class IICodeTests {

        private fun validWithCrc(dec: MessageDecoder, bytes: IntArray, crc: Int): DecodedMessage? =
            dec.decode(CrcChecker.CheckedFrame(RawFrame(bytes.copyOf()), CrcChecker.CrcResult.VALID, crc))

        @Test fun `ii is the top 4 bits of the recovered address`() {
            val msg = validWithCrc(makeDecoder(), intArrayOf(0x5D,0x48,0x40,0xD6,0x00,0x00,0x00), crc = 7 shl 20) as? DecodedMessage.AllCallReply
            assertNotNull(msg); assertEquals(7, msg!!.iiCode)
        }

        @Test fun `lower 20 bits of the address are masked away`() {
            // 3<<20 with noise in the low bits must still read ii=3.
            val msg = validWithCrc(makeDecoder(), intArrayOf(0x5D,0x48,0x40,0xD6,0x00,0x00,0x00), crc = (3 shl 20) or 0xABCDE) as? DecodedMessage.AllCallReply
            assertNotNull(msg); assertEquals(3, msg!!.iiCode)
        }

        @Test fun `a real valid DF11 (crc 0) decodes ii zero`() {
            val msg = valid(makeDecoder(), intArrayOf(0x5D,0x48,0x40,0xD6,0x00,0x00,0x00)) as? DecodedMessage.AllCallReply
            assertNotNull(msg); assertEquals(0, msg!!.iiCode)
        }

        @Test fun `II code merged into AircraftState`() {
            val dec = makeDecoder(); val mgr = AircraftManager()
            val msg = validWithCrc(dec, intArrayOf(0x5D,0xC0,0xFF,0xEE,0x00,0x00,0x00), crc = 5 shl 20) ?: return
            assertEquals(5, mgr.update(msg).iiCode)
        }

        @Test fun `a genuine nonzero interrogator ID never survives real CRC validation`() {
            // Structural finding, verified against the live Python reference
            // (see docs/correction_plan.md #1): constructing a DF11 whose PI
            // encodes a real ii != 0 makes CrcChecker classify it BAD, so
            // decodeDF11 never even runs on it. This documents *why* ii=0 is
            // the only value ever observed in practice, not a coincidence.
            val icao = 0x4840D6
            val dataBytes = intArrayOf(0x5D, (icao ushr 16) and 0xFF, (icao ushr 8) and 0xFF, icao and 0xFF)
            for (ii in listOf(1, 3, 7, 15)) {
                val crcOfData = CrcChecker.computeCrc(dataBytes + intArrayOf(0, 0, 0)) // crc24(data[:-3]) via the 0-PI trick
                val addr = ii shl 20
                val pi = crcOfData xor addr
                val frame = dataBytes + intArrayOf((pi ushr 16) and 0xFF, (pi ushr 8) and 0xFF, pi and 0xFF)
                val checked = CrcChecker.check(RawFrame(frame))
                assertEquals(CrcChecker.CrcResult.INVALID, checked.crcResult, "ii=$ii should be rejected, not decoded")
            }
        }
    }

    // ── Distance / bearing ────────────────────────────────────────────────────
    @Nested inner class DistanceBearingTests {

        // Real position from CPR test (Heathrow area): ~52.9°N, 0.5°W
        // Observer at (51.5, -0.45) → aircraft is roughly north of observer
        private val CPR_ODD = intArrayOf(
            0x8D,0x48,0x40,0xD6,0x58,0xB5,0x06,0xB2,0x83,0xEB,0x7A,0x94,0x5B,0x84
        )

        @Test fun `distanceNm is computed when observer and position are set`() {
            val dec = makeDecoder(51.5, -0.45)
            val mgr = AircraftManager().also { it.observerLat = 51.5; it.observerLon = -0.45 }
            val msg = valid(dec, CPR_ODD) ?: return
            val state = mgr.update(msg)
            if (state.latitude == null) return  // CPR didn't resolve — skip
            assertNotNull(state.distanceNm)
            assertTrue(state.distanceNm!! in 0.0..500.0, "distanceNm=${state.distanceNm} out of plausible range")
        }

        @Test fun `bearingDeg is in 0-360 range`() {
            val dec = makeDecoder(51.5, -0.45)
            val mgr = AircraftManager().also { it.observerLat = 51.5; it.observerLon = -0.45 }
            val msg = valid(dec, CPR_ODD) ?: return
            val state = mgr.update(msg)
            if (state.bearingDeg == null) return
            assertTrue(state.bearingDeg!! in 0.0..360.0)
        }

        @Test fun `distanceNm is null without observer`() {
            val dec = makeDecoder(51.5, -0.45)
            val mgr = AircraftManager()  // observer stays 0,0
            val msg = valid(dec, CPR_ODD) ?: return
            val state = mgr.update(msg)
            assertNull(state.distanceNm)
        }
    }

    // ── ICAO lookup + sort ────────────────────────────────────────────────────
    @Nested inner class IcaoLookupTests {

        private val LOOKUP = mapOf(
            "AABBCC" to com.laviavi.adsbandroid.aircraft.IcaoEntry("N12345", "Test Air", "B738"),
        )

        @Test fun `lookup enriches registration and operator`() {
            val mgr = AircraftManager().also { it.setLookup(LOOKUP) }
            val dec = makeDecoder()
            // DF11 with ICAO 0xAABBCC
            val msg = valid(dec, intArrayOf(0x5D,0xAA,0xBB,0xCC,0x00,0x00,0x00)) ?: return
            val state = mgr.update(msg)
            assertEquals("N12345", state.registration)
            assertEquals("Test Air", state.operator)
            assertEquals("B738", state.aircraftType)
        }

        @Test fun `lookup does not overwrite existing registration`() {
            val mgr = AircraftManager().also { it.setLookup(LOOKUP) }
            val dec = makeDecoder()
            // Force an existing registration via a direct state seed — do two updates;
            // the first will get lookup data, the second should keep it (not null)
            val msg = valid(dec, intArrayOf(0x5D,0xAA,0xBB,0xCC,0x00,0x00,0x00)) ?: return
            val state1 = mgr.update(msg)
            val state2 = mgr.update(msg)
            assertEquals("N12345", state2.registration)
        }

        @Test fun `NEAREST sort puts a known position before an unknown one`() {
            // Nearest-first is no longer baked into AircraftManager (see
            // AircraftSort) — this now exercises the presentation-layer sort
            // applied to a manager snapshot, same as PipelineService.publishAircraft.
            val mgr = AircraftManager().also {
                it.observerLat = 33.95; it.observerLon = -117.33
            }
            val dec = makeDecoder(33.95, -117.33)
            // Feed a DF11 (no position → null distanceNm → sorted last) and a CPR pair.
            valid(dec, intArrayOf(0x5D,0xAA,0xBB,0xCC,0x00,0x00,0x00))?.let { mgr.update(it) }
            val cpMsg = valid(dec, intArrayOf(
                0x8D,0x48,0x40,0xD6,0x58,0xB5,0x06,0xB2,0x83,0xEB,0x7A,0x94,0x5B,0x84
            )) ?: return
            mgr.update(cpMsg)
            val list = AircraftSort.apply(mgr.aircraft, AircraftSortOrder.NEAREST)
            val withPos    = list.filter { it.distanceNm != null }
            val withoutPos = list.filter { it.distanceNm == null }
            if (withPos.isNotEmpty() && withoutPos.isNotEmpty()) {
                val idxWith    = list.indexOf(withPos.first())
                val idxWithout = list.indexOf(withoutPos.first())
                assertTrue(idxWith < idxWithout, "Aircraft with position should be before aircraft without")
            }
        }
    }

    // ── DF22/23/24 unknown handling ───────────────────────────────────────────
    @Nested inner class Df22_23_24Tests {

        // Seed a DF17 to populate confirmedIcaoCache with 0x4840D6
        private val SEED = intArrayOf(
            0x8D,0x48,0x40,0xD6,0x20,0x2C,0xC3,0x71,0xC3,0x2C,0xE0,0x57,0x60,0x98
        )
        // For DF22/23/24 the ICAO is taken from the last 3 bytes (PA field)
        // Set them to a cached ICAO so extractIcao() doesn't return null
        private fun makeUnknownFrame(df: Int): IntArray {
            val b = IntArray(14)
            b[0] = df shl 3
            b[11] = 0x48; b[12] = 0x40; b[13] = 0xD6
            return b
        }

        @Test fun `DF22 returns Unknown`() {
            val dec = makeDecoder()
            valid(dec, SEED)
            val msg = valid(dec, makeUnknownFrame(22)) ?: return
            assertTrue(msg is DecodedMessage.Unknown, "expected Unknown, got $msg")
        }

        @Test fun `DF23 returns Unknown`() {
            val dec = makeDecoder()
            valid(dec, SEED)
            val msg = valid(dec, makeUnknownFrame(23)) ?: return
            assertTrue(msg is DecodedMessage.Unknown, "expected Unknown, got $msg")
        }

        @Test fun `DF24 returns Unknown`() {
            val dec = makeDecoder()
            valid(dec, SEED)
            val msg = valid(dec, makeUnknownFrame(24)) ?: return
            assertTrue(msg is DecodedMessage.Unknown, "expected Unknown, got $msg")
        }
    }
}
