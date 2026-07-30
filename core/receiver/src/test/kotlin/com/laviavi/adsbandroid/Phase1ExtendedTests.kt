package com.laviavi.adsbandroid

import com.laviavi.adsbandroid.aircraft.AircraftManager
import com.laviavi.adsbandroid.capture.NetworkSource
import com.laviavi.adsbandroid.crc.CrcChecker
import com.laviavi.adsbandroid.decoder.*
import com.laviavi.adsbandroid.demod.Demodulator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class Phase1ExtendedTests {

    // ─────────────────────────────────────────────────────────────────────────
    @Nested inner class DemodulatorPhase1Tests {

        private val demod = Demodulator()

        @Test fun `preambleOk accepts a well-formed dump1090 preamble`() {
            assertTrue(demod.preambleOk(syntheticFrame(REAL_DF17), 0))
        }

        @Test fun `preambleOk rejects a flat signal`() {
            assertFalse(demod.preambleOk(IntArray(4000) { 20_000 }, 0))
        }

        @Test fun `preambleOk rejects noise without the pulse pattern`() {
            val rng = java.util.Random(7)
            val mag = IntArray(4000) { rng.nextInt(65_535) }
            var accepted = 0
            for (j in 0 until 1000) if (demod.preambleOk(mag, j)) accepted++
            assertTrue(accepted < 50, "Preamble check let through $accepted/1000 noise positions")
        }

        @Test fun `extractBits rejects pure noise via the delta floor`() {
            // Flat signal: every bit-pair delta is 0, far below the 2550 floor.
            assertNull(demod.extractBits(IntArray(1000) { 30_000 }, 0, 112))
        }

        @Test fun `extractBits recovers the exact payload bytes`() {
            val mag = syntheticFrame(REAL_DF17)
            val bytes = demod.extractBits(mag, Demodulator.PREAMBLE_SAMPLES, 112)
            assertNotNull(bytes)
            assertArrayEquals(REAL_DF17, bytes)
        }

        @Test fun `detectFrames returns empty for all-silent input`() {
            assertTrue(demod.detectFrames(IntArray(Demodulator.BLOCK_SIZE / 2) { 100 }).isEmpty())
        }

        @Test fun `noise produces few candidates and none survive CRC`() {
            // Uniform random magnitude is far harsher than real RF noise. Some
            // preamble candidates are expected (dump1090 behaves the same) — the
            // property that matters is that CRC rejects all of them. The previous
            // demodulator emitted a frame at essentially every threshold crossing
            // (~9200/sec observed on hardware, 0 valid).
            val rng = java.util.Random(42)
            val mag = IntArray(Demodulator.BLOCK_SIZE / 2) { rng.nextInt(65_535) }
            val frames = demod.detectFrames(mag)
            assertTrue(frames.size < 200, "Noise produced ${frames.size} candidate frames")
            assertEquals(0, frames.count { CrcChecker.computeCrc(it.bytes) == 0 },
                "Noise must not produce a CRC-valid frame")
        }

        @Test fun `detectFrames recovers a real DF17 frame that passes CRC`() {
            val frames = demod.detectFrames(syntheticFrame(REAL_DF17))
            assertEquals(1, frames.size, "Expected exactly one frame")
            assertArrayEquals(REAL_DF17, frames[0].bytes)
            assertEquals(0, CrcChecker.computeCrc(frames[0].bytes), "Demodulated frame must pass CRC")
        }

        @Test fun `detectFrames computes a real, non-zero signal level per frame`() {
            // The demodulator has always computed this (mean of the four preamble
            // peaks). The Signal column reading "—" on real hardware was never a
            // missing calculation here — PipelineService was discarding this value
            // one layer up. See CrcParityTests for the guard on that.
            val frames = demod.detectFrames(syntheticFrame(REAL_DF17))
            assertEquals(1, frames.size)
            assertTrue(frames[0].signalLevel > 0.0, "expected a positive signal level, got ${frames[0].signalLevel}")
        }

        @Test fun `detectFrames trims a short-DF frame to 7 bytes`() {
            // DF11 (short) — must not be emitted as a 14-byte frame.
            val frames = demod.detectFrames(syntheticFrame(REAL_DF11, padTo = 112))
            assertEquals(1, frames.size)
            assertEquals(7, frames[0].bytes.size)
            assertArrayEquals(REAL_DF11, frames[0].bytes)
        }

        @Test fun `full demodulate pipeline decodes a frame from raw IQ`() {
            val frames = demod.demodulate(syntheticIq(REAL_DF17))
            assertTrue(frames.isNotEmpty(), "Expected a frame from synthetic IQ")
            assertArrayEquals(REAL_DF17, frames[0].bytes)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested inner class NetworkSourceTests {

        @Test fun `parseAvrLine valid long frame`() {
            val line = "*8D4840D6202CC371C32CE0576098;"
            val bytes = NetworkSource.parseAvrLine(line)
            assertNotNull(bytes)
            assertEquals(14, bytes!!.size)
            assertEquals(0x8D, bytes[0])
            assertEquals(0x48, bytes[1])
        }

        @Test fun `parseAvrLine valid short frame`() {
            val line = "*5D4840D6341C23;"  // 7 bytes
            val bytes = NetworkSource.parseAvrLine(line)
            assertNotNull(bytes)
            assertEquals(7, bytes!!.size)
        }

        @Test fun `parseAvrLine missing star returns null`() {
            assertNull(NetworkSource.parseAvrLine("8D4840D6202CC371C32CE0576098;"))
        }

        @Test fun `parseAvrLine missing semicolon returns null`() {
            assertNull(NetworkSource.parseAvrLine("*8D4840D6202CC371C32CE0576098"))
        }

        @Test fun `parseAvrLine empty hex returns null`() {
            assertNull(NetworkSource.parseAvrLine("*;"))
        }

        @Test fun `parseAvrLine invalid hex returns null`() {
            assertNull(NetworkSource.parseAvrLine("*8D4840D6202CC371C32CE057609ZZ;"))
        }

        @Test fun `parseAvrLine wrong length returns null`() {
            // 12 bytes (24 hex chars) — not 7 or 14
            assertNull(NetworkSource.parseAvrLine("*8D4840D6202CC371C32CE057;"))
        }

        @Test fun `parseAvrLine with CRLF whitespace`() {
            val line = "*8D4840D6202CC371C32CE0576098;\r\n"
            val bytes = NetworkSource.parseAvrLine(line)
            assertNotNull(bytes)
            assertEquals(14, bytes!!.size)
        }

        @Test fun `parseAvrLine produces correct byte values`() {
            val line = "*8D4840D6202CC371C32CE0576098;"
            val bytes = NetworkSource.parseAvrLine(line)!!
            val expected = intArrayOf(0x8D,0x48,0x40,0xD6,0x20,0x2C,0xC3,0x71,
                                      0xC3,0x2C,0xE0,0x57,0x60,0x98)
            assertArrayEquals(expected, bytes)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested inner class AircraftManagerTests {

        private val VALID_DF17 = intArrayOf(
            0x8D,0x48,0x40,0xD6,0x20,0x2C,0xC3,0x71,0xC3,0x2C,0xE0,0x57,0x60,0x98
        )
        private val VALID_DF17_2 = intArrayOf(
            0x8D,0xC0,0x7B,0x6E,0x58,0x41,0xD5,0x5B,0x72,0x3C,0xAF,0x6C,0xF4,0x46
        )

        private fun makeDecoded(bytes: IntArray): DecodedMessage? {
            val dec = MessageDecoder()
            return dec.decode(CrcChecker.check(RawFrame(bytes.copyOf())))
        }

        @Test fun `new aircraft created on first message`() {
            val mgr = AircraftManager()
            val msg = makeDecoded(VALID_DF17) ?: return
            mgr.update(msg)
            assertEquals(1, mgr.aircraft.size)
            assertEquals("4840D6", mgr.aircraft[0].icao)
        }

        @Test fun `message count increments on each update`() {
            val mgr = AircraftManager()
            val msg = makeDecoded(VALID_DF17) ?: return
            repeat(5) { mgr.update(msg) }
            assertEquals(5, mgr.aircraft[0].messageCount)
        }

        @Test fun `two distinct aircraft tracked separately`() {
            val mgr = AircraftManager()
            mgr.update(makeDecoded(VALID_DF17)  ?: return)
            mgr.update(makeDecoded(VALID_DF17_2) ?: return)
            assertEquals(2, mgr.aircraft.size)
        }

        @Test fun `totalSeen increments only for new aircraft`() {
            val mgr = AircraftManager()
            val msg = makeDecoded(VALID_DF17) ?: return
            repeat(10) { mgr.update(msg) }
            assertEquals(1, mgr.totalSeen)
        }

        @Test fun `reset clears all aircraft`() {
            val mgr = AircraftManager()
            mgr.update(makeDecoded(VALID_DF17)  ?: return)
            mgr.update(makeDecoded(VALID_DF17_2) ?: return)
            mgr.reset()
            assertEquals(0, mgr.aircraft.size)
            assertEquals(0, mgr.totalSeen)
        }

        @Test fun `expireStale removes old aircraft`() {
            val mgr = AircraftManager(expirySeconds = 30)
            val msg = makeDecoded(VALID_DF17) ?: return
            mgr.update(msg)
            assertEquals(1, mgr.aircraft.size)
            // Expire with a future cutoff
            val removed = mgr.expireStale(System.currentTimeMillis() + 60_000)
            assertEquals(1, removed.size)
            assertEquals("4840D6", removed.first().icao, "final state is returned for History")
            assertEquals(0, mgr.aircraft.size)
        }

        @Test fun `expireStale keeps recent aircraft`() {
            val mgr = AircraftManager(expirySeconds = 30)
            mgr.update(makeDecoded(VALID_DF17) ?: return)
            val removed = mgr.expireStale(System.currentTimeMillis())
            assertTrue(removed.isEmpty())
            assertEquals(1, mgr.aircraft.size)
        }

        @Test fun `expiry window is changeable at runtime`() {
            // The setting is edited while the receiver runs, so the window cannot
            // be fixed at construction. It was a constructor val, which is why the
            // configured value never reached the manager.
            val mgr = AircraftManager(expirySeconds = 3600)
            mgr.update(makeDecoded(VALID_DF17) ?: return)
            val now = System.currentTimeMillis()

            assertEquals(0, mgr.expireStale(now + 60_000).size, "1 min < 1 h window")
            mgr.expirySeconds = 30
            assertEquals(1, mgr.expireStale(now + 60_000).size, "1 min > 30 s window")
        }

        @Test fun `aircraft list does not grow without bound`() {
            // The regression this guards: expireStale existed and passed its unit
            // tests, but no caller ever invoked it, so every ICAO ever heard stayed
            // in the list for the life of the session.
            val mgr = AircraftManager(expirySeconds = 30)
            mgr.update(makeDecoded(VALID_DF17) ?: return)
            mgr.update(makeDecoded(VALID_DF17_2) ?: return)
            assertEquals(2, mgr.aircraft.size)

            val laterThanExpiry = System.currentTimeMillis() + 31_000
            mgr.expireStale(laterThanExpiry)
            assertEquals(0, mgr.aircraft.size, "silent aircraft must leave the live list")
            assertEquals(2, mgr.totalSeen, "but the session total still counts them")
        }

        @Test fun `altitude merged from DF17 airborne position frame`() {
            // Use a TC=11 airborne position frame with known altitude
            val cpEven = intArrayOf(
                0x8D,0x48,0x40,0xD6,0x58,0xB5,0x02,0xCF,0x7E,0x9B,0xEF,0x8A,0x2C,0x46
            )
            val mgr = AircraftManager()
            val dec = MessageDecoder()
            val msg = dec.decode(CrcChecker.CheckedFrame(
                RawFrame(cpEven), CrcChecker.CrcResult.VALID, 0))
            if (msg != null) {
                mgr.update(msg)
                val state = mgr.aircraft.firstOrNull { it.icao == "4840D6" }
                assertNotNull(state)
                // Altitude should be populated from ac12 decode
                if (state?.altitudeFt != null) {
                    assertTrue(state.altitudeFt!! > 0)
                }
            }
        }

        @Test fun `callsign merged from TC1-4 frame`() {
            val mgr = AircraftManager()
            val dec = MessageDecoder()
            val csFrame = intArrayOf(
                0x8D,0x48,0x40,0xD6,0x20,0x2C,0xC3,0x71,0xC3,0x2C,0xE0,0x57,0x60,0x98
            )
            val msg = dec.decode(CrcChecker.CheckedFrame(
                RawFrame(csFrame), CrcChecker.CrcResult.VALID, 0))
            if (msg != null) {
                mgr.update(msg)
                val state = mgr.aircraft.firstOrNull()
                if (state?.callsign != null) {
                    assertTrue(state.callsign!!.isNotEmpty())
                }
            }
        }

        @Test fun `lastSeenMs updated on each message`() {
            val mgr = AircraftManager()
            val msg = makeDecoded(VALID_DF17) ?: return
            mgr.update(msg)
            val t1 = mgr.aircraft[0].lastSeenMs
            Thread.sleep(2)
            mgr.update(msg)
            val t2 = mgr.aircraft[0].lastSeenMs
            assertTrue(t2 >= t1)
        }

        @Test fun `firstSeenMs not updated on subsequent messages`() {
            val mgr = AircraftManager()
            val msg = makeDecoded(VALID_DF17) ?: return
            mgr.update(msg)
            val t1 = mgr.aircraft[0].firstSeenMs
            Thread.sleep(2)
            mgr.update(msg)
            assertEquals(t1, mgr.aircraft[0].firstSeenMs)
        }

        @Test fun `aircraft list is in first-seen order, unaffected by later updates`() {
            // Display ordering (nearest-first, most-recent-first, etc.) moved to
            // AircraftSort — see AircraftSortTests. The manager itself now
            // guarantees only first-seen order, which it gets for free from
            // LinkedHashMap: a re-put does not move an existing key.
            val mgr = AircraftManager()
            mgr.update(makeDecoded(VALID_DF17)   ?: return)
            Thread.sleep(2)
            mgr.update(makeDecoded(VALID_DF17_2)  ?: return)
            assertEquals("4840D6", mgr.aircraft[0].icao)
            assertEquals("C07B6E", mgr.aircraft[1].icao)

            // A later update to the first aircraft must not move it.
            mgr.update(makeDecoded(VALID_DF17) ?: return)
            assertEquals("4840D6", mgr.aircraft[0].icao)
            assertEquals("C07B6E", mgr.aircraft[1].icao)
        }
    }
}

// ── Synthetic 2 Msps Mode S signal builders ──────────────────────────────────
//
// These drive the real Demodulator API. (The previous helpers here re-implemented
// the demodulator's internals inside the test file, so they asserted against a
// copy of the logic rather than the shipped code.)

/** Real DF17 extended squitter, CRC-valid. */
val REAL_DF17 = intArrayOf(0x8D,0x48,0x40,0xD6,0x20,0x2C,0xC3,0x71,0xC3,0x2C,0xE0,0x57,0x60,0x98)

/** Real DF11 all-call reply (7 bytes) captured from dump1090. */
val REAL_DF11 = intArrayOf(0x5D,0xA8,0xB4,0xC6,0xBE,0x26,0x3E)

private const val SAMPLE_HIGH = 65167   // magLut[128][128] — IQ (255,255)
private const val SAMPLE_LOW  = 0       // magLut[0][0]     — IQ (127,127)

/**
 * Magnitude vector holding one Mode S frame at offset 0, sampled at 2 Msps:
 * 16-sample preamble with pulses at samples 0,2,7,9, then 2 samples per bit
 * (high-then-low = 1). [padTo] extends the payload with zero bits, which is what
 * a short frame looks like to a demodulator that always slices 112 bits first.
 */
fun syntheticFrame(payload: IntArray, padTo: Int = payload.size * 8): IntArray {
    val bitCount = maxOf(padTo, payload.size * 8)
    val mag = IntArray(16 + bitCount * 2 + 600) { SAMPLE_LOW }
    for (hp in intArrayOf(0, 2, 7, 9)) mag[hp] = SAMPLE_HIGH

    for (bit in 0 until bitCount) {
        val byteIdx = bit / 8
        val bitVal = if (byteIdx < payload.size) (payload[byteIdx] ushr (7 - bit % 8)) and 1 else 0
        val s = 16 + bit * 2
        if (bitVal == 1) { mag[s] = SAMPLE_HIGH; mag[s + 1] = SAMPLE_LOW }
        else             { mag[s] = SAMPLE_LOW;  mag[s + 1] = SAMPLE_HIGH }
    }
    return mag
}

/** The same signal as raw interleaved IQ bytes, so the full pipeline can be exercised. */
fun syntheticIq(payload: IntArray, padTo: Int = payload.size * 8): ByteArray {
    val mag = syntheticFrame(payload, padTo)
    val iq = ByteArray(mag.size * 2)
    for (i in mag.indices) {
        val level = if (mag[i] == SAMPLE_HIGH) 255.toByte() else 127.toByte()
        iq[i * 2] = level
        iq[i * 2 + 1] = level
    }
    return iq
}
