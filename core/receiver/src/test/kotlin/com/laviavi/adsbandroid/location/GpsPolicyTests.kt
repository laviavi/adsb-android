package com.laviavi.adsbandroid.location

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class GpsPolicyTests {

    @Nested inner class ContinuousGpsGating {
        @Test fun `runs only when Follow GPS and source Running`() {
            assertTrue(GpsPolicy.shouldRunContinuousGps(ObserverMode.FOLLOW_GPS, sourceRunning = true))
        }
        @Test fun `does not run in Fixed mode even if Running`() {
            assertFalse(GpsPolicy.shouldRunContinuousGps(ObserverMode.FIXED, sourceRunning = true))
        }
        @Test fun `does not run in Follow GPS mode when source not Running`() {
            assertFalse(GpsPolicy.shouldRunContinuousGps(ObserverMode.FOLLOW_GPS, sourceRunning = false))
        }
        @Test fun `does not run when neither condition holds`() {
            assertFalse(GpsPolicy.shouldRunContinuousGps(ObserverMode.FIXED, sourceRunning = false))
        }
    }

    @Nested inner class RefixOnReconnect {
        @Test fun `genuine error followed by reconnect triggers a refix`() {
            assertTrue(GpsPolicy.shouldRefixOnReconnect(hadErrorSinceLastRunning = true, isRunning = true))
        }
        @Test fun `benign restart with no prior error does not trigger a refix`() {
            // e.g. user edited an unrelated setting - Running -> Connecting -> Running, no Error in between.
            assertFalse(GpsPolicy.shouldRefixOnReconnect(hadErrorSinceLastRunning = false, isRunning = true))
        }
        @Test fun `still mid-outage (not yet Running again) does not trigger a refix`() {
            assertFalse(GpsPolicy.shouldRefixOnReconnect(hadErrorSinceLastRunning = true, isRunning = false))
        }
    }

    @Nested inner class PeriodicRefix {
        @Test fun `disabled when interval is zero regardless of elapsed time`() {
            assertFalse(GpsPolicy.isPeriodicRefixDue(lastRefixMs = 0L, nowMs = 999_999_999L, intervalMinutes = 0))
        }
        @Test fun `not due before the interval elapses`() {
            val interval = 60
            val elapsed = (interval * 60_000L) - 1
            assertFalse(GpsPolicy.isPeriodicRefixDue(lastRefixMs = 0L, nowMs = elapsed, intervalMinutes = interval))
        }
        @Test fun `due once the interval elapses`() {
            val interval = 60
            val elapsed = interval * 60_000L
            assertTrue(GpsPolicy.isPeriodicRefixDue(lastRefixMs = 0L, nowMs = elapsed, intervalMinutes = interval))
        }
    }
}
