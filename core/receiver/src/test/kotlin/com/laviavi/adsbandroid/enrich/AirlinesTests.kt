package com.laviavi.adsbandroid.enrich

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AirlinesTests {

    @Test fun `real FAA-style operator names match despite case and corporate suffix`() {
        listOf(
            "SOUTHWEST AIRLINES CO",
            "DELTA AIR LINES INC",
            "UNITED AIRLINES INC",
            "SKYWEST AIRLINES INC",
            "Skywest Airlines",
            "AIR CANADA",
        ).forEach { assertTrue(Airlines.matchesKnownAirlineName(it), it) }
    }

    @Test fun `private owners and leasing entities do not match`() {
        listOf(
            "DUIGNAN MICHAEL DALE",
            "C C & E I LLC",
            "WILMINGTON TRUST CO TRUSTEE",
            "MLS AVIATION LLC",
            null,
            "",
            "   ",
        ).forEach { assertFalse(Airlines.matchesKnownAirlineName(it), it ?: "null") }
    }
}
