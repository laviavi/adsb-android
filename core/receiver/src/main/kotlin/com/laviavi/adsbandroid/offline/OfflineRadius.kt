package com.laviavi.adsbandroid.offline

/**
 * The only radii an offline download may use.
 *
 * A closed set rather than a free number because the estimate, the tile count and
 * the storage warning are all derived from it — an arbitrary radius would let a
 * user request a download whose size nobody has reasoned about. The three values
 * bracket real ADS-B reception: 90 NM is comfortably inside typical range, 250 NM
 * is about the physical line-of-sight ceiling.
 */
enum class OfflineRadius(val nauticalMiles: Int, val label: String) {
    NM_90(90, "90 NM"),
    NM_150(150, "150 NM"),
    NM_250(250, "250 NM");

    companion object {
        val DEFAULT = NM_150

        /** Null for anything not in the set — callers must not invent a radius. */
        fun fromNauticalMiles(nm: Int): OfflineRadius? = entries.firstOrNull { it.nauticalMiles == nm }

        fun isSupported(nm: Int): Boolean = fromNauticalMiles(nm) != null
    }
}

/**
 * How much zoom detail a download covers.
 *
 * The ceilings are not arbitrary: `MapScreen.computeZoom` only ever asks for
 * z8.4–z11.5 across the four range steps, so z12 is the highest level the renderer
 * can actually display and anything beyond it would be downloaded and never drawn.
 * Each level costs roughly 4x the previous one, which is why STANDARD stopping at
 * z11 is about a quarter the size of DETAILED.
 */
enum class MapDetail(val minZoom: Int, val maxZoom: Int, val label: String) {
    STANDARD(8, 11, "Standard"),
    DETAILED(8, 12, "Detailed");

    val zoomRange: IntRange get() = minZoom..maxZoom

    companion object {
        val DEFAULT = STANDARD
    }
}
