package com.laviavi.adsbandroid.ui.stats

import com.laviavi.adsbandroid.data.AircraftVisitEntity
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AircraftStatsTests {

    private fun visit(
        icao: String,
        firstSeenMs: Long,
        lastSeenMs: Long = firstSeenMs + 60_000,
        registration: String? = "N12345",
        operator: String? = "United Airlines",
        aircraftType: String? = "B738",
        isAirline: Boolean = true,
    ) = AircraftVisitEntity(
        icao = icao, registration = registration, operator = operator, aircraftType = aircraftType,
        isAirline = isAirline, firstSeenMs = firstSeenMs, lastSeenMs = lastSeenMs, messageCount = 100,
    )

    @Test fun `empty visit log yields no summaries`() {
        assertTrue(summarizeVisits(emptyList()).isEmpty())
    }

    @Test fun `one aircraft seen three times counts three`() {
        val visits = listOf(
            visit("A1B2C3", firstSeenMs = 1_000),
            visit("A1B2C3", firstSeenMs = 2_000),
            visit("A1B2C3", firstSeenMs = 3_000),
        )
        val summary = summarizeVisits(visits).single()
        assertEquals(3, summary.timesSeen)
    }

    @Test fun `first and last seen ever span the earliest and latest visit`() {
        val visits = listOf(
            visit("A1B2C3", firstSeenMs = 5_000, lastSeenMs = 5_500),
            visit("A1B2C3", firstSeenMs = 1_000, lastSeenMs = 1_500),
            visit("A1B2C3", firstSeenMs = 3_000, lastSeenMs = 3_500),
        )
        val summary = summarizeVisits(visits).single()
        assertEquals(1_000, summary.firstSeenEverMs)
        assertEquals(5_500, summary.lastSeenEverMs)
    }

    @Test fun `identity fields come from the most recent visit, not an arbitrary one`() {
        val visits = listOf(
            visit("A1B2C3", firstSeenMs = 1_000, registration = "N-OLD", operator = "Old Corp"),
            visit("A1B2C3", firstSeenMs = 9_000, registration = "N-NEW", operator = "New Corp"),
        )
        val summary = summarizeVisits(visits).single()
        assertEquals("N-NEW", summary.registration)
        assertEquals("New Corp", summary.operator)
    }

    @Test fun `distinct aircraft produce distinct summaries`() {
        val visits = listOf(visit("A1B2C3", firstSeenMs = 1_000), visit("D4E5F6", firstSeenMs = 2_000))
        assertEquals(2, summarizeVisits(visits).size)
    }

    @Test fun `isAirline is carried from the most recent visit`() {
        val visits = listOf(
            visit("A1B2C3", firstSeenMs = 1_000, isAirline = false),
            visit("A1B2C3", firstSeenMs = 9_000, isAirline = true),
        )
        assertTrue(summarizeVisits(visits).single().isAirline)
    }
}
