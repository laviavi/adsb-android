package com.laviavi.adsbandroid.decoder

data class RawFrame(
    val bytes: IntArray,
    val signalLevel: Double = 0.0,
    val clockCount: Long = 0L,
    /** Sample index within the magnitude buffer where this frame's preamble starts. */
    val sampleOffset: Int = 0,
) {
    val downlinkFormat: Int get() = bytes[0] ushr 3
    val isLong: Boolean get() = bytes.size == 14

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RawFrame) return false
        return bytes.contentEquals(other.bytes) && signalLevel == other.signalLevel &&
            clockCount == other.clockCount && sampleOffset == other.sampleOffset
    }
    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + signalLevel.hashCode()
        result = 31 * result + clockCount.hashCode()
        result = 31 * result + sampleOffset
        return result
    }
}
