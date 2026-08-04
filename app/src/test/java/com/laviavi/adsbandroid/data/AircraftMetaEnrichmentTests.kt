package com.laviavi.adsbandroid.data

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Regression coverage for the 30-day -> 2-hour negative-cache TTL fix.
 *
 * Real evidence behind this fix: ICAO C05737 (a Canadian Harbour Air DHC-3
 * Turbo Otter, registration C-GHAS) got its meta lookup cached as "none" while
 * hexdb.io was returning 504 and OpenSky's metadata endpoint was down (410
 * Gone, permanently retired) — even though adsbdb has full data for it right
 * now (verified live: registration C-GHAS, type DHC-3 Otter (Turbo), operator
 * Harbour Air). Under the old 30-day TTL that negative result would have
 * blocked a retry for a month; the fix forces a retry within 2 hours instead.
 */
class AircraftMetaEnrichmentTests {

    @Test fun `a lookup cached less than 2 hours ago is still fresh`() {
        val cachedAt = 0L
        val oneHourLater = cachedAt + 3_600_000L
        assertTrue(isCacheFresh(cachedAt, oneHourLater))
    }

    @Test fun `exactly at the 2-hour boundary is no longer fresh`() {
        val cachedAt = 0L
        val exactlyTwoHours = cachedAt + 2 * 3_600_000L
        assertFalse(isCacheFresh(cachedAt, exactlyTwoHours))
    }

    @Test fun `a lookup cached 3 hours ago (C05737's own timeline) is stale and must retry`() {
        val cachedAt = 0L
        val threeHoursLater = cachedAt + 3 * 3_600_000L
        assertFalse(isCacheFresh(cachedAt, threeHoursLater))
    }

    @Test fun `the old 30-day rule would have wrongly kept that same 3-hour-old failure cached`() {
        val cachedAt = 0L
        val threeHoursLater = cachedAt + 3 * 3_600_000L
        val oldThirtyDayTtlMs = 30L * 24 * 3_600_000L
        // Sanity: 3 hours easily fits inside the old 30-day window, confirming
        // the old constant really would have blocked a same-day retry.
        assertTrue(isCacheFresh(cachedAt, threeHoursLater, ttlMs = oldThirtyDayTtlMs))
        // The fixed default (2h) correctly forces a retry at the same instant.
        assertFalse(isCacheFresh(cachedAt, threeHoursLater))
    }
}
