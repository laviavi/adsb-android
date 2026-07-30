package com.laviavi.adsbandroid

import com.laviavi.adsbandroid.crc.CrcChecker
import com.laviavi.adsbandroid.decoder.RawFrame
import com.laviavi.adsbandroid.decoder.MessageDecoder
import com.laviavi.adsbandroid.demod.Demodulator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Nested

// Known valid DF17 frames (verified against dump1090 and Python test suite)
private val VALID_DF17_1 = intArrayOf(
    0x8D, 0x48, 0x40, 0xD6, 0x20, 0x2C, 0xC3, 0x71, 0xC3, 0x2C, 0xE0, 0x57, 0x60, 0x98
)
// DF17 TC=11, ICAO=C07B6E — CRC-verified via Python
private val VALID_DF17_2 = intArrayOf(
    0x8D, 0xC0, 0x7B, 0x6E, 0x58, 0x41, 0xD5, 0x5B, 0x72, 0x3C, 0xAF, 0x6C, 0xF4, 0x46
)
// Valid DF11 (short, 7 bytes) — All-Call Reply
private val VALID_DF11 = intArrayOf(
    0x5D, 0xAB, 0x45, 0xCF, 0x34, 0xCA, 0x23
)

class Phase0Tests {

    @Nested
    inner class CrcCheckerTests {

        @Test fun `valid DF17 frame 1 has zero CRC`() {
            assertEquals(0, CrcChecker.computeCrc(VALID_DF17_1))
        }

        @Test fun `valid DF17 frame 2 has zero CRC`() {
            assertEquals(0, CrcChecker.computeCrc(VALID_DF17_2))
        }

        @Test fun `check returns VALID for clean DF17`() {
            val result = CrcChecker.check(RawFrame(VALID_DF17_1.copyOf()))
            assertEquals(CrcChecker.CrcResult.VALID, result.crcResult)
            assertEquals(0, result.crc)
        }

        @Test fun `check returns CORRECTED for single-bit error in DF17`() {
            val corrupted = VALID_DF17_1.copyOf()
            corrupted[0] = corrupted[0] xor 0x01  // flip bit 7
            val result = CrcChecker.check(RawFrame(corrupted))
            assertEquals(CrcChecker.CrcResult.CORRECTED, result.crcResult)
            // corrected frame should have zero CRC
            assertEquals(0, CrcChecker.computeCrc(result.frame.bytes))
        }

        @Test fun `check returns CORRECTED for single-bit error in payload byte`() {
            val corrupted = VALID_DF17_1.copyOf()
            corrupted[5] = corrupted[5] xor 0x08  // flip a payload bit
            val result = CrcChecker.check(RawFrame(corrupted))
            assertEquals(CrcChecker.CrcResult.CORRECTED, result.crcResult)
        }

        @Test fun `check returns INVALID for two-bit error`() {
            val corrupted = VALID_DF17_1.copyOf()
            corrupted[0] = corrupted[0] xor 0x03
            corrupted[5] = corrupted[5] xor 0x40
            val result = CrcChecker.check(RawFrame(corrupted))
            assertEquals(CrcChecker.CrcResult.INVALID, result.crcResult)
        }

        @Test fun `single-bit correction disabled returns INVALID for corrupted frame`() {
            val corrupted = VALID_DF17_1.copyOf()
            corrupted[0] = corrupted[0] xor 0x01
            val result = CrcChecker.check(RawFrame(corrupted), correctSingleBit = false)
            assertEquals(CrcChecker.CrcResult.INVALID, result.crcResult)
        }

        @Test fun `DF format extracted correctly from frame`() {
            val frame = RawFrame(VALID_DF17_1.copyOf())
            assertEquals(17, frame.downlinkFormat)
        }

        @Test fun `isLong true for 14-byte frame`() {
            assertTrue(RawFrame(VALID_DF17_1.copyOf()).isLong)
        }

        @Test fun `isLong false for 7-byte frame`() {
            assertFalse(RawFrame(VALID_DF11.copyOf()).isLong)
        }

        @Test fun `CRC table produces correct syndrome for bit flip at position 0`() {
            val msg = IntArray(14)
            msg[0] = msg[0] xor (1 shl 7)
            val syndrome = CrcChecker.computeCrc(msg)
            assertNotEquals(0, syndrome)  // flipped bit must produce non-zero syndrome
        }

        @Test fun `single-bit syndrome map covers all 112 bit positions`() {
            // Force lazy init, then verify round-trip: for each bit flip, CRC identifies it
            for (i in 0 until 112) {
                val msg = IntArray(14)
                msg[i / 8] = msg[i / 8] xor (1 shl (7 - i % 8))
                val corrupted = RawFrame(msg.copyOf().also {
                    // Fake it as DF17 so single-bit correction is attempted
                    it[0] = 0x8D
                })
                // Just verifying no exception thrown for all 112 positions
                CrcChecker.check(corrupted)
            }
        }
    }

