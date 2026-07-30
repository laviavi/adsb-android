package com.laviavi.adsbandroid.pipeline

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Locks the two-flag interaction behind [AppConfig.networkEnrichmentAllowed].
 *
 * Worth its own tests because the failure is silent in both directions: a wrong
 * AND/OR still compiles, still toggles in the UI, and only shows up as network
 * traffic that should not be happening — which nothing in the app surfaces.
 */
class OfflineModeTests {

    private val base = AppConfig()

    @Test fun `default is online`() {
        assertFalse(base.offlineMode)
        assertTrue(base.networkEnrichmentAllowed)
    }

    @Test fun `offline mode blocks enrichment even when enrichment is on`() {
        val c = base.copy(enrichmentEnabled = true, offlineMode = true)
        assertFalse(c.networkEnrichmentAllowed)
    }

    @Test fun `enrichment off blocks enrichment even when online`() {
        val c = base.copy(enrichmentEnabled = false, offlineMode = false)
        assertFalse(c.networkEnrichmentAllowed)
    }

    @Test fun `network is allowed only when online and enrichment is on`() {
        assertTrue(base.copy(enrichmentEnabled = true, offlineMode = false).networkEnrichmentAllowed)
        listOf(
            true to true,
            false to true,
            false to false,
        ).forEach { (enrichment, offline) ->
            val c = base.copy(enrichmentEnabled = enrichment, offlineMode = offline)
            assertFalse(
                c.networkEnrichmentAllowed,
                "enrichment=$enrichment offline=$offline should not allow network",
            )
        }
    }

    @Test fun `offline mode preserves the user's enrichment preference`() {
        // The toggle must not implement itself by writing enrichmentEnabled=false,
        // or turning offline mode off would silently leave enrichment disabled.
        val on = base.copy(enrichmentEnabled = true)
        val offline = on.copy(offlineMode = true)
        assertTrue(offline.enrichmentEnabled, "preference must survive going offline")
        assertTrue(offline.copy(offlineMode = false).networkEnrichmentAllowed, "must resume on return")
    }

    @Test fun `offline mode does not disturb the receiver session`() {
        // Decoding is USB-only; toggling the network has no business restarting it.
        val offline = base.copy(offlineMode = true)
        assertFalse(ConfigChange.requiresPipelineRestart(base, offline))
        assertFalse(ConfigChange.requiresGainReapply(base, offline))
        assertFalse(ConfigChange.requiresBiasTeeReapply(base, offline))
        assertFalse(ConfigChange.requiresDemodRetune(base, offline))
    }
}
