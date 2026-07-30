package com.laviavi.adsbandroid.pipeline

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SourceWatchdogTests {

    @Test fun `watchdog expiry - stops after the timeout with no activity`() = runTest {
        var expired = false
        val watchdog = SourceWatchdog(this) { expired = true }
        watchdog.onStateChanged(active = false, timeoutMs = 5_000L)
        advanceTimeBy(5_001L)
        runCurrent()
        assertTrue(expired)
    }

    @Test fun `does not expire before the timeout`() = runTest {
        var expired = false
        val watchdog = SourceWatchdog(this) { expired = true }
        watchdog.onStateChanged(active = false, timeoutMs = 5_000L)
        advanceTimeBy(4_000L)
        runCurrent()
        assertFalse(expired)
    }

    @Test fun `watchdog reset on reconnection - a transient reconnect before the timeout cancels it`() = runTest {
        var expired = false
        val watchdog = SourceWatchdog(this) { expired = true }
        watchdog.onStateChanged(active = false, timeoutMs = 5_000L)
        advanceTimeBy(2_000L)
        watchdog.onStateChanged(active = true, timeoutMs = 5_000L) // reconnected
        advanceTimeBy(10_000L)
        runCurrent()
        assertFalse(expired, "A transient reconnect that recovers before the timeout must not shut down")
    }

    @Test fun `state churn while still inactive does not restart the deadline`() = runTest {
        var expired = false
        val watchdog = SourceWatchdog(this) { expired = true }
        watchdog.onStateChanged(active = false, timeoutMs = 5_000L)
        advanceTimeBy(3_000L)
        watchdog.onStateChanged(active = false, timeoutMs = 5_000L) // still inactive - must not push the deadline out
        advanceTimeBy(2_001L) // total 5001ms since the FIRST inactive report
        runCurrent()
        assertTrue(expired)
    }

    @Test fun `timeout of zero disables the watchdog`() = runTest {
        var expired = false
        val watchdog = SourceWatchdog(this) { expired = true }
        watchdog.onStateChanged(active = false, timeoutMs = 0L)
        advanceTimeBy(1_000_000L)
        runCurrent()
        assertFalse(expired)
    }
}
