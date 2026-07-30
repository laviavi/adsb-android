package com.laviavi.adsbandroid

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Guards the single-source decision: the USB dongle is the only signal source.
 *
 * File and AVR replay are legitimate, but only inside test source sets — they are
 * the parity harness, not a product feature. This checks the shipped source tree
 * rather than a config value, because the failure mode being prevented is code
 * quietly reappearing, not a setting being flipped.
 */
class SingleSourceGuardTests {

    private val mainSourceSets = listOf(
        File("src/main/kotlin"),
        File("../app/src/main/java"),
    ).filter { it.isDirectory }

    private fun mainKotlinFiles(): List<File> =
        mainSourceSets.flatMap { it.walkTopDown().filter { f -> f.extension == "kt" }.toList() }

    @Test fun `no user-selectable source types ship`() {
        val banned = listOf("SourceType", "NetworkFormat", "DummySource", "RtlTcpSource")
        val hits = mainKotlinFiles().flatMap { f ->
            val text = f.readText()
            banned.filter { text.contains(it) }.map { "${f.path}: $it" }
        }
        assertTrue(
            hits.isEmpty(),
            "Non-antenna source code is back in a main source set: " + hits.joinToString("; "),
        )
    }

    @Test fun `FileSource is a test-only replay helper`() {
        assertTrue(
            mainKotlinFiles().none { it.name == "FileSource.kt" },
            "FileSource belongs in the test source set — it is the replay harness, not a product source",
        )
    }

    @Test fun `NetworkSource remains, as the USB loopback transport`() {
        // Not an oversight: UsbRtlSdrSource delegates its socket reads to
        // NetworkSource on 127.0.0.1, which is how the driver app hands over IQ.
        assertTrue(
            mainKotlinFiles().any { it.name == "NetworkSource.kt" },
            "NetworkSource is required by the USB path",
        )
    }
}
