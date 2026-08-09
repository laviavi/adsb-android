package com.laviavi.adsbandroid.data

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Regression coverage for dropping the `flightStatus == "airborne"` requirement.
 * Real case: FlightAware's page for CGHAS (ICAO C05737, a Harbour Air DHC-3 Turbo
 * Otter) carries both an "airborne" and an "arrived" leg — a short seaplane hop
 * is airborne only briefly, so a retry landing outside that window used to get
 * silently discarded even though FlightAware genuinely had the flight.
 */
class FlightAwareEnrichmentTests {

    private fun htmlWithFlights(flightsJson: String) = """
        <html><body><script>
        trackpollBootstrap = {"flights":{$flightsJson}};
        </script></body></html>
    """.trimIndent()

    private val airborneFlight = """
        "F1":{"flightStatus":"airborne","origin":{"iata":"YVR"},"destination":{"iata":"YYJ"},
        "displayIdent":"CGHAS","airline":{"fullName":"Harbour Air Seaplanes","icao":"HBR"},
        "aircraft":{"type":"DHC3","typeDetails":{"manufacturer":"DE HAVILLAND","model":"OTTER"}}}
    """.trimIndent()

    private val arrivedFlight = """
        "F2":{"flightStatus":"arrived","origin":{"iata":"YYJ"},"destination":{"iata":"YVR"},
        "displayIdent":"CGHAS","airline":{"fullName":"Harbour Air Seaplanes","icao":"HBR"},
        "aircraft":{"type":"DHC3","typeDetails":{"manufacturer":"DE HAVILLAND","model":"OTTER"}}}
    """.trimIndent()

    private val unknownPlaceholder = """
        "INVALID-1":{"unknown":true,"unknownContent":"couldn't find flight tracking data"}
    """.trimIndent()

    @Test fun `an airborne flight is still parsed`() {
        val result = parse(htmlWithFlights(airborneFlight))
        assertNotNull(result)
        assertEquals("YVR", result!!.origin)
        assertEquals("Harbour Air Seaplanes", result.airlineName)
    }

    @Test fun `an arrived flight is now parsed too — the actual fix`() {
        val result = parse(htmlWithFlights(arrivedFlight))
        assertNotNull(result)
        assertEquals("YYJ", result!!.origin)
        assertEquals("YVR", result.destination)
    }

    @Test fun `an unknown placeholder with no flightStatus yields no result`() {
        val result = parse(htmlWithFlights(unknownPlaceholder))
        assertNull(result, "a not-found placeholder must not be treated as a resolved flight")
    }

    @Test fun `a real flight is preferred over a preceding unknown placeholder`() {
        val result = parse(htmlWithFlights("$unknownPlaceholder,$arrivedFlight"))
        assertNotNull(result)
        assertEquals("YYJ", result!!.origin)
    }

    @Test fun `no trackpollBootstrap marker at all yields no result`() {
        assertNull(parse("<html><body>nothing here</body></html>"))
    }
}

/**
 * Regression coverage for the "null → null" / "Null" type bug (screenshot,
 * 2026-08-09): FlightAware's JSON can leave a field genuinely present but set
 * to JSON `null` (not absent). kotlinx.serialization's `JsonNull.content` is
 * the literal 4-character string `"null"`, which the old `?: ""` fallback
 * never caught (a present key never hits the fallback) — it flowed straight
 * into `FaResult` and then, via `titleCase()`, into the capitalized string
 * `"Null"` for aircraft type. `parse()` now filters every extracted field
 * through `.present()` before use.
 */
class FlightAwareEnrichmentNullFieldTests {

    private fun htmlWithFlights(flightsJson: String) = """
        <html><body><script>
        trackpollBootstrap = {"flights":{$flightsJson}};
        </script></body></html>
    """.trimIndent()

    @Test fun `a JSON null origin and destination do not become the string 'null'`() {
        val flight = """
            "F1":{"flightStatus":"scheduled","origin":{"iata":null},"destination":{"iata":null},
            "displayIdent":"CFHAJ"}
        """.trimIndent()
        val result = parse(htmlWithFlights(flight))
        assertNotNull(result)
        assertEquals("", result!!.origin)
        assertEquals("", result.destination)
        assertFalse(result.hasRoute())
    }

    @Test fun `a JSON null aircraft manufacturer and model do not become the string 'Null'`() {
        val flight = """
            "F1":{"flightStatus":"scheduled","displayIdent":"CFHAJ",
            "aircraft":{"type":null,"typeDetails":{"manufacturer":null,"model":null}}}
        """.trimIndent()
        val result = parse(htmlWithFlights(flight))
        assertNotNull(result)
        assertEquals("", result!!.typeCode)
        assertEquals("", result.manufacturer)
        assertEquals("", result.model)
        assertFalse(result.hasAircraft())
    }

    @Test fun `a JSON null flightStatus is not treated as a resolved flight`() {
        val flight = """"F1":{"flightStatus":null}"""
        assertNull(parse(htmlWithFlights(flight)))
    }
}
