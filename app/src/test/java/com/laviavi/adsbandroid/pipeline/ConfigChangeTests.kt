package com.laviavi.adsbandroid.pipeline

import com.laviavi.adsbandroid.aircraft.AircraftSortOrder
import com.laviavi.adsbandroid.location.ObserverMode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Guards the promise that opening Settings and changing an unrelated value does
 * not drop a live receiver session.
 */
class ConfigChangeTests {

    private val base = AppConfig()

    @Nested inner class RequiresRestart {

        @Test fun `identical config never restarts`() {
            assertFalse(ConfigChange.requiresPipelineRestart(base, base.copy()))
        }

        @Test fun `PPM restarts`() {
            assertTrue(ConfigChange.requiresPipelineRestart(base, base.copy(ppmCorrection = 5)))
        }

        @Test fun `switching auto-manual gain mode restarts`() {
            assertTrue(ConfigChange.requiresPipelineRestart(base, base.copy(autoGain = false)))
        }

        @Test fun `picking a different level within the same gain mode does not restart`() {
            // Only the mode switch restarts; a level change goes out live instead.
            val manual = base.copy(autoGain = false, gainTenths = 300)
            assertFalse(ConfigChange.requiresPipelineRestart(manual, manual.copy(gainTenths = 421)))
        }

        @Test fun `demodulator tuning does not restart`() {
            // Retuning goes straight into the running demodulator; a reconnect
            // would drop the session the user is watching to judge the change.
            assertFalse(ConfigChange.requiresPipelineRestart(base, base.copy(preambleGapDivisor = 9)))
            assertFalse(ConfigChange.requiresPipelineRestart(base, base.copy(deltaFloor = 1020)))
        }

        @Test fun `observer, logging and watchdog changes do not restart`() {
            assertFalse(ConfigChange.requiresPipelineRestart(base, base.copy(observerMode = ObserverMode.FOLLOW_GPS)))
            assertFalse(ConfigChange.requiresPipelineRestart(base, base.copy(observerLatitude = 51.5)))
            assertFalse(ConfigChange.requiresPipelineRestart(base, base.copy(gpsRefreshIntervalMinutes = 15)))
            assertFalse(ConfigChange.requiresPipelineRestart(base, base.copy(sourceWatchdogTimeoutMinutes = 30)))
            assertFalse(ConfigChange.requiresPipelineRestart(base, base.copy(rawLoggingEnabled = true)))
            assertFalse(ConfigChange.requiresPipelineRestart(base, base.copy(enrichmentEnabled = true)))
            assertFalse(ConfigChange.requiresPipelineRestart(base, base.copy(crcCorrectSingleBit = false)))
            assertFalse(ConfigChange.requiresPipelineRestart(base, base.copy(aircraftExpirySeconds = 120)))
        }

        @Test fun `sort order does not restart`() {
            // Sort is a presentation choice applied at emission time — see
            // PipelineService.publishAircraft — it never touches the pipeline.
            AircraftSortOrder.entries.forEach {
                assertFalse(ConfigChange.requiresPipelineRestart(base, base.copy(sortOrder = it)))
            }
        }
    }

    @Nested inner class RequiresGainReapply {

        @Test fun `switching auto-manual mode does not live-reapply — it restarts instead`() {
            assertFalse(ConfigChange.requiresGainReapply(base, base.copy(autoGain = false)))
        }

        @Test fun `picking a different level within the same mode reapplies live`() {
            val manual = base.copy(autoGain = false, gainTenths = 300)
            assertTrue(ConfigChange.requiresGainReapply(manual, manual.copy(gainTenths = 421)))
        }

        @Test fun `unrelated change does not reapply gain`() {
            assertFalse(ConfigChange.requiresGainReapply(base, base.copy(observerLatitude = 10.0)))
        }
    }

    @Nested inner class RequiresBiasTeeReapply {

        @Test fun `toggling bias tee reapplies`() {
            assertTrue(ConfigChange.requiresBiasTeeReapply(base, base.copy(biasTee = true)))
        }

        @Test fun `unrelated change does not reapply bias tee`() {
            assertFalse(ConfigChange.requiresBiasTeeReapply(base, base.copy(observerLatitude = 10.0)))
        }

        @Test fun `bias tee does not restart the pipeline`() {
            assertFalse(ConfigChange.requiresPipelineRestart(base, base.copy(biasTee = true)))
        }
    }

    @Nested inner class RequiresDemodRetune {

        @Test fun `changing either threshold retunes`() {
            assertTrue(ConfigChange.requiresDemodRetune(base, base.copy(preambleGapDivisor = 9)))
            assertTrue(ConfigChange.requiresDemodRetune(base, base.copy(deltaFloor = 1020)))
        }

        @Test fun `unrelated change does not retune`() {
            assertFalse(ConfigChange.requiresDemodRetune(base, base.copy(observerLatitude = 10.0)))
        }
    }

    @Nested inner class Defaults {

        @Test fun `auto gain is the default`() {
            assertTrue(base.autoGain, "Auto gain avoids shipping an arbitrary fixed gain")
        }

        @Test fun `watchdog defaults to five minutes`() {
            assertEquals(5, base.sourceWatchdogTimeoutMinutes)
        }

        @Test fun `demodulator defaults match the demodulator's own`() {
            // These are the values the noise floor and preamble check were
            // calibrated at. A mismatch means the UI would show a non-default
            // value as if it were the baseline.
            assertEquals(6, base.preambleGapDivisor)
            assertEquals(2550, base.deltaFloor)
        }

        @Test fun `manual gain starts unset rather than at a real level`() {
            // 0 is the R82xx minimum-gain step, so defaulting to it would pin the
            // tuner to its least sensitive setting without the user asking.
            assertEquals(AppConfig.GAIN_UNSET, base.gainTenths)
            assertNotEquals(0, base.gainTenths)
        }

        @Test fun `sort defaults to first-seen, the only order that does not reshuffle rows`() {
            assertEquals(AircraftSortOrder.FIRST_SEEN, base.sortOrder)
        }

        @Test fun `bias tee defaults off`() {
            // Powered wrong, a bias tee can damage an unpowered antenna — never default it on.
            assertFalse(base.biasTee)
        }
    }
}
