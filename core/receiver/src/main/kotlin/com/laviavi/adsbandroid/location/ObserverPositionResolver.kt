package com.laviavi.adsbandroid.location

/**
 * Resolves which lat/lon the decoder/aircraft manager should use: the persisted fixed
 * position, or a live GPS fix. Never reports a fix that hasn't actually been received
 * this run — [resolve] falls back to the fixed coordinates until [applyLiveFix] is called,
 * and again after [clearLiveFix] (e.g. on source disconnect, where a pre-outage fix is no
 * longer trusted).
 */
class ObserverPositionResolver {
    private var liveLat: Double? = null
    private var liveLon: Double? = null

    fun applyLiveFix(lat: Double, lon: Double) {
        liveLat = lat
        liveLon = lon
    }

    fun clearLiveFix() {
        liveLat = null
        liveLon = null
    }

    fun resolve(mode: ObserverMode, fixedLat: Double, fixedLon: Double): Pair<Double, Double> {
        val lat = liveLat
        val lon = liveLon
        return if (mode == ObserverMode.FOLLOW_GPS && lat != null && lon != null) lat to lon
        else fixedLat to fixedLon
    }
}
