package com.laviavi.adsbandroid.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class StringPresenceTests {

    @Test fun `a real value passes through trimmed`() {
        assertEquals("CFHAJ", "  CFHAJ  ".present())
    }

    @Test fun `null is absent`() {
        assertNull((null as String?).present())
    }

    @Test fun `empty and blank strings are absent`() {
        assertNull("".present())
        assertNull("   ".present())
    }

    @Test fun `the literal string null, any case, is absent`() {
        assertNull("null".present())
        assertNull("Null".present())
        assertNull("NULL".present())
        assertNull("  null  ".present())
    }
}
