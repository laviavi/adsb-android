package com.laviavi.adsbandroid.crc

import com.laviavi.adsbandroid.decoder.RawFrame

/**
 * Mode S 24-bit CRC / parity checker — behaviour matches Python `crc/checker.py`.
 *
 * Two CRC families, per ICAO Annex 10:
 *  - Pure-CRC DFs (11, 17, 18): trailing 24 bits are a plain CRC. Verifiable by
 *    anyone; a valid frame's ICAO is added to [IcaoCache].
 *  - Parity-Address DFs (0, 4, 5, 16, 20, 21, 24): trailing 24 bits are
 *    CRC(message) XOR transponder_address ("AP"). Not verifiable blind. The
 *    candidate address is recovered and validated against [IcaoCache]
 *    (dump1090's bruteForceAP); a hit yields [CrcResult.RECOVERED].
 *
 * Recovery identity: because the CRC is linear and AP = CRC(data) XOR addr,
 * the remainder over the *whole* frame equals the address itself —
 * so [computeCrc] of the full frame IS the candidate ICAO. Verified in tests.
 */
object CrcChecker {

    /**
     * VALID/CORRECTED/INVALID map to Python's valid/corrected/bad.
     * PARITY_ADDRESS and RECOVERED mirror Python's parity_addr/recovered.
     */
    enum class CrcResult { VALID, CORRECTED, INVALID, PARITY_ADDRESS, RECOVERED }

    data class CheckedFrame(
        val frame: RawFrame,
        val crcResult: CrcResult,
        val crc: Int,
        /** Address recovered from an AP frame; only set when [crcResult] is RECOVERED. */
        val recoveredIcao: Int? = null,
    )

    /** DFs whose trailing 24 bits are a plain CRC rather than an address-XOR. */
    private val PURE_CRC_DF = setOf(11, 17, 18)

    private val CHECKSUM_TABLE = intArrayOf(
        0x3935ea, 0x1c9af5, 0xf1b77e, 0x78dbbf, 0xc397db, 0x9e31e9, 0xb0e2f0, 0x587178,
        0x2c38bc, 0x161c5e, 0x0b0e2f, 0xfa7d13, 0x82c48d, 0xbe9842, 0x5f4c21, 0xd05c14,
        0x682e0a, 0x341705, 0xe5f186, 0x72f8c3, 0xc68665, 0x9cb936, 0x4e5c9b, 0xd8d449,
        0x939020, 0x49c810, 0x24e408, 0x127204, 0x093902, 0x049c81, 0xfdb444, 0x7eda22,
        0x3f6d11, 0xe04c8c, 0x702646, 0x381323, 0xe3f395, 0x8e03ce, 0x4701e7, 0xdc7af7,
        0x91c77f, 0xb719bb, 0xa476d9, 0xadc168, 0x56e0b4, 0x2b705a, 0x15b82d, 0xf52612,
        0x7a9309, 0xc2b380, 0x6159c0, 0x30ace0, 0x185670, 0x0c2b38, 0x06159c, 0x030ace,
        0x018567, 0xff38b7, 0x80665f, 0xbfc92b, 0xa01e91, 0xaff54c, 0x57faa6, 0x2bfd53,
        0xea04ad, 0x8af852, 0x457c29, 0xdd4410, 0x6ea208, 0x375104, 0x1ba882, 0x0dd441,
        0xf91024, 0x7c8812, 0x3e4409, 0xe0d800, 0x706c00, 0x383600, 0x1c1b00, 0x0e0d80,
        0x0706c0, 0x038360, 0x01c1b0, 0x00e0d8, 0x00706c, 0x003836, 0x001c1b, 0xfff409,
        0x000000, 0x000000, 0x000000, 0x000000, 0x000000, 0x000000, 0x000000, 0x000000,
        0x000000, 0x000000, 0x000000, 0x000000, 0x000000, 0x000000, 0x000000, 0x000000,
        0x000000, 0x000000, 0x000000, 0x000000, 0x000000, 0x000000, 0x000000, 0x000000,
    )

