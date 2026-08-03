package com.laviavi.adsbandroid.pipeline

import com.laviavi.adsbandroid.data.BestRangeRecordEntity
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BestRangeTests {

    private fun record(distanceNm: Double) = BestRangeRecordEntity(
        icao = "A1B2C3", callsign = "UAL123", distanceNm = distanceNm,
        bearingDeg = 90.0, altitudeFt = 35_000, timestampMs = 1_000L,
    )

    @Test fun `no existing record is always beaten`() {
        assertTrue(isNewBestRange(0.1, null))
    }

    @Test fun `a longer distance beats the stored record`() {
        assertTrue(isNewBestRange(150.0, record(100.0)))
    }

    @Test fun `a shorter distance does not beat the stored record`() {
        assertFalse(isNewBestRange(50.0, record(100.0)))
    }

    @Test fun `an exact tie does not count as a new best`() {
        assertFalse(isNewBestRange(100.0, record(100.0)))
    }
}
