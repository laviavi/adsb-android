// Pure-JVM receiver core: demod, CRC, decoder, aircraft state, enrichment,
// location policy, capture contracts. No Android dependencies — this is what
// lets the decoder be diffed against the Python reference without an emulator.
// `:app` depends on this and holds only Android-specific code.
//
// Renamed from `:core-test` (Step 3, docs/PLAN_STATUS.md) — the old name called
// this a test module when everything in src/main/kotlin ships to users. See
// docs/PHASE_PROGRESS.md for the history of why that name existed and what it
// cost (Sessions 7-8: a stale duplicate constant certified a sample-rate bug
// that broke reception on hardware while both copies' tests stayed green).

plugins {
    kotlin("jvm")
    // The offline-map manifest is a serialized document. Applied here rather than
    // only in :app because the manifest model and its schema belong with the pure
    // logic that reads it — putting the types in :app would drag the whole offline
    // manager across the boundary and out of JVM test reach.
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.11.3")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.3")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}
