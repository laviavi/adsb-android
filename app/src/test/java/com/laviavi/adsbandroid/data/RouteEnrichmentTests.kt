package com.laviavi.adsbandroid.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Regression coverage for the adsbdb route-lookup bug found via a real exported
 * enrichment log: every fresh (non-cached) `adsbdb-route` attempt failed with
 * "Serializer for class 'AdsbdbCallsignResponse' is not found." Root cause,
 * confirmed live against api.adsbdb.com: adsbdb's `response` field is an OBJECT
 * for a recognized callsign but a plain STRING ("unknown callsign", "invalid
 * callsign: X") for one it isn't — a fixed object-shaped `@Serializable` class
 * can't decode both shapes, so every miss (i.e. most callsigns) threw instead of
 * being treated as "no route." `parseAdsbdbRoute` replaces that with manual
 * JsonElement parsing that tolerates either shape.
 */
class RouteEnrichmentTests {

    @Test fun `real AAL123 response (object shape) parses to origin-destination`() {
        // Captured live from api.adsbdb.com/v0/callsign/AAL123.
        val json = """
            {"response":{"flightroute":{"callsign":"AAL123","callsign_icao":"AAL123","callsign_iata":"AA123",
            "airline":{"name":"American Airlines","icao":"AAL","iata":"AA","country":"United States","country_iso":"US","callsign":"AMERICAN"},
            "origin":{"country_iso_name":"US","country_name":"United States","elevation":607,"iata_code":"DFW","icao_code":"KDFW",
            "latitude":32.896801,"longitude":-97.038002,"municipality":"Dallas-Fort Worth","name":"Dallas Fort Worth International Airport"},
            "destination":{"country_iso_name":"US","country_name":"United States","elevation":54,"iata_code":"OGG","icao_code":"PHOG",
            "latitude":20.8986,"longitude":-156.429993,"municipality":"Kahului","name":"Kahului Airport"}}}}
        """.trimIndent()
        assertEquals("KDFW-PHOG", parseAdsbdbRoute(json))
    }

    @Test fun `unrecognized callsign (string response, C02129's real case) is no route, not a crash`() {
        // Captured live: api.adsbdb.com/v0/callsign/C02129 -- an ICAO hex tried as a callsign.
        assertNull(parseAdsbdbRoute("""{"response":"unknown callsign"}"""))
    }

    @Test fun `invalid callsign (string response, different wording) is also no route`() {
        // Captured live: api.adsbdb.com/v0/callsign/BADCALLSIGN.
        assertNull(parseAdsbdbRoute("""{"response":"invalid callsign: BADCALLSIGN"}"""))
    }

    @Test fun `a response object with a null flightroute is no route`() {
        assertNull(parseAdsbdbRoute("""{"response":{"flightroute":null}}"""))
    }

    @Test fun `malformed JSON is no route, not a crash`() {
        assertNull(parseAdsbdbRoute("not json at all"))
    }
}
