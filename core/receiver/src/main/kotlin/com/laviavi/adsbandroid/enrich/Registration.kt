package com.laviavi.adsbandroid.enrich

/**
 * Derive an aircraft registration from its ICAO 24-bit address with no network call.
 *
 * Port of the Python receiver's `enrich/registration.py`. Supports US civil
 * aircraft (ICAO block A00001–ADF7C7) only; every other block returns null.
 *
 * The FAA assigns N-numbers as a depth-first traversal of a trie over all valid
 * N-numbers, so the mapping is deterministic and invertible — which makes this
 * the only enrichment source that is free, instant, offline and exact.
 *
 * N-number format: N + 1–5 digits + 0–2 suffix letters. Suffix letters use the
 * 24-letter FAA alphabet (A–Z minus I and O, which are excluded because they are
 * easily confused with 1 and 0).
 */
object Registration {

    private const val US_BASE = 0xA00000
    private const val US_MIN = 0xA00001
    private const val US_MAX = 0xADF7C7   // N99999

    private const val LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ"
    private const val L = 24

    /** Suffix slots belonging to a stem itself, indexed by digit count. */
    private val OWN_SLOTS = intArrayOf(0, 601, 601, 601, 25, 1)

    /**
     * Total addresses consumed by a stem of N digits including all descendants.
     * Built bottom-up: a 5-digit stem is a leaf; every shorter stem owns its own
     * suffix slots plus ten child subtrees.
     */
    private val BLOCK = IntArray(7).apply {
        this[5] = 1
        this[4] = 25 + 10 * this[5]     //     35
        this[3] = 601 + 10 * this[4]    //    951
        this[2] = 601 + 10 * this[3]    //  10111
        this[1] = 601 + 10 * this[2]    // 101711
    }

    private fun slotToSuffix(slot: Int, digitCount: Int): String {
        if (slot == 0) return ""
        val s = slot - 1
        if (digitCount > 3) return LETTERS[s].toString()
        val block = 1 + L
        val first = LETTERS[s / block]
        val pos = s % block
        return if (pos == 0) first.toString() else "$first${LETTERS[pos - 1]}"
    }

    private fun suffixToSlot(suffix: String, digitCount: Int): Int {
        if (suffix.isEmpty()) return 0
        if (digitCount > 3) return 1 + LETTERS.indexOf(suffix[0])
        val block = 1 + L
        val first = LETTERS.indexOf(suffix[0])
        return if (suffix.length == 1) 1 + first * block
        else 1 + first * block + LETTERS.indexOf(suffix[1]) + 1
    }

    /**
     * Convert a 6-character hex ICAO address to its US registration, or null if
     * the address falls outside the US civil block.
     */
    fun fromIcao(icaoHex: String): String? {
        val value = icaoHex.trim().toIntOrNull(16) ?: return null
        return fromIcao(value)
    }

    fun fromIcao(icao: Int): String? {
        if (icao < US_MIN || icao > US_MAX) return null

        var offset = icao - US_BASE
        val digits = StringBuilder()

        // First digit is 1–9; each consumes a full depth-1 block.
        val d1 = (offset - 1) / BLOCK[1]
        offset -= d1 * BLOCK[1]
        digits.append(d1 + 1)

        // Remaining digits are 0–9. Stop as soon as the offset lands inside the
        // current stem's own suffix slots rather than in a child subtree.
        for (depth in 2..5) {
            val own = OWN_SLOTS[depth - 1]
            if (offset <= own) break
            offset -= own
            val child = BLOCK[depth]
            val dc = (offset - 1) / child
            offset -= dc * child
            digits.append(dc)
        }

        val number = digits.toString()
        return "N$number${slotToSuffix(offset - 1, number.length)}"
    }

    /** Convert a US N-number back to its 6-character hex ICAO address, or null. */
    fun toIcao(registration: String): String? {
        val reg = registration.trim().uppercase()
        if (!reg.startsWith("N")) return null
        val body = reg.substring(1)

        val numStr = body.takeWhile { it.isDigit() }
        val suffix = body.substring(numStr.length)
        if (numStr.isEmpty()) return null

        val number = numStr.toIntOrNull() ?: return null
        if (number < 1 || number > 99999) return null
        val numS = number.toString()
        val digitCount = numS.length

        if (suffix.any { it !in LETTERS }) return null
        var maxSuffix = if (digitCount >= 3) maxOf(0, 2 - (digitCount - 3)) else 2
        maxSuffix = minOf(maxSuffix, 5 - digitCount)
        if (suffix.length > maxSuffix) return null

        var offset = (numS[0].digitToInt() - 1) * BLOCK[1]
        for (i in 1 until numS.length) {
            val depth = i + 1
            offset += OWN_SLOTS[depth - 1]            // skip the parent's own slots
            offset += numS[i].digitToInt() * BLOCK[depth]   // skip earlier siblings
        }
        offset += suffixToSlot(suffix, digitCount) + 1

        val icao = US_BASE + offset
        if (icao > US_MAX) return null
        return "%06X".format(icao)
    }
}
