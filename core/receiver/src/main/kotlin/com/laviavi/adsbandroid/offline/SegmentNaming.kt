package com.laviavi.adsbandroid.offline

/**
 * Resolves a coordinate to a place name. Implemented by a platform geocoder in `:app`.
 *
 * Returning null is expected and handled — no network (offline mode, aeroplane mode,
 * or the Wi-Fi that downloads require simply not being up yet) means no geocode.
 */
interface LocationNamer {
    suspend fun nameFor(lat: Double, lon: Double): String?
}

/**
 * Names new segments after where they were made, keeping every name unique.
 *
 * Naming is separated from the manager because collision handling is fiddly, entirely
 * deterministic, and the single most likely thing to silently destroy data if it goes
 * wrong: a name that collides and overwrites is indistinguishable from a successful
 * download until the user looks for coverage that is no longer there.
 */
object SegmentNaming {

    /** Shown when geocoding fails, so a segment always has a usable label. */
    const val UNKNOWN_LOCATION = "Unknown location"

    /**
     * A unique display name derived from [locationName].
     *
     * First collision takes a counter suffix — `Riverside (2)` — because that is what
     * users expect and it stays short. Only if the counter itself somehow collides
     * does the date form appear, which is why [dateStamp] is passed in rather than
     * read from the clock: the fallback has to be reproducible in a test.
     */
    fun uniqueName(
        locationName: String,
        existingNames: Collection<String>,
        dateStamp: String? = null,
    ): String {
        val base = locationName.trim().ifEmpty { UNKNOWN_LOCATION }
        val taken = existingNames.toHashSet()
        if (base !in taken) return base

        // Start at 2: the unsuffixed name is conceptually the first.
        for (n in 2..999) {
            val candidate = "$base ($n)"
            if (candidate !in taken) return candidate
        }

        if (dateStamp != null) {
            val dated = "$base - $dateStamp"
            if (dated !in taken) return dated
            for (n in 2..999) {
                val candidate = "$base - $dateStamp ($n)"
                if (candidate !in taken) return candidate
            }
        }
        // Exhausting a thousand names of one place is not a real scenario, but
        // returning a duplicate would overwrite data, so fall through to something
        // guaranteed distinct rather than risk it.
        return "$base (${existingNames.size + 1})"
    }

    /**
     * Fallback label for a coordinate with no geocode.
     *
     * Deliberately the actual position rather than a bare "Unknown": a user with
     * three unnamed segments needs some way to tell them apart, and the coordinate
     * is the only distinguishing fact available.
     */
    fun coordinateLabel(lat: Double, lon: Double): String {
        val ns = if (lat >= 0) "N" else "S"
        val ew = if (lon >= 0) "E" else "W"
        return "%.2f%s %.2f%s".format(kotlin.math.abs(lat), ns, kotlin.math.abs(lon), ew)
    }
}
