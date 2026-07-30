package com.laviavi.adsbandroid.aircraft

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AircraftSortTests {

    /** Deliberately out of first-seen order, so FIRST_SEEN identity is a real check. */
    private fun sample() = listOf(
        AircraftState(icao = "A1", callsign = "BBB1234", distanceNm = 40.0,
            altitudeFt = 10_000, messageCount = 5, lastSeenMs = 100),
        AircraftState(icao = "A2", callsign = null, distanceNm = 5.0,
            altitudeFt = 35_000, messageCount = 50, lastSeenMs = 300),
        AircraftState(icao = "A3", callsign = "AAA9999", distanceNm = null,
            altitudeFt = null, messageCount = 1, lastSeenMs = 200),
    )

    @Test fun `FIRST_SEEN is the identity - order is never touched`() {
        val input = sample()
        assertEquals(input, AircraftSort.apply(input, AircraftSortOrder.FIRST_SEEN))
    }

    @Test fun `NEAREST sorts by distance, unknown distance last`() {
        val result = AircraftSort.apply(sample(), AircraftSortOrder.NEAREST)
        assertEquals(listOf("A2", "A1", "A3"), result.map { it.icao })
    }

    @Test fun `ALTITUDE sorts highest first, unknown altitude last`() {
        val result = AircraftSort.apply(sample(), AircraftSortOrder.ALTITUDE)
        assertEquals(listOf("A2", "A1", "A3"), result.map { it.icao })
    }

    @Test fun `CALLSIGN sorts alphabetically, blank callsigns last`() {
        val result = AircraftSort.apply(sample(), AircraftSortOrder.CALLSIGN)
        // A3="AAA9999" before A1="BBB1234"; A2 has no callsign, sorts last.
        assertEquals(listOf("A3", "A1", "A2"), result.map { it.icao })
    }

    @Test fun `MESSAGE_COUNT sorts most-active first`() {
        val result = AircraftSort.apply(sample(), AircraftSortOrder.MESSAGE_COUNT)
        assertEquals(listOf("A2", "A1", "A3"), result.map { it.icao })
    }

    @Test fun `LAST_SEEN sorts most-recent first`() {
        val result = AircraftSort.apply(sample(), AircraftSortOrder.LAST_SEEN)
        assertEquals(listOf("A2", "A3", "A1"), result.map { it.icao })
    }

    @Test fun `every order is a permutation, nothing dropped or duplicated`() {
        val input = sample()
        AircraftSortOrder.entries.forEach { order ->
            val result = AircraftSort.apply(input, order)
            assertEquals(input.map { it.icao }.toSet(), result.map { it.icao }.toSet(), "order=$order")
            assertEquals(input.size, result.size, "order=$order")
        }
    }

    @Test fun `empty and single-element lists do not crash any order`() {
        AircraftSortOrder.entries.forEach { order ->
            assertEquals(emptyList<AircraftState>(), AircraftSort.apply(emptyList(), order))
            val one = listOf(AircraftState(icao = "A1"))
            assertEquals(one, AircraftSort.apply(one, order))
        }
    }
}
