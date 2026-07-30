package com.laviavi.adsbandroid.demod

import com.laviavi.adsbandroid.decoder.RawFrame

/**
 * Mode S / ADS-B IQ demodulator — port of Python `demod/demodulator.py`,
 * which is itself a line-by-line port of dump1090's `detectModeS()`.
 *
 * Requires a **2.0 Msps** input stream: the whole algorithm assumes exactly
 * 2 samples per 1µs Mode S bit and a 16-sample (8µs) preamble. Feeding it
 * 2.4 Msps drifts ~45 samples across a 112-bit frame and decodes nothing.
 *
 * References:
 *  - antirez/dump1090 dump1090.c: computeMagnitudeVector(), detectModeS()
 *  - Python reference: adsb_receiver/demod/demodulator.py
 *
 * No Android dependencies — pure Kotlin, testable in plain JUnit.
 */
class Demodulator {

    // Pre-allocated hot-path buffers. Demodulator is single-threaded (one per pipeline).
    // ponytail: no thread-safety guard — add if ever shared across pipelines
    private val magBuffer   = IntArray(BLOCK_SIZE / 2)
    private val bitsBuffer  = IntArray(MODES_LONG_MSG_BITS)
    private val bytesBuffer = IntArray(MODES_LONG_MSG_BITS / 8)

    companion object {
        const val BLOCK_SIZE = 262_144          // 256K IQ bytes → 131072 magnitude samples

        const val MODES_PREAMBLE_US    = 8
        const val MODES_SHORT_MSG_BITS = 56
        const val MODES_LONG_MSG_BITS  = 112
        const val PREAMBLE_SAMPLES     = MODES_PREAMBLE_US * 2                        // 16
        const val MODES_FULL_LEN       = PREAMBLE_SAMPLES + MODES_LONG_MSG_BITS * 2   // 240

        /** Sample rate this demodulator is built for. Sources must be configured to match. */
        const val REQUIRED_SAMPLE_RATE_HZ = 2_000_000L

        /** DFs carrying a 112-bit payload; everything else is 56-bit. */
        val LONG_DF = setOf(16, 17, 18, 19, 20, 21, 24)

        /** Default preamble gap divisor (dump1090 uses 6). Higher = accepts weaker preambles. */
        const val DEFAULT_PREAMBLE_GAP_DIVISOR = 6

        /** Default noise floor for the mean bit-pair delta (dump1090 uses 10*255). */
        const val DEFAULT_DELTA_FLOOR = 10 * 255

        /**
         * 129×129 magnitude LUT indexed by (|I-127|, |Q-127|), value = round(sqrt(i²+q²) * 360).
         * Exactly dump1090's maglut — the ×360 scale is what [DEFAULT_DELTA_FLOOR] is calibrated to.
         */
        val magLut: Array<IntArray> = Array(129) { i ->
            IntArray(129) { q ->
                Math.round(Math.sqrt((i * i + q * q).toDouble()) * 360).toInt().coerceAtMost(65535)
            }
        }
    }

    /** Tunable at runtime, mirroring the Python demodulator's adjustable thresholds. */
    var preambleGapDivisor: Int = DEFAULT_PREAMBLE_GAP_DIVISOR
    var deltaFloor: Int = DEFAULT_DELTA_FLOOR

    /**
     * Preambles that passed [preambleOk], whether or not they went on to yield a
     * frame. Read by the Receiver screen's `cand/s` row: candidates climbing while
     * frames stay flat is a bit-extraction problem, not an antenna one.
     *
     * Observation only — never read back by the demodulator, so it cannot affect
     * decode output or parity.
     */
    var candidateCount: Long = 0L
        private set

    fun demodulate(data: ByteArray): List<RawFrame> {
        val len = data.size / 2
        for (j in 0 until len) {
            val i = Math.abs((data[j * 2].toInt() and 0xFF) - 127).coerceAtMost(128)
            val q = Math.abs((data[j * 2 + 1].toInt() and 0xFF) - 127).coerceAtMost(128)
            magBuffer[j] = magLut[i][q]
        }
        return detectFrames(magBuffer, len)
    }

    /** Interleaved uint8 IQ → magnitude, matching dump1090's computeMagnitudeVector(). */
    fun computeMagnitude(data: ByteArray): IntArray {
        val len = data.size / 2
        val vector = IntArray(len)
        for (j in 0 until len) {
            val i = Math.abs((data[j * 2].toInt() and 0xFF) - 127).coerceAtMost(128)
            val q = Math.abs((data[j * 2 + 1].toInt() and 0xFF) - 127).coerceAtMost(128)
            vector[j] = magLut[i][q]
        }
        return vector
    }

    fun detectFrames(mag: IntArray): List<RawFrame> = detectFrames(mag, mag.size)

