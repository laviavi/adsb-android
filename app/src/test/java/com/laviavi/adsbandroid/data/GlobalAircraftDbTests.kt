package com.laviavi.adsbandroid.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Regression coverage for `parseGlobalAcDbLine` against real lines captured live
 * from ADS-B Exchange's basic-ac-db.json.gz (the source this app's meta enrichment
 * now checks before hexdb/adsbdb). Both fixtures below matched this app's own
 * previously-confirmed data for the same ICAOs (hexdb.io/adsbdb for C04205,
 * live enrichment log for C0809E) — the whole reason this source was adopted.
 */
class GlobalAircraftDbTests {

    @Test fun `C04205 (Helijet Sikorsky S-76A) parses with nulls preserved where the source has them`() {
        val line = """{"icao":"c04205","reg":"C-FZAA","icaotype":"S76","year":null,"manufacturer":null,"model":"Sikorsky S-76-A","ownop":null,"faa_pia":false,"faa_ladd":false,"short_type":"H2T","mil":false}"""
        val entity = parseGlobalAcDbLine(line)
        assertEquals("C04205", entity!!.icao, "icao is upper-cased for the primary key")
        assertEquals("C-FZAA", entity.registration)
        assertEquals("S76", entity.typeCode)
        assertEquals("Sikorsky S-76-A", entity.model)
        assertNull(entity.manufacturer, "source has manufacturer=null for this row — must stay null, not \"null\" or empty string")
        assertNull(entity.owner)
        assertFalse(entity.military)
    }

    @Test fun `C0809E (WestJet 737 MAX 8) parses every field when the source has them all`() {
        val line = """{"icao":"c0809e","reg":"C-GWSJ","icaotype":"B38M","year":"2026","manufacturer":"BOEING","model":"737 MAX 8","ownop":"WESTJET","faa_pia":false,"faa_ladd":false,"short_type":"L2J","mil":false}"""
        val entity = parseGlobalAcDbLine(line)
        assertEquals("C0809E", entity!!.icao)
        assertEquals("C-GWSJ", entity.registration)
        assertEquals("B38M", entity.typeCode)
        assertEquals("BOEING", entity.manufacturer)
        assertEquals("737 MAX 8", entity.model)
        assertEquals("WESTJET", entity.owner)
    }

    @Test fun `a row with only registration and owner leaves the rest null, not empty strings`() {
        // Real shape for a US GA aircraft in the file: no type/manufacturer/model, owner present.
        val line = """{"icao":"a03e9c","reg":"N11484","icaotype":null,"year":null,"manufacturer":null,"model":null,"ownop":"ANTHONY MOROZOWSKY","faa_pia":false,"faa_ladd":false,"short_type":null,"mil":false}"""
        val entity = parseGlobalAcDbLine(line)
        assertEquals("N11484", entity!!.registration)
        assertEquals("ANTHONY MOROZOWSKY", entity.owner)
        assertNull(entity.typeCode)
        assertNull(entity.manufacturer)
        assertNull(entity.model)
    }

    @Test fun `military flag is carried through`() {
        val line = """{"icao":"ae1234","reg":null,"icaotype":"F16","manufacturer":"Lockheed","model":null,"ownop":null,"mil":true}"""
        val entity = parseGlobalAcDbLine(line)
        assertTrue(entity!!.military)
    }

    @Test fun `malformed line is skipped, not a crash`() {
        assertNull(parseGlobalAcDbLine("not json at all"))
        assertNull(parseGlobalAcDbLine(""))
        assertNull(parseGlobalAcDbLine("""{"reg":"N12345"}""")) // missing required icao field
    }
}
