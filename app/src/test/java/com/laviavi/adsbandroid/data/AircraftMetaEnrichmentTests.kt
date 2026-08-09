package com.laviavi.adsbandroid.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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

/**
 * Regression coverage for the adsbdb field-mapping bug: `AdsbdbAircraftFields`
 * used to read the ICAO type designator from a field named `registerType`,
 * which doesn't exist anywhere in adsbdb's real response — it always
 * deserialized to null, so `model` was always null and `typeDisplay()` could
 * never show "$manufacturer $model" for an adsbdb-sourced aircraft.
 *
 * Fixture is ICAO C066C4's real live response (verified 2026-08-09): a
 * Harbour Air Cessna 172M, registration C-GMXV — adsbdb's actual JSON has
 * `"type":"172M"` (the free-text model) and `"icao_type":"C172"` (the real
 * ICAO designator), not a `registerType` field at all.
 */
class AdsbdbFieldMappingTests {

    @Test fun `type code comes from icao_type, not the free-text type field`() {
        val ac = AdsbdbAircraftFields(
            registration = "CA-GMXV",
            type = "172M",
            manufacturer = "Cessna",
            icaoType = "C172",
        )
        val meta = mapAdsbdbFields("C066C4", ac)
        assertNotNull(meta)
        assertEquals("C172", meta!!.typeCode, "typeCode must be the real ICAO designator")
        assertEquals("172M", meta.model, "model must be the free-text type string")
        assertEquals("Cessna", meta.manufacturer)
    }

    @Test fun `manufacturer and model combine once model is no longer stuck null`() {
        val ac = AdsbdbAircraftFields(type = "172M", manufacturer = "Cessna", icaoType = "C172")
        val meta = mapAdsbdbFields("C066C4", ac)
        assertEquals("Cessna 172M", meta!!.typeDisplay())
    }

    @Test fun `all fields absent yields no result`() {
        assertNull(mapAdsbdbFields("C066C4", AdsbdbAircraftFields()))
    }
}
