package com.laviavi.adsbandroid.ui.model

import com.laviavi.adsbandroid.aircraft.AircraftState
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LiveFiltersTests {

    private fun state(
        onGround: Boolean = false,
        lat: Double? = 34.0,
        lon: Double? = -117.0,
        altitudeFt: Int? = 35_000,
        distanceNm: Double? = 10.0,
        squawk: String? = null,
        ra: Boolean = false,
    ) = AircraftState(
        icao = "ABC123",
        onGround = onGround,
        latitude = lat,
        longitude = lon,
        altitudeFt = altitudeFt,
        distanceNm = distanceNm,
        squawk = squawk,
        tcasRaActive = ra,
    )

    @Test
    fun `no filters matches everything`() {
        assertTrue(LiveFilters.matches(state(), LiveFilters()))
        assertTrue(LiveFilters.matches(state(onGround = true, lat = null, lon = null), LiveFilters()))
    }

    @Test
    fun `airborne and on ground select opposite halves`() {
        val airborne = LiveFilters(airborne = true)
        assertTrue(LiveFilters.matches(state(onGround = false), airborne))
        assertFalse(LiveFilters.matches(state(onGround = true), airborne))

        val grounded = LiveFilters(onGround = true)
        assertTrue(LiveFilters.matches(state(onGround = true), grounded))
        assertFalse(LiveFilters.matches(state(onGround = false), grounded))
    }

    @Test
    fun `chips AND together`() {
        val both = LiveFilters(airborne = true, withPosition = true)
        assertTrue(LiveFilters.matches(state(onGround = false), both))
        assertFalse(LiveFilters.matches(state(onGround = false, lat = null, lon = null), both))
        assertFalse(LiveFilters.matches(state(onGround = true), both))
    }

    @Test
    fun `emergency accepts a squawk or an active RA`() {
        val f = LiveFilters(emergency = true)
        assertFalse(LiveFilters.matches(state(), f))
        assertTrue(LiveFilters.matches(state(squawk = "7700"), f))
        assertTrue(LiveFilters.matches(state(ra = true), f))
    }

    @Test
    fun `unknown values never satisfy a filter that needs them`() {
        assertFalse(LiveFilters.matches(state(distanceNm = null), LiveFilters(within50Mi = true)))
        assertFalse(LiveFilters.matches(state(altitudeFt = null), LiveFilters(altitudeBand = AltitudeBand.LOW)))
        // …but an unfiltered altitude still matches when no band is chosen.
        assertTrue(LiveFilters.matches(state(altitudeFt = null), LiveFilters()))
    }

    @Test
    fun `50 mi chip converts to nautical miles`() {
        val f = LiveFilters(within50Mi = true)
        // 45 statute miles is inside; 55 is outside. Naively comparing 50 against
        // nautical miles would wrongly admit everything out to 57.5 statute miles.
        assertTrue(LiveFilters.matches(state(distanceNm = 45 / 1.15077945), f))
        assertFalse(LiveFilters.matches(state(distanceNm = 55 / 1.15077945), f))
    }

    @Test
    fun `altitude bands do not overlap`() {
        assertTrue(AltitudeBand.LOW.matches(9_000))
        assertFalse(AltitudeBand.MID.matches(9_000))
        assertTrue(AltitudeBand.MID.matches(10_000))
        assertTrue(AltitudeBand.MID.matches(24_999))
        assertFalse(AltitudeBand.HIGH.matches(24_999))
        assertTrue(AltitudeBand.HIGH.matches(25_000))
    }
}
