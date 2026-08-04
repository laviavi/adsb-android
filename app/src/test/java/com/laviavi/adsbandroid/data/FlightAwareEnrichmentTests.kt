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
