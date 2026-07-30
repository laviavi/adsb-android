package com.laviavi.adsbandroid.capture

/**
 * Backoff schedule for reopening the USB source after the stream dies.
 *
 * Recovery deliberately does not depend on detecting a reconnect. The driver
 * app's attach broadcast is a third-party signal this app cannot rely on (its
 * own receiver documents the detach half as "not always fired", and a
 * non-exported receiver does not see another package's implicit broadcasts), so
 * an app that only restarts when told to restart stays dead whenever that signal
 * is missed. Retrying on a timer needs no signal at all: when the dongle comes
 * back, the next attempt simply succeeds.
 *
 * Pure so the schedule is testable without a device — though passing tests here
 * say nothing about whether reconnection works, which only unplugging can show.
 */
object ReconnectPolicy {

    const val INITIAL_DELAY_MS = 1_000L

    /**
     * Ceiling on the backoff. Capped rather than unbounded because the dongle can
     * come back at any moment and an exponential curve would otherwise grow into
     * multi-minute blind spots after a long absence.
     */
    const val MAX_DELAY_MS = 30_000L

    /** Gap between USB presence checks while waiting for the dongle to reappear. */
    const val PRESENCE_POLL_MS = 1_000L

    /**
     * Doubling backoff, capped at [MAX_DELAY_MS]. [attempt] is 0-based; a
     * successful open resets it, so a single glitch never inherits the delay
     * earned by an earlier outage.
     */
    fun delayForAttempt(attempt: Int): Long {
        if (attempt <= 0) return INITIAL_DELAY_MS
        // Shifting past 62 would overflow into a negative delay.
        if (attempt >= 62) return MAX_DELAY_MS
        val scaled = INITIAL_DELAY_MS shl attempt
        return if (scaled <= 0L || scaled > MAX_DELAY_MS) MAX_DELAY_MS else scaled
    }
}
