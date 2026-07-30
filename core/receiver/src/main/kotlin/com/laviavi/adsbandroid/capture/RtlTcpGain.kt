package com.laviavi.adsbandroid.capture

/**
 * Tuner identification and gain tables for an rtl_tcp source.
 *
 * On connect, rtl_tcp sends a 12-byte dongle-info header:
 *   [0..3]  ASCII magic "RTL0"
 *   [4..7]  tuner type   (big-endian uint32, `rtlsdr_get_tuner_type`)
 *   [8..11] gain count   (big-endian uint32, `rtlsdr_get_tuner_gains(dev, NULL)`)
 *
 * The protocol reports the tuner *type* and the *number* of gain steps, not the
 * steps themselves, so the values below are librtlsdr's own per-tuner tables
 * (verified against `rtlsdr/src/main/cpp/librtlsdr/src/librtlsdr.c`
 * `rtlsdr_get_tuner_gains`). The reported count is used to verify the table
 * actually matches the attached hardware — a mismatch is surfaced rather than
 * guessed around, so an unknown gain is never silently applied.
 */
enum class TunerType(val code: Int, val displayName: String) {
    UNKNOWN(0, "Unknown"),
    E4000(1, "E4000"),
    FC0012(2, "FC0012"),
    FC0013(3, "FC0013"),
    FC2580(4, "FC2580"),
    R820T(5, "R820T"),
    R828D(6, "R828D");

    companion object {
        fun fromCode(code: Int): TunerType? = entries.firstOrNull { it.code == code }
    }
}

data class DongleInfo(val tuner: TunerType, val reportedGainCount: Int)

/** Manual gain steps for the attached dongle, or a specific reason they're unusable. */
sealed interface GainOptions {
    data class Available(val tuner: TunerType, val gainsTenths: List<Int>) : GainOptions
    data class Unavailable(val reason: String) : GainOptions
}

object RtlTcpGain {

    const val HEADER_SIZE = 12
    val MAGIC = byteArrayOf('R'.code.toByte(), 'T'.code.toByte(), 'L'.code.toByte(), '0'.code.toByte())

    /** rtl_tcp command: 1-byte code + 4-byte big-endian parameter. */
    const val CMD_SET_GAIN_MODE = 0x03  // 0 = tuner AGC (auto), 1 = manual
    const val CMD_SET_GAIN      = 0x04  // tenths of a dB; only honoured in manual mode
    const val CMD_SET_BIAS_TEE  = 0x0e  // 0 = off, 1 = on (rtl-sdr's set_bias_tee)

    // Verbatim from librtlsdr rtlsdr_get_tuner_gains(); all values in tenths of a dB.
    private val E4000_GAINS = listOf(-10, 15, 40, 65, 90, 115, 140, 165, 190, 215, 240, 290, 340, 420)
    private val FC0012_GAINS = listOf(-99, -40, 71, 179, 192)
    private val FC0013_GAINS = listOf(
        -99, -73, -65, -63, -60, -58, -54, 58, 61, 63, 65, 67, 68, 70, 71,
        179, 181, 182, 184, 186, 188, 191, 197,
    )
    private val R82XX_GAINS = listOf(
        0, 9, 14, 27, 37, 77, 87, 125, 144, 157, 166, 197, 207, 229, 254,
        280, 297, 328, 338, 364, 372, 386, 402, 421, 434, 439, 445, 480, 496,
    )

    private val TABLES: Map<TunerType, List<Int>> = mapOf(
        TunerType.E4000 to E4000_GAINS,
        TunerType.FC0012 to FC0012_GAINS,
        TunerType.FC0013 to FC0013_GAINS,
        TunerType.R820T to R82XX_GAINS,
        TunerType.R828D to R82XX_GAINS,
    )

    /** Parses the 12-byte header. Returns null if it isn't a valid rtl_tcp greeting. */
    fun parseDongleInfo(header: ByteArray): DongleInfo? {
        if (header.size < HEADER_SIZE) return null
        for (i in MAGIC.indices) if (header[i] != MAGIC[i]) return null
        val tunerCode = beInt(header, 4)
        val gainCount = beInt(header, 8)
        return DongleInfo(TunerType.fromCode(tunerCode) ?: TunerType.UNKNOWN, gainCount)
    }

    fun gainsFor(info: DongleInfo): GainOptions {
        val table = TABLES[info.tuner]
            ?: return GainOptions.Unavailable(
                if (info.tuner == TunerType.FC2580 || info.tuner == TunerType.UNKNOWN)
                    "${info.tuner.displayName} tuner reports no selectable gain levels. Use Auto gain."
                else
                    "Unrecognised tuner type. Use Auto gain."
            )
        if (table.size != info.reportedGainCount) {
            return GainOptions.Unavailable(
                "Dongle reported ${info.reportedGainCount} gain levels but the known " +
                "${info.tuner.displayName} table has ${table.size}. Not applying an unknown " +
                "value — use Auto gain."
            )
        }
        return GainOptions.Available(info.tuner, table)
    }

    /** Encodes an rtl_tcp control command. */
    fun command(cmd: Int, param: Int): ByteArray = byteArrayOf(
        cmd.toByte(),
        (param ushr 24).toByte(), (param ushr 16).toByte(), (param ushr 8).toByte(), param.toByte(),
    )

    /** Formats tenths-of-a-dB for display, e.g. 496 -> "49.6 dB". */
    fun formatGain(tenths: Int): String = "${tenths / 10}.${Math.abs(tenths % 10)} dB"

    private fun beInt(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
        ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)
}
