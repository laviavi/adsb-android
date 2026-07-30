package com.laviavi.adsbandroid.location

/** Interval and min-distance to use for the next batch of location update requests. */
data class ThrottleParams(val intervalMs: Long, val minDistanceMeters: Float)

/**
 * Motion-aware throttle for continuous GPS updates: the longer consecutive fixes show no
 * meaningful movement, the longer the interval gets (fewer radio wakeups while parked).
 * Any fix that moved far enough resets straight back to the most responsive tier.
 */
class GpsThrottlePolicy {
    private var lastLat: Double? = null
    private var lastLon: Double? = null
    private var stationaryStreak = 0

    /** Feed each accepted fix; returns the throttle parameters to use for the next request. */
    fun onFix(lat: Double, lon: Double): ThrottleParams {
        val prevLat = lastLat
        val prevLon = lastLon
        val moved = prevLat == null || prevLon == null ||
            haversineMeters(prevLat, prevLon, lat, lon) >= MOVEMENT_THRESHOLD_M
        stationaryStreak = if (moved) 0 else stationaryStreak + 1
        lastLat = lat
        lastLon = lon
        return tierFor(stationaryStreak)
    }

    /** Clear tracked history — call when GPS updates stop/restart (e.g. source disconnect). */
    fun reset() {
        lastLat = null
        lastLon = null
        stationaryStreak = 0
    }

    /** The tier that would apply right now, without consuming a fix — for starting a fresh request. */
    fun currentParams(): ThrottleParams = tierFor(stationaryStreak)

    companion object {
        const val MOVEMENT_THRESHOLD_M = 30.0

        // ponytail: tier thresholds/values are a reasonable heuristic schedule, not a
        // tuned one. Upgrade path if real-world battery/accuracy tradeoff needs shifting:
        // make the streak counts and ThrottleParams configurable instead of hardcoded.
        private val TIER_0_MOVING      = ThrottleParams(10_000L, 50f)
        private val TIER_1_STATIONARY  = ThrottleParams(60_000L, 75f)
        private val TIER_2_STATIONARY  = ThrottleParams(300_000L, 100f)
        private val TIER_3_STATIONARY  = ThrottleParams(900_000L, 150f)

        private fun tierFor(streak: Int): ThrottleParams = when {
            streak >= 6 -> TIER_3_STATIONARY
            streak >= 3 -> TIER_2_STATIONARY
            streak >= 1 -> TIER_1_STATIONARY
            else -> TIER_0_MOVING
        }

        private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = Math.sin(dLat / 2).let { it * it } +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2).let { it * it }
            return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        }
    }
}
