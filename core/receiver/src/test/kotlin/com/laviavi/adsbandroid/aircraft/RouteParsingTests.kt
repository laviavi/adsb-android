package com.laviavi.adsbandroid.aircraft

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RouteParsingTests {

    @Test fun `null route yields null for both`() {
        assertNull(routeOrigin(null))
        assertNull(routeDestination(null))
    }

    @Test fun `adsbdb hyphen format parses both ends`() {
        assertEquals("KJFK", routeOrigin("KJFK-KLAX"))
        assertEquals("KLAX", routeDestination("KJFK-KLAX"))
    }

    @Test fun `FlightAware arrow format parses both ends`() {
        assertEquals("EWR", routeOrigin("EWR → LAX"))
        assertEquals("LAX", routeDestination("EWR → LAX"))
    }

    @Test fun `FlightAware origin-only format leaves destination null`() {
        assertEquals("EWR", routeOrigin("EWR →"))
        assertNull(routeDestination("EWR →"))
    }

    @Test fun `FlightAware destination-only format leaves origin null`() {
        assertNull(routeOrigin("→ LAX"))
        assertEquals("LAX", routeDestination("→ LAX"))
    }

    @Test fun `blank route yields null for both`() {
        assertNull(routeOrigin(""))
        assertNull(routeDestination(""))
    }
}
