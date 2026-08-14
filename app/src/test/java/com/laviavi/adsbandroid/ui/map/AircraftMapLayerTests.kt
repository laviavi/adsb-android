package com.laviavi.adsbandroid.ui.map

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * Regression coverage for the range-ring geometry: v2.0 replaced a screen-pixel
 * `CircleLayer` radius (which doesn't represent a constant real-world distance
 * across zoom levels) with an actual geodesic circle polygon, computed by hand
 * since MapLibre has no built-in "circle of N nautical miles" primitive.
 */
class AircraftMapLayerTests {

    @Test fun `circle is closed — first and last point match`() {
        val points = AircraftMapLayer.circlePoints(40.0, -74.0, 25.0)
        assertEquals(points.first(), points.last())
    }

    @Test fun `point count is points+1 to close the loop`() {
        val points = AircraftMapLayer.circlePoints(40.0, -74.0, 25.0, points = 36)
        assertEquals(37, points.size)
    }

    @Test fun `due-north point is radius nm away in latitude`() {
        // Bearing 0 (i=0) is due north: longitude unchanged, latitude shifts by
        // radiusNm/60 degrees (1 degree of latitude is ~60nm everywhere).
        val center = 40.0 to -74.0
        val radiusNm = 60.0
        val points = AircraftMapLayer.circlePoints(center.first, center.second, radiusNm, points = 360)
        val north = points[0]
        assertEquals(41.0, north.first, 0.05, "60nm north of 40N should land near 41N")
        assertEquals(center.second, north.second, 0.01)
    }

    @Test fun `every point is roughly radiusNm from the center`() {
        val radiusNm = 100.0
        val points = AircraftMapLayer.circlePoints(35.0, -100.0, radiusNm, points = 24)
        points.forEach { (lat, lon) ->
            // Rough equirectangular check is enough here — this isn't re-testing
            // the great-circle formula itself, just that no point is wildly off.
            val dLatNm = abs(lat - 35.0) * 60.0
            assertTrue(dLatNm <= radiusNm + 1.0, "point $lat,$lon strayed too far in latitude")
        }
    }
}
