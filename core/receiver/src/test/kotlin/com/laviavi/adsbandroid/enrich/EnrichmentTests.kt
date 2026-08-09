package com.laviavi.adsbandroid.enrich

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.File

class EnrichmentTests {

    @Nested
    inner class IcaoToRegistration {

        /**
         * Fixed points taken from the Python reference and cross-checked against
         * guillaumemichel/icao-nnumber_converter. These pin the trie's edges: the
         * first and last address in the block, the bare/one-letter/two-letter
         * suffix boundaries, and each digit-count rollover.
         */
        @Test fun `known conversions match the reference`() {
            val cases = mapOf(
                "A00001" to "N1",
                "A00002" to "N1A",
                "A00003" to "N1AA",
                "A0001A" to "N1AZ",     // last two-letter suffix under the A stem
                "A0001B" to "N1B",
                "A0025A" to "N10",      // first address of the N10 stem
                "A00727" to "N10002",
                "A0A4C1" to "N140XD",
                "A12345" to "N1722M",
                "ADF7C7" to "N99999",   // final address in the US block
            )
            cases.forEach { (icao, reg) ->
                assertEquals(reg, Registration.fromIcao(icao), "ICAO $icao")
            }
        }

        @Test fun `non-US blocks are refused rather than guessed`() {
            // Returning a plausible-looking N-number for a German or UK aircraft
            // would be worse than returning nothing.
            listOf("3C6444", "4CA1FA", "406B2E", "780000", "000000", "FFFFFF")
                .forEach { assertNull(Registration.fromIcao(it), "ICAO $it") }
        }

        @Test fun `block boundaries are exclusive on both sides`() {
            assertNull(Registration.fromIcao("A00000"), "one below the block")
            assertNotNull(Registration.fromIcao("A00001"))
            assertNotNull(Registration.fromIcao("ADF7C7"))
            assertNull(Registration.fromIcao("ADF7C8"), "one above the block")
        }

        @Test fun `malformed input returns null instead of throwing`() {
            listOf("", "ZZZZZZ", "A0000", "not hex", "A000011")
                .forEach { assertNull(Registration.fromIcao(it), "input '$it'") }
        }
    }

    @Nested
    inner class RoundTrip {

        @Test fun `every address in the block round-trips`() {
            // The whole point of the algorithm is that it is invertible; a partial
            // sample would hide exactly the boundary bugs worth catching.
            var checked = 0
            for (icao in 0xA00001..0xADF7C7) {
                val reg = Registration.fromIcao(icao)
                assertNotNull(reg, "no registration for %06X".format(icao))
                val back = Registration.toIcao(reg!!)
                assertEquals("%06X".format(icao), back, "round-trip of $reg")
                checked++
            }
            assertEquals(915_399, checked, "US civil block size")
        }

        @Test fun `registrations are unique across the block`() {
            val seen = HashSet<String>(1_000_000)
            for (icao in 0xA00001..0xADF7C7) {
                val reg = Registration.fromIcao(icao)!!
                assertTrue(seen.add(reg), "duplicate registration $reg at %06X".format(icao))
            }
        }

        @Test fun `invalid N-numbers are rejected`() {
            listOf(
                "",           // empty
                "G-ABCD",     // not a US registration
                "N",          // no digits
                "N0",         // N-numbers start at 1
                "N100000",    // above N99999
                "N1AI",       // I is not in the FAA suffix alphabet
                "N1AO",       // nor is O
                "N12345A",    // 5 digits leaves no room for a suffix
                "N1234AB",    // 4 digits allows only one suffix letter
            ).forEach { assertNull(Registration.toIcao(it), "registration '$it'") }
        }
    }

    @Nested
    inner class PythonParity {

        private val fixture = File("src/test/resources/fixtures/registration_parity.tsv")

        private fun meta(key: String): String? = fixture.useLines { lines ->
            lines.firstOrNull { it.startsWith("#$key\t") }?.substringAfter('\t')
        }

        /**
         * The whole-block differential. Kotlin hashes its own output over exactly
         * the range Python hashed; a single differing address anywhere in the
         * 915,399 changes the digest.
         */
        @Test fun `digest over the whole block matches Python`() {
            assertTrue(fixture.exists(), "run tools/gen_registration_parity.py first")

            val expectedCount = meta("count")!!.toInt()
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            var count = 0
            for (value in 0xA00001..0xADF7C7) {
                val icao = "%06X".format(value)
                val reg = Registration.fromIcao(icao)
                assertNotNull(reg, "no registration for in-block $icao")
                digest.update("$icao\t$reg\n".toByteArray(Charsets.US_ASCII))
                count++
            }

            assertEquals(expectedCount, count, "block size")
            assertEquals(
                meta("sha256"),
                digest.digest().joinToString("") { "%02x".format(it) },
                "Kotlin and Python disagree somewhere in the block — " +
                    "the sampled rows below will show where",
            )
        }

        /** Runs on failure of the digest test to localise the disagreement. */
        @Test fun `sampled rows match Python`() {
            assertTrue(fixture.exists(), "run tools/gen_registration_parity.py first")

            var compared = 0
            val mismatches = mutableListOf<String>()
            fixture.forEachLine { line ->
                if (line.isBlank() || line.startsWith("#") || line.startsWith("icao\t")) return@forEachLine
                val (icao, expected) = line.split("\t", limit = 2)
                val actual = Registration.fromIcao(icao)
                if (actual != expected) mismatches += "$icao: python=$expected kotlin=$actual"
                compared++
            }
            assertTrue(compared > 100, "fixture looks truncated: only $compared rows")
            assertTrue(
                mismatches.isEmpty(),
                "$compared compared, ${mismatches.size} mismatched:\n" +
                    mismatches.take(20).joinToString("\n"),
            )
        }
    }

