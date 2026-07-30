package com.laviavi.adsbandroid.location

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GpsThrottlePolicyTests {

    // A point ~34.05,-117.30; a "nearby" point < 30m away; a "far" point > 30m away.
    private val baseLat = 34.05
    private val baseLon = -117.30
    private val nearbyLat = 34.050001 // ~0.1m north - well under the 30m threshold
    private val farLat = 34.0505      // ~55m north - clears the 30m threshold

    @Test fun `first fix is always tier 0`() {
        val policy = GpsThrottlePolicy()
        val params = policy.onFix(baseLat, baseLon)
        assertEquals(10_000L, params.intervalMs)
    }

    @Test fun `stationary throttling escalates through tiers`() {
        val policy = GpsThrottlePolicy()
        policy.onFix(baseLat, baseLon) // tier 0 (first fix)
        val tier1 = policy.onFix(nearbyLat, baseLon) // 1st stationary repeat
        assertEquals(60_000L, tier1.intervalMs)

        policy.onFix(nearbyLat, baseLon) // 2nd stationary repeat
        val tier2 = policy.onFix(nearbyLat, baseLon) // 3rd stationary repeat
        assertEquals(300_000L, tier2.intervalMs)

        repeat(3) { policy.onFix(nearbyLat, baseLon) } // streak -> 6
        val tier3 = policy.onFix(nearbyLat, baseLon)
        assertEquals(900_000L, tier3.intervalMs)
    }

    @Test fun `movement resuming resets to the most responsive tier`() {
        val policy = GpsThrottlePolicy()
        policy.onFix(baseLat, baseLon)
        repeat(6) { policy.onFix(nearbyLat, baseLon) } // escalate to tier 3
        assertEquals(900_000L, policy.currentParams().intervalMs)

        val afterMove = policy.onFix(farLat, baseLon) // meaningful movement
        assertEquals(10_000L, afterMove.intervalMs, "Movement should reset to tier 0, not stay throttled")
    }

    @Test fun `reset clears streak back to tier 0`() {
        val policy = GpsThrottlePolicy()
        policy.onFix(baseLat, baseLon)
        repeat(3) { policy.onFix(nearbyLat, baseLon) }
        policy.reset()
        assertEquals(10_000L, policy.currentParams().intervalMs)
    }
}
