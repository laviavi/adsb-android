package com.laviavi.adsbandroid.data

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Regression coverage for making FA's initial attempt timer-driven: a marginal
 * contact (weak signal, few messages) that goes quiet right after its first
 * message used to never cross the 5s delay, since the only thing that
 * re-checked the timing was a *new* message arriving — see
 * [FlightAwareEnrichment]'s class doc.
 */
class FaSchedulingTests {

    @Test fun `not due before the initial delay elapses`() {
        assertFalse(isFirstAttemptDue(firstSeenMs = 0L, nowMs = 4_999L, initialDelayMs = 5_000L))
    }

    @Test fun `due exactly at the initial delay`() {
        assertTrue(isFirstAttemptDue(firstSeenMs = 0L, nowMs = 5_000L, initialDelayMs = 5_000L))
    }

    @Test fun `due well after the initial delay, regardless of new messages`() {
        // The real-world case this exists for: no message arrived between 0 and
        // 5000ms to re-trigger a check, but the sweep still finds it due.
        assertTrue(isFirstAttemptDue(firstSeenMs = 0L, nowMs = 30_000L, initialDelayMs = 5_000L))
    }

    @Test fun `rate limiter allows up to the cap within one window`() {
        val limiter = FaRateLimiter(maxRequests = 2, windowMs = 1_000L)
        assertTrue(limiter.tryAcquire(0L))
        assertTrue(limiter.tryAcquire(100L))
        assertFalse(limiter.tryAcquire(200L), "a third request within the same second must be refused")
    }

    @Test fun `rate limiter frees up once the window slides past old requests`() {
        val limiter = FaRateLimiter(maxRequests = 2, windowMs = 1_000L)
        assertTrue(limiter.tryAcquire(0L))
        assertTrue(limiter.tryAcquire(100L))
        assertFalse(limiter.tryAcquire(200L))
        assertTrue(limiter.tryAcquire(1_001L), "the first request has aged out of the 1s window by now")
    }

    @Test fun `rate limiter is shared budget, not per-ident`() {
        val limiter = FaRateLimiter(maxRequests = 2, windowMs = 1_000L)
        assertTrue(limiter.tryAcquire(0L))   // ident A
        assertTrue(limiter.tryAcquire(50L))  // ident B
        assertFalse(limiter.tryAcquire(60L), "ident C must still be refused — the cap is global, not per-aircraft")
    }
}