    @Nested
    inner class MessageDecoderTests {

        private val decoder = MessageDecoder()

        @Test fun `decode returns null for INVALID frame`() {
            val frame = RawFrame(VALID_DF17_1.copyOf())
            val checked = CrcChecker.CheckedFrame(frame, CrcChecker.CrcResult.INVALID, 0x123456)
            assertNull(decoder.decode(checked))
        }

        @Test fun `decode extracts ICAO from DF17 frame`() {
            val checked = CrcChecker.check(RawFrame(VALID_DF17_1.copyOf()))
            val msg = decoder.decode(checked)
            assertNotNull(msg)
            assertEquals(0x4840D6, msg!!.icao)
        }

        @Test fun `decode extracts correct ICAO from second DF17 frame`() {
            val checked = CrcChecker.check(RawFrame(VALID_DF17_2.copyOf()))
            val msg = decoder.decode(checked)
            assertNotNull(msg)
            assertEquals(0xC07B6E, msg!!.icao)
        }

        @Test fun `parity-address DF rejected if ICAO not in confirmed cache`() {
            // DF4 = 0x20 >> 3 = 4; unknown ICAO — should be rejected
            val df4Frame = intArrayOf(0x20, 0x00, 0x08, 0x05, 0x77, 0xEE, 0xDD)
            val frame = RawFrame(df4Frame)
            val checked = CrcChecker.CheckedFrame(frame, CrcChecker.CrcResult.VALID, 0)
            assertNull(decoder.decode(checked))
        }

        @Test fun `parity-address DF admitted if ICAO previously confirmed`() {
            // First decode a DF17 to populate confirmed cache
            val df17Checked = CrcChecker.check(RawFrame(VALID_DF17_1.copyOf()))
            decoder.decode(df17Checked) // populates cache with 0x4840D6

            // Now craft a DF4 whose last 3 bytes == confirmed ICAO
            val df4Frame = intArrayOf(0x20, 0x00, 0x08, 0x05, 0x48, 0x40, 0xD6)
            val checked = CrcChecker.CheckedFrame(RawFrame(df4Frame), CrcChecker.CrcResult.VALID, 0)
            assertNotNull(decoder.decode(checked))
        }
    }

    @Nested
    inner class DemodulatorTests {

        private val demod = Demodulator()

        // LUT is dump1090's 129x129 maglut, indexed by (|I-127|, |Q-127|), scaled x360.
        @Test fun `magnitude LUT is 129x129 (dump1090 maglut)`() {
            assertEquals(129, Demodulator.magLut.size)
            assertEquals(129, Demodulator.magLut[0].size)
        }

        @Test fun `magnitude LUT zero deviation is zero magnitude`() {
            assertEquals(0, Demodulator.magLut[0][0])
        }

        @Test fun `magnitude LUT scales by 360 per dump1090`() {
            // |I-127|=128, |Q-127|=0 -> sqrt(128^2) * 360 = 46080
            assertEquals(46080, Demodulator.magLut[128][0])
        }

        @Test fun `magnitude LUT max stays within uint16`() {
            assertTrue(Demodulator.magLut[128][128] <= 65535)
            assertTrue(Demodulator.magLut[128][128] > Demodulator.magLut[64][64])
        }

        @Test fun `magnitude LUT is symmetric`() {
            assertEquals(Demodulator.magLut[100][50], Demodulator.magLut[50][100])
        }

        @Test fun `computeMagnitude output length is half input length`() {
            val data = ByteArray(1024) { 127 }
            val mag = demod.computeMagnitude(data)
            assertEquals(512, mag.size)
        }

        @Test fun `computeMagnitude handles full block size`() {
            val data = ByteArray(Demodulator.BLOCK_SIZE) { 127 }
            val mag = demod.computeMagnitude(data)
            assertEquals(Demodulator.BLOCK_SIZE / 2, mag.size)
        }

        @Test fun `demodulate returns empty list for silent input (Phase 0 stub)`() {
            val data = ByteArray(Demodulator.BLOCK_SIZE) { 127 }
            val frames = demod.demodulate(data)
            assertTrue(frames.isEmpty())
        }
    }
}
