package com.laviavi.adsbandroid.capture

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Covers the backoff schedule only. Reconnection itself is not testable here and
 * is not claimed to be: whether the receiver recovers depends on the driver app,
 * the USB stack and the socket, none of which exist in a JVM test. The schedule
 * being correct is necessary, not sufficient.
 */
class ReconnectPolicyTests {

    @Test fun `first attempt waits the initial delay`() {
        assertEquals(ReconnectPolicy.INITIAL_DELAY_MS, ReconnectPolicy.delayForAttempt(0))
    }

    @Test fun `delay doubles per attempt`() {
        assertEquals(2_000L, ReconnectPolicy.delayForAttempt(1))
        assertEquals(4_000L, ReconnectPolicy.delayForAttempt(2))
        assertEquals(8_000L, ReconnectPolicy.delayForAttempt(3))
        assertEquals(16_000L, ReconnectPolicy.delayForAttempt(4))
    }

    @Test fun `delay is capped so a long outage never opens a multi-minute blind spot`() {
        assertEquals(ReconnectPolicy.MAX_DELAY_MS, ReconnectPolicy.delayForAttempt(5))
        assertEquals(ReconnectPolicy.MAX_DELAY_MS, ReconnectPolicy.delayForAttempt(50))
    }

    @Test fun `a very high attempt count never overflows into a negative delay`() {
        // Left-shifting past 62 wraps; a negative delay would make delay() return
        // immediately and turn the backoff into a hot loop against the driver app.
        listOf(61, 62, 63, 64, 1_000, Int.MAX_VALUE).forEach { attempt ->
            val d = ReconnectPolicy.delayForAttempt(attempt)
            assertTrue(d > 0, "attempt $attempt produced a non-positive delay: $d")
            assertEquals(ReconnectPolicy.MAX_DELAY_MS, d, "attempt $attempt should be capped")
        }
    }

    @Test fun `negative attempts are treated as the first attempt`() {
        assertEquals(ReconnectPolicy.INITIAL_DELAY_MS, ReconnectPolicy.delayForAttempt(-1))
    }

    @Test fun `every delay stays within the declared bounds`() {
        (0..100).forEach { attempt ->
            val d = ReconnectPolicy.delayForAttempt(attempt)
            assertTrue(d >= ReconnectPolicy.INITIAL_DELAY_MS, "attempt $attempt below floor: $d")
            assertTrue(d <= ReconnectPolicy.MAX_DELAY_MS, "attempt $attempt above cap: $d")
        }
    }
}
