package com.laviavi.adsbandroid.data

import kotlinx.serialization.json.Json
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

/**
 * Regression coverage for moving off "first source wins": a source with only
 * a registration used to permanently hide a later source's owner, even
 * though nothing about the first source's result said the owner was
 * genuinely unknown — it just hadn't been asked.
 */
class MergeSourcesTests {

    @Test fun `a field missing from the first source is filled in by the second`() {
        val hexdb = AircraftMeta("C084A3", registration = "C-GYFY", manufacturer = null, model = null, typeCode = null, owner = null, source = "hexdb")
        val adsbdb = AircraftMeta("C084A3", registration = null, manufacturer = "Airbus", model = "A321", typeCode = "A321", owner = "Air Canada Rouge", source = "adsbdb")
        val merged = mergeSources("C084A3", hexdb, adsbdb)
        assertNotNull(merged)
        assertEquals("C-GYFY", merged!!.registration, "earlier source's field is kept")
        assertEquals("Air Canada Rouge", merged.owner, "later source fills in what the earlier one lacked")
        assertEquals("hexdb+adsbdb", merged.source)
    }

    @Test fun `the earlier source's value wins when both have the same field`() {
        val hexdb = AircraftMeta("C084A3", registration = "C-GYFY", manufacturer = null, model = null, typeCode = null, owner = null, source = "hexdb")
        val opensky = AircraftMeta("C084A3", registration = "WRONG-REG", manufacturer = null, model = null, typeCode = null, owner = null, source = "opensky")
        val merged = mergeSources("C084A3", hexdb, opensky)
        assertEquals("C-GYFY", merged!!.registration)
    }

    @Test fun `all sources null yields no result`() {
        assertNull(mergeSources("C084A3", null, null, null))
    }

    @Test fun `every source empty (all fields null) still yields no result`() {
        val empty = AircraftMeta("C084A3", null, null, null, null, null, "hexdb")
        assertNull(mergeSources("C084A3", empty, null))
    }
}

/**
 * Regression coverage for the actual bug: `mapHexdbResponse`/`mapAdsbdbFields` were always
 * correct and tested (above), but the app's real fetch path used Ktor's typed `.body<T>()`
 * deserialization, which threw "Serializer for class X is not found" on every real request —
 * confirmed via a live device export (ICAO C04205) showing two independent fresh (non-cached)
 * failures over 4 hours apart for an aircraft both hexdb.io and adsbdb definitely have data
 * for (verified live with curl). These tests exercise the actual decode step
 * (`metaJson.decodeFromString<T>()`, the fix) against real captured API responses — the class
 * of bug the pure-mapping-function tests above could never catch, since they never touch
 * deserialization at all.
 */
class MetaJsonDecodeTests {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun `hexdb C04205 (Helijet Sikorsky S-76A) decodes and maps correctly`() {
        // Captured live: hexdb.io/api/v1/aircraft/c04205.
        val raw = """{"ModeS": "C04205", "Registration": "C-FZAA", "Manufacturer": "Sikorsky",
            "ICAOTypeCode": "S76", "Type": "S-76 A", "RegisteredOwners": "Helijet", "OperatorFlagCode": "JBA"}"""
        val resp = json.decodeFromString<HexdbResponse>(raw)
        val meta = mapHexdbResponse("C04205", resp)
        assertNotNull(meta)
        assertEquals("C-FZAA", meta!!.registration)
        assertEquals("Sikorsky", meta.manufacturer)
        assertEquals("S76", meta.typeCode)
        assertEquals("Helijet", meta.owner)
    }

    @Test fun `adsbdb C04205 parses through the nested response-aircraft object correctly`() {
        // Captured live: api.adsbdb.com/v0/aircraft/c04205.
        val raw = """{"response":{"aircraft":{"type":"S-76 A","icao_type":"S76","manufacturer":"Sikorsky",
            "mode_s":"C04205","registration":"C-FZAA","registered_owner_country_iso_name":"CA",
            "registered_owner_country_name":"Canada","registered_owner_operator_flag_code":"JBA",
            "registered_owner":"Helijet","url_photo":null,"url_photo_thumbnail":null}}}"""
        val meta = parseAdsbdbAircraft("C04205", raw)
        assertNotNull(meta)
        assertEquals("C-FZAA", meta!!.registration)
        assertEquals("S76", meta.typeCode)
        assertEquals("Sikorsky", meta.manufacturer)
    }

    @Test fun `adsbdb unknown-aircraft response (string, not object) is no data, not a crash`() {
        // Captured live: api.adsbdb.com/v0/aircraft/cf3bf5 -- same string-response shape as the
        // callsign endpoint's "unknown callsign" case, now handled the same defensive way.
        assertNull(parseAdsbdbAircraft("CF3BF5", """{"response":"unknown aircraft"}"""))
    }

    @Test fun `malformed JSON is no data, not a crash`() {
        assertNull(parseAdsbdbAircraft("C04205", "not json at all"))
    }
}