    private val singleBitSyndromes: Map<Int, Int> by lazy { buildSingleBitSyndromes() }

    /**
     * @param icaoCache required to validate parity-address frames. When null, AP
     *   frames can only ever be reported as [CrcResult.PARITY_ADDRESS].
     */
    fun check(
        frame: RawFrame,
        correctSingleBit: Boolean = true,
        icaoCache: IcaoCache? = null,
    ): CheckedFrame {
        val crc = computeCrc(frame.bytes)
        val df = frame.downlinkFormat

        if (df in PURE_CRC_DF) {
            if (crc == 0) {
                icaoCache?.let { cache -> frameIcao(frame)?.let { cache.add(it) } }
                return CheckedFrame(frame, CrcResult.VALID, 0)
            }
            // dump1090 special case: DF11 with a small residual is an Interrogator
            // Identifier, not corruption — genuine if we already know the address.
            if (df == 11 && crc < 80 && icaoCache != null) {
                val icao = frameIcao(frame)
                if (icao != null && icaoCache.contains(icao)) {
                    return CheckedFrame(frame, CrcResult.VALID, crc)
                }
            }
            if (df in setOf(17, 18) && correctSingleBit && frame.bytes.size == 14) {
                val bitPos = singleBitSyndromes[crc]
                if (bitPos != null) {
                    val corrected = frame.bytes.copyOf()
                    corrected[bitPos / 8] = corrected[bitPos / 8] xor (1 shl (7 - bitPos % 8))
                    if (computeCrc(corrected) == 0) {
                        val fixed = frame.copy(bytes = corrected)
                        icaoCache?.let { cache -> frameIcao(fixed)?.let { cache.add(it) } }
                        return CheckedFrame(fixed, CrcResult.CORRECTED, 0)
                    }
                }
            }
            return CheckedFrame(frame, CrcResult.INVALID, crc)
        }

        // Parity-Address frame: the full-frame remainder IS the candidate address.
        if (frame.bytes.size < 4) return CheckedFrame(frame, CrcResult.PARITY_ADDRESS, crc)
        if (icaoCache != null && icaoCache.contains(crc)) {
            return CheckedFrame(frame, CrcResult.RECOVERED, crc, recoveredIcao = crc)
        }
        return CheckedFrame(frame, CrcResult.PARITY_ADDRESS, crc)
    }

    /** ICAO from bytes 1-3 — only meaningful for pure-CRC DFs. */
    private fun frameIcao(frame: RawFrame): Int? {
        if (frame.bytes.size < 4) return null
        val icao = (frame.bytes[1] shl 16) or (frame.bytes[2] shl 8) or frame.bytes[3]
        return icao.takeIf { it > 0 }
    }

    fun computeCrc(msg: IntArray): Int {
        val length = msg.size
        val bits = length * 8
        val offset = if (bits == 112) 0 else 56
        var crc = 0
        for (i in 0 until bits - 24) {
            if ((msg[i / 8] shr (7 - i % 8)) and 1 == 1)
                crc = crc xor CHECKSUM_TABLE[i + offset]
        }
        val rem = ((msg[length - 3] shl 16) or (msg[length - 2] shl 8) or msg[length - 1])
        return (crc xor rem) and 0xFFFFFF
    }

    /**
     * Syndromes for single-bit flips in the 88 *data* bits of a 112-bit frame.
     * The 24 CRC bits are deliberately excluded (matches Python
     * `_try_single_bit_correction`): "correcting" a parity bit would accept a
     * frame whose payload is still wrong.
     */
    private fun buildSingleBitSyndromes(): Map<Int, Int> {
        val map = HashMap<Int, Int>(88)
        val msg = IntArray(14)
        for (i in 0 until 88) {
            msg[i / 8] = msg[i / 8] xor (1 shl (7 - i % 8))
            val syndrome = computeCrc(msg)
            if (syndrome != 0) map[syndrome] = i
            msg[i / 8] = msg[i / 8] xor (1 shl (7 - i % 8))
        }
        return map
    }
}
