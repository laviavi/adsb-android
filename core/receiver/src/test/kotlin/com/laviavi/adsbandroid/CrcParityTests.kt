package com.laviavi.adsbandroid

import com.laviavi.adsbandroid.crc.CrcChecker
import com.laviavi.adsbandroid.crc.IcaoCache
import com.laviavi.adsbandroid.decoder.MessageDecoder
import com.laviavi.adsbandroid.decoder.RawFrame
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * CRC / parity behaviour, mirroring Python `crc/checker.py`.
 *
 * Covers the two CRC families separately: pure-CRC frames (DF11/17/18) that any
 * receiver can verify, and parity-address frames (DF0/4/5/16/20/21) whose
 * trailing bytes are CRC XOR aircraft address and can only be validated against
 * a previously confirmed ICAO.
 */
class CrcParityTests {

    private fun hex(s: String) = IntArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16) }
    private fun frame(s: String) = RawFrame(hex(s))

    // Real frames captured from dump1090 on this antenna.
    private val DF17_VALID = "8D4840D6202CC371C32CE0576098"
    private val DF17_VALID_2 = "8DAAA95458531642BEB959CC2043"

    @Nested inner class PureCrcFrames {

        @Test fun `valid DF17 has zero remainder`() {
            assertEquals(0, CrcChecker.computeCrc(hex(DF17_VALID)))
        }

        @Test fun `valid DF17 reports VALID`() {
            val r = CrcChecker.check(frame(DF17_VALID))
            assertEquals(CrcChecker.CrcResult.VALID, r.crcResult)
        }

        @Test fun `corrupted DF17 does not report VALID`() {
            val bytes = hex(DF17_VALID)
            bytes[5] = bytes[5] xor 0xFF          // 8-bit smash, not correctable
            val r = CrcChecker.check(RawFrame(bytes), correctSingleBit = true)
            assertNotEquals(CrcChecker.CrcResult.VALID, r.crcResult)
        }

        @Test fun `single-bit error in a data bit is corrected`() {
            val bytes = hex(DF17_VALID)
            bytes[4] = bytes[4] xor 0x08          // flip one data bit
            val r = CrcChecker.check(RawFrame(bytes), correctSingleBit = true)
            assertEquals(CrcChecker.CrcResult.CORRECTED, r.crcResult)
            assertArrayEquals(hex(DF17_VALID), r.frame.bytes, "Correction must restore the original frame")
        }

        @Test fun `single-bit correction can be disabled`() {
            val bytes = hex(DF17_VALID)
            bytes[4] = bytes[4] xor 0x08
            val r = CrcChecker.check(RawFrame(bytes), correctSingleBit = false)
            assertEquals(CrcChecker.CrcResult.INVALID, r.crcResult)
        }

        @Test fun `a flipped CRC bit is not silently corrected`() {
            // Python only flips the 88 data bits; "fixing" a parity bit would
            // accept a frame whose payload is still wrong.
            val bytes = hex(DF17_VALID)
            bytes[13] = bytes[13] xor 0x01        // last byte is CRC
            val r = CrcChecker.check(RawFrame(bytes), correctSingleBit = true)
            assertEquals(CrcChecker.CrcResult.INVALID, r.crcResult)
        }

        @Test fun `valid DF17 populates the ICAO cache`() {
            val cache = IcaoCache()
            CrcChecker.check(frame(DF17_VALID), icaoCache = cache)
            assertTrue(cache.contains(0x4840D6))
        }

        @Test fun `signal level survives an unmodified frame`() {
            // Regression guard: PipelineService used to call demodulator.demodulate()
            // (which computes a real per-frame signal level), then discard the
            // whole RawFrame down to `.bytes` and rebuild a bare RawFrame(bytes) —
            // silently resetting signalLevel to its 0.0 default on every frame.
            // The fix was to stop reconstructing the frame at all; this pins the
            // property CrcChecker must preserve for that fix to hold.
            val withSignal = RawFrame(hex(DF17_VALID), signalLevel = 0.73)
            val r = CrcChecker.check(withSignal)
            assertEquals(0.73, r.frame.signalLevel, 0.0001)
        }

        @Test fun `signal level survives single-bit correction`() {
            // The CORRECTED path builds a new RawFrame via frame.copy(bytes = ...) —
            // confirms that copy carries signalLevel forward too, not just bytes.
            val bytes = hex(DF17_VALID)
            bytes[4] = bytes[4] xor 0x08
            val withSignal = RawFrame(bytes, signalLevel = 0.42)
            val r = CrcChecker.check(withSignal, correctSingleBit = true)
            assertEquals(CrcChecker.CrcResult.CORRECTED, r.crcResult)
            assertEquals(0.42, r.frame.signalLevel, 0.0001)
        }
    }

    @Nested inner class ParityAddressFrames {

        // DF20 (Comm-B) — trailing 3 bytes are CRC XOR the aircraft address.
        private fun apFrameFor(icao: Int): IntArray {
            val bytes = IntArray(14)
            bytes[0] = 20 shl 3
            bytes[2] = 0x18
            bytes[4] = 0x10
            // AP = CRC(data) XOR icao. computeCrc returns CRC(data) XOR trailing,
            // so writing 0 first yields CRC(data) directly.
            val crcData = CrcChecker.computeCrc(bytes)
            val ap = crcData xor icao
            bytes[11] = (ap shr 16) and 0xFF
            bytes[12] = (ap shr 8) and 0xFF
            bytes[13] = ap and 0xFF
            return bytes
        }

        @Test fun `full-frame remainder equals the aircraft address`() {
            // The identity the recovery relies on (dump1090 bruteForceAP).
            val icao = 0xABCDEF
            assertEquals(icao, CrcChecker.computeCrc(apFrameFor(icao)))
        }

        @Test fun `unknown address yields PARITY_ADDRESS not VALID`() {
            val r = CrcChecker.check(RawFrame(apFrameFor(0xABCDEF)), icaoCache = IcaoCache())
            assertEquals(CrcChecker.CrcResult.PARITY_ADDRESS, r.crcResult)
        }

        @Test fun `address confirmed by an earlier DF17 yields RECOVERED`() {
            val cache = IcaoCache()
            cache.add(0xABCDEF)
            val r = CrcChecker.check(RawFrame(apFrameFor(0xABCDEF)), icaoCache = cache)
            assertEquals(CrcChecker.CrcResult.RECOVERED, r.crcResult)
            assertEquals(0xABCDEF, r.recoveredIcao)
        }

        @Test fun `without a cache AP frames can never be validated`() {
            val r = CrcChecker.check(RawFrame(apFrameFor(0xABCDEF)), icaoCache = null)
            assertEquals(CrcChecker.CrcResult.PARITY_ADDRESS, r.crcResult)
        }

        @Test fun `decoder drops PARITY_ADDRESS frames and accepts RECOVERED ones`() {
            val decoder = MessageDecoder()
            val cache = IcaoCache()
            val unknown = CrcChecker.check(RawFrame(apFrameFor(0xABCDEF)), icaoCache = cache)
            assertNull(decoder.decode(unknown), "Unverifiable AP frame must be dropped")

            cache.add(0xABCDEF)
            val known = CrcChecker.check(RawFrame(apFrameFor(0xABCDEF)), icaoCache = cache)
            val decoded = decoder.decode(known)
            assertNotNull(decoded, "AP frame with a confirmed address must decode")
            assertEquals(0xABCDEF, decoded!!.icao)
        }

        @Test fun `end-to-end DF17 then DF20 for the same aircraft`() {
            val decoder = MessageDecoder()
            val cache = IcaoCache()
            // DF17 confirms 4840D6...
            assertEquals(CrcChecker.CrcResult.VALID,
                CrcChecker.check(frame(DF17_VALID), icaoCache = cache).crcResult)
            // ...which then unlocks that aircraft's surveillance replies.
            val r = CrcChecker.check(RawFrame(apFrameFor(0x4840D6)), icaoCache = cache)
            assertEquals(CrcChecker.CrcResult.RECOVERED, r.crcResult)
            assertEquals(0x4840D6, decoder.decode(r)?.icao)
        }
    }

    @Nested inner class CacheBehaviour {

        @Test fun `entries expire after the TTL`() {
            val cache = IcaoCache()
            cache.add(0x123456, nowMs = 0L)
            assertTrue(cache.contains(0x123456, nowMs = IcaoCache.CACHE_TTL_MS))
            assertFalse(cache.contains(0x123456, nowMs = IcaoCache.CACHE_TTL_MS + 1))
        }

        @Test fun `unrelated address is not reported present`() {
            val cache = IcaoCache()
            cache.add(0x123456)
            assertFalse(cache.contains(0x654321))
        }

        @Test fun `clear empties the cache`() {
            val cache = IcaoCache()
            cache.add(0x123456)
            cache.clear()
            assertFalse(cache.contains(0x123456))
        }
    }

    /**
     * DF11's trailing field is PI = CRC(data) XOR II, so the syndrome returned by
     * [CrcChecker.computeCrc] must be the interrogator ID itself (0-63), which is
     * what makes dump1090's "residual under 80 from a known aircraft is valid"
     * rule meaningful. Frames below are real captures from this antenna.
     */
    @Nested inner class Df11InterrogatorId {

        @Test fun `syndrome of a DF11 reply is its interrogator ID`() {
            // Independently derived as CRC(data) XOR PI for each frame.
            assertEquals(0, CrcChecker.computeCrc(hex("5DA8B4C6BE263E")))
            assertEquals(7, CrcChecker.computeCrc(hex("5DA6E361B31A71")))
            assertEquals(2, CrcChecker.computeCrc(hex("5DA3244CD85A64")))
            assertEquals(20, CrcChecker.computeCrc(hex("5DA82824EAEC05")))
        }

        @Test fun `DF11 with an interrogator ID is valid once the aircraft is known`() {
            val cache = IcaoCache()
            val f = frame("5DA6E361B31A71")          // II = 7
            assertEquals(CrcChecker.CrcResult.INVALID, CrcChecker.check(f, icaoCache = cache).crcResult)
            cache.add(0xA6E361)
            assertEquals(CrcChecker.CrcResult.VALID, CrcChecker.check(f, icaoCache = cache).crcResult)
        }

        @Test fun `a large residual is never accepted as an interrogator ID`() {
            val bytes = hex("5DA6E361B31A71")
            bytes[2] = bytes[2] xor 0x5A             // corrupt the address body
            val cache = IcaoCache()
            cache.add((bytes[1] shl 16) or (bytes[2] shl 8) or bytes[3])
            val r = CrcChecker.check(RawFrame(bytes), icaoCache = cache)
            assertTrue(CrcChecker.computeCrc(bytes) >= 80, "Test frame should have a large residual")
            assertEquals(CrcChecker.CrcResult.INVALID, r.crcResult)
        }
    }

    @Nested inner class ShortFrames {

        @Test fun `DF11 short frame CRC uses the 56-bit offset`() {
            // A 7-byte frame must not be checked with the 112-bit table offset.
            val df11 = frame("5DA8B4C6BE263E")
            assertEquals(11, df11.downlinkFormat)
            assertDoesNotThrow { CrcChecker.computeCrc(df11.bytes) }
        }
    }
}
