package com.laviavi.adsbandroid.location

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ObserverPositionResolverTests {

    private val fixedLat = 33.9524737
    private val fixedLon = -117.3317861
    private val liveLat = 34.05
    private val liveLon = -117.30

    @Test fun `startup fresh fix - Follow GPS falls back to fixed coordinates before any fix arrives`() {
        val resolver = ObserverPositionResolver()
        val (lat, lon) = resolver.resolve(ObserverMode.FOLLOW_GPS, fixedLat, fixedLon)
        assertEquals(fixedLat to fixedLon, lat to lon, "Must never report a fix that hasn't actually been received")
    }

    @Test fun `Follow GPS uses the live fix once one has been applied`() {
        val resolver = ObserverPositionResolver()
        resolver.applyLiveFix(liveLat, liveLon)
        val (lat, lon) = resolver.resolve(ObserverMode.FOLLOW_GPS, fixedLat, fixedLon)
        assertEquals(liveLat to liveLon, lat to lon)
    }

    @Test fun `Fixed mode always uses persisted coordinates, even with a live fix present`() {
        val resolver = ObserverPositionResolver()
        resolver.applyLiveFix(liveLat, liveLon)
        val (lat, lon) = resolver.resolve(ObserverMode.FIXED, fixedLat, fixedLon)
        assertEquals(fixedLat to fixedLon, lat to lon, "Fixed mode must use persisted lat/lon only")
    }

    @Test fun `location fallback - clearing the live fix reverts to fixed coordinates`() {
        val resolver = ObserverPositionResolver()
        resolver.applyLiveFix(liveLat, liveLon)
        resolver.clearLiveFix() // e.g. on source disconnect - a pre-outage fix is no longer trusted
        val (lat, lon) = resolver.resolve(ObserverMode.FOLLOW_GPS, fixedLat, fixedLon)
        assertEquals(fixedLat to fixedLon, lat to lon)
    }
}
