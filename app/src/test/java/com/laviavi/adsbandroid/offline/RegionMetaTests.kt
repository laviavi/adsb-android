package com.laviavi.adsbandroid.offline

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Regression coverage for `RegionMeta`'s manual (non-kotlinx.serialization) encoding —
 * :app doesn't carry the serialization compiler plugin, so an offline area's display
 * name/timestamp round-trips through a plain newline-delimited byte array instead.
 */
class RegionMetaTests {

    @Test fun `round-trips name and timestamp`() {
        val original = RegionMeta("Riverside", 1_732_000_000_000L)
        val decoded = RegionMeta.decode(original.encode())
        assertEquals(original, decoded)
    }

    @Test fun `a name containing no newline decodes cleanly`() {
        val decoded = RegionMeta.decode("Some Place\n42".toByteArray())
        assertEquals("Some Place", decoded.name)
        assertEquals(42L, decoded.createdAtMs)
    }

    @Test fun `malformed bytes with no newline fall back rather than crash`() {
        val decoded = RegionMeta.decode("garbage".toByteArray())
        assertEquals("garbage", decoded.name)
        assertEquals(0L, decoded.createdAtMs)
    }

    @Test fun `empty bytes fall back to a default name`() {
        val decoded = RegionMeta.decode(ByteArray(0))
        assertEquals("Offline area", decoded.name)
    }
}