    @Nested
    inner class AirlineFromCallsign {

        @Test fun `known prefixes resolve`() {
            assertEquals("United Airlines", Airlines.fromCallsign("UAL2184"))
            assertEquals("Southwest Airlines", Airlines.fromCallsign("SWA1188"))
            assertEquals("Lufthansa", Airlines.fromCallsign("DLH441"))
        }

        @Test fun `callsigns are trimmed and case-normalised`() {
            // Decoded callsigns are space-padded to 8 chars by the ADS-B encoding.
            assertEquals("United Airlines", Airlines.fromCallsign("UAL2184 "))
            assertEquals("United Airlines", Airlines.fromCallsign(" ual2184"))
        }

        @Test fun `unknown or unusable callsigns return null`() {
            // XQZ isn't a real ICAO airline prefix and isn't in either source table —
            // unlike "ZZZ", which used to be a safe "definitely unmapped" example until
            // OpenFlights turned out to have a real Zabaykalskii Airlines entry for it.
            listOf(null, "", "  ", "XX", "XQZ9999", "N38901")
                .forEach { assertNull(Airlines.fromCallsign(it), "callsign '$it'") }
        }

        @Test fun `registration callsigns are recognised`() {
            listOf("N38901", "N1", "n8703m").forEach {
                assertTrue(Airlines.isRegistrationCallsign(it), "'$it' is a registration")
            }
            listOf("UAL2184", "NKS100", null, "", "N").forEach {
                assertFalse(Airlines.isRegistrationCallsign(it), "'$it' is not a registration")
            }
        }

        @Test fun `table was generated, not left empty`() {
            // Not an exact count: the table is now the Python reference (155,
            // fixed) merged with OpenFlights' airlines.dat, which grows/shrinks
            // slightly whenever that cache is refreshed — a floor is what
            // actually catches "generation silently produced an empty table".
            assertTrue(Airlines.size >= 1000, "regenerate with tools/gen_airlines.py — got ${Airlines.size}")
        }
    }

    @Nested
    inner class OfflineCombination {

        @Test fun `airline flight gets operator and derived registration`() {
            val r = OfflineEnrichment.enrich("A4B1C2", "UAL2184")
            assertEquals("United Airlines", r.operator)
            assertEquals(DataSource.ALGORITHMIC, r.operatorSource)
            assertEquals(Registration.fromIcao("A4B1C2"), r.registration)
            assertEquals(DataSource.ALGORITHMIC, r.registrationSource)
        }

        @Test fun `GA aircraft transmitting its tail number beats the algorithm`() {
            // Both sources agree here, but the decoded one is authoritative and
            // must be labelled as such — it is what the aircraft actually said.
            val icao = Registration.toIcao("N38901")!!
            val r = OfflineEnrichment.enrich(icao, "N38901")
            assertEquals("N38901", r.registration)
            assertEquals(DataSource.DECODED, r.registrationSource)
            assertNull(r.operator, "a tail number is not an operator")
        }

        @Test fun `foreign aircraft yields operator but no registration`() {
            val r = OfflineEnrichment.enrich("3C6444", "DLH441")
            assertEquals("Lufthansa", r.operator)
            assertNull(r.registration, "non-US ICAO has no derivable registration")
            assertNull(r.registrationSource)
        }

        @Test fun `no callsign yet still yields a registration`() {
            // The ICAO arrives before the callsign, so registration must not wait.
            val r = OfflineEnrichment.enrich("A4B1C2", null)
            assertNotNull(r.registration)
            assertNull(r.operator)
        }
    }

    @Nested
    inner class SourcePrecedence {

        @Test fun `stronger sources outrank weaker ones`() {
            assertTrue(DataSource.DECODED.betterThan(DataSource.NETWORK))
            assertTrue(DataSource.NETWORK.betterThan(DataSource.DATABASE))
            assertTrue(DataSource.DATABASE.betterThan(DataSource.ALGORITHMIC))
            assertTrue(DataSource.ALGORITHMIC.betterThan(null))
            assertFalse(DataSource.ALGORITHMIC.betterThan(DataSource.DECODED))
        }
    }
}
