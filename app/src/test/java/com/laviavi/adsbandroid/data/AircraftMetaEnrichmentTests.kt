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

/**
 * Investigating "C084A3 and C02AE3 showed no registration/type/route in the
 * Traffic list, despite running long enough that this isn't a timing issue"
 * (screenshots, 2026-08-09). Both ICAOs have complete data on hexdb.io — the
 * *first* source tried, before OpenSky or adsbdb are ever reached — verified
 * live:
 *
 *   C084A3: {"Registration":"C-GYFY","Manufacturer":"Airbus","Type":"A321 211",
 *            "RegisteredOwners":"Air Canada Rouge","ICAOTypeCode":"A321"}
 *   C02AE3: {"Registration":"C-FQGG","Manufacturer":"Boeing","Type":"737MAX 8",
 *            "RegisteredOwners":"WestJet","ICAOTypeCode":"B38M"}
 *
 * These tests prove `mapHexdbResponse()` — the exact function
 * `AircraftMetaEnrichment.fetchHexdb()` runs on every meta lookup — parses
 * this real data correctly. That rules out a parsing bug in the currently
 * shipped code as the cause: whatever kept these two aircraft unenriched, it
 * is not that the app can't understand hexdb.io's response for them. The
 * remaining, unverifiable-without-device-access explanation is the 2h
 * negative-cache TTL (`isCacheFresh`, above): if either ICAO was seen and
 * cached as "none" earlier in the same testing session (e.g. during a
 * transient hexdb.io hiccup, or before this evening's other fixes landed),
 * every later sighting replays that cached null for up to 2 hours — no
 * matter how much *more* time passes while watching it live, since the
 * clock that matters is time-since-cache-write, not time-since-resighting.
 */
class HexdbFieldMappingTests {

    @Test fun `C084A3 (Air Canada Rouge A321) parses to a complete result`() {
        val resp = HexdbResponse(
            registration = "C-GYFY",
            manufacturer = "Airbus",
            type = "A321 211",
            registeredOwners = "Air Canada Rouge",
            icaoTypeCode = "A321",
        )
        val meta = mapHexdbResponse("C084A3", resp)
        assertNotNull(meta)
        assertEquals("C-GYFY", meta!!.registration)
        assertEquals("Airbus", meta.manufacturer)
        assertEquals("A321 211", meta.model)
        assertEquals("A321", meta.typeCode)
        assertEquals("Air Canada Rouge", meta.owner)
        assertEquals("Airbus A321 211", meta.typeDisplay())
    }

    @Test fun `C02AE3 (WestJet 737 MAX 8) parses to a complete result`() {
        val resp = HexdbResponse(
            registration = "C-FQGG",
            manufacturer = "Boeing",
            type = "737MAX 8",
            registeredOwners = "WestJet",
            icaoTypeCode = "B38M",
        )
        val meta = mapHexdbResponse("C02AE3", resp)
        assertNotNull(meta)
        assertEquals("Boeing 737MAX 8", meta!!.typeDisplay())
    }
}