    fun detectFrames(mag: IntArray, len: Int): List<RawFrame> {
        val frames = mutableListOf<RawFrame>()
        if (len < MODES_FULL_LEN + 16) return frames

        var j = 0
        while (j < len - MODES_FULL_LEN * 2) {
            if (!preambleOk(mag, j)) { j++; continue }
            candidateCount++

            // Decode into pre-allocated buffers; only allocate the final byte array for kept frames.
            if (!extractBitsInto(mag, j + PREAMBLE_SAMPLES, MODES_LONG_MSG_BITS, bitsBuffer, bytesBuffer)) {
                j++; continue
            }

            val df = (bytesBuffer[0] shr 3) and 0x1F
            val nBits = if (df in LONG_DF) MODES_LONG_MSG_BITS else MODES_SHORT_MSG_BITS
            val bytes = bytesBuffer.copyOf(nBits / 8)

            val signal = ((mag[j] + mag[j + 2] + mag[j + 7] + mag[j + 9]) / 4.0) / 65535.0

            frames.add(RawFrame(bytes, signal, sampleOffset = j))
            j += PREAMBLE_SAMPLES + nBits * 2
        }
        return frames
    }

    /**
     * dump1090's preamble test: a fixed chain of inequalities over the first 10
     * samples, then a check that the inter-pulse gap (4,5) and the pre-payload
     * gap (11-14) sit below the peak average.
     */
    fun preambleOk(m: IntArray, j: Int): Boolean {
        if (j + MODES_FULL_LEN * 2 >= m.size) return false

        val m0 = m[j];     val m1 = m[j + 1]; val m2 = m[j + 2]; val m3 = m[j + 3]
        val m4 = m[j + 4]; val m5 = m[j + 5]; val m6 = m[j + 6]; val m7 = m[j + 7]
        val m8 = m[j + 8]; val m9 = m[j + 9]

        if (!(m0 > m1 && m1 < m2 && m2 > m3 && m3 < m0 && m4 < m0 && m5 < m0 &&
              m6 < m0 && m7 > m8 && m8 < m9 && m9 > m6)) return false

        val high = (m0 + m2 + m7 + m9) / preambleGapDivisor
        if (m4 >= high || m5 >= high) return false
        if (m[j + 11] >= high || m[j + 12] >= high || m[j + 13] >= high || m[j + 14] >= high) return false
        return true
    }

    /** In-place variant used by detectFrames — writes into caller-supplied buffers, no allocation. */
    private fun extractBitsInto(m: IntArray, start: Int, nBits: Int, bitsOut: IntArray, bytesOut: IntArray): Boolean {
        if (start + nBits * 2 > m.size) return false

        var deltaSum = 0L
        var i = 0
        while (i < nBits * 2) {
            val low  = m[start + i]
            val high = m[start + i + 1]
            val delta = Math.abs(low - high)
            deltaSum += delta
            val bitIdx = i / 2
            bitsOut[bitIdx] = when {
                i > 0 && delta < 256 -> bitsOut[bitIdx - 1]
                low == high -> ERROR_BIT
                low > high  -> 1
                else        -> 0
            }
            i += 2
        }

        if (deltaSum / (nBits / 2) < deltaFloor) return false

        bytesOut.fill(0, 0, nBits / 8)
        for (k in 0 until nBits) {
            if (bitsOut[k] == 1) bytesOut[k shr 3] = bytesOut[k shr 3] or (1 shl (7 - (k and 7)))
        }
        return true
    }

    /**
     * dump1090's PPM bit slicer. Each bit is two samples: first > second = 1.
     * A pair whose delta is under 256 is too ambiguous to call, so the previous
     * bit is carried; an exactly-equal pair is marked as an error and packs as 0.
     * Frames whose mean delta falls below [deltaFloor] are pure noise and rejected —
     * this is the gate that stops the detector emitting garbage on an empty band.
     */
    fun extractBits(m: IntArray, start: Int, nBits: Int): IntArray? {
        if (start + nBits * 2 > m.size) return null

        val bits = IntArray(nBits)
        var deltaSum = 0L
        var i = 0
        while (i < nBits * 2) {
            val low = m[start + i]
            val high = m[start + i + 1]
            val delta = Math.abs(low - high)
            deltaSum += delta
            val bitIdx = i / 2
            bits[bitIdx] = when {
                i > 0 && delta < 256 -> bits[bitIdx - 1]
                low == high -> ERROR_BIT
                low > high -> 1
                else -> 0
            }
            i += 2
        }

        if (deltaSum / (nBits / 2) < deltaFloor) return null

        val bytes = IntArray(nBits / 8)
        for (k in 0 until nBits) {
            if (bits[k] == 1) bytes[k shr 3] = bytes[k shr 3] or (1 shl (7 - (k and 7)))
        }
        return bytes
    }
}

/** Marker for an undecidable bit pair; packs as 0, same as Python's error marker. */
private const val ERROR_BIT = 2
