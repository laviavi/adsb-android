package com.laviavi.adsbandroid.pipeline

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * The rolling accept-rate window: "tested / accepted / rejected over the last
 * N seconds." This is the number that would have shown Session 7's failure in
 * seconds — total climbing into the millions while accepted stayed at zero —
 * instead of after 1,043,100 frames on screen looking merely "high."
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PipelineStatsTests {

    @Test fun `window accumulates accepted and rejected separately`() = runTest {
        val stats = PipelineStats(backgroundScope)
        stats.windowSeconds = 10

        // Second 1: 5 valid, 2 bad.
        repeat(5) { stats.totalMessages.incrementAndGet(); stats.validMessages.incrementAndGet() }
        repeat(2) { stats.totalMessages.incrementAndGet(); stats.invalidMessages.incrementAndGet() }
        advanceTimeBy(1_001)

        val snap = stats.stats.value
        assertEquals(7L, snap.windowTested)
        assertEquals(5L, snap.windowAccepted)
        assertEquals(2L, snap.windowRejected)
        assertEquals(5.0 / 7.0 * 100.0, snap.windowAcceptRatePercent, 0.01)
    }

    @Test fun `corrected frames count as accepted, not merely valid`() = runTest {
        val stats = PipelineStats(backgroundScope)
        stats.totalMessages.incrementAndGet(); stats.correctedMessages.incrementAndGet()
        advanceTimeBy(1_001)

        val snap = stats.stats.value
        assertEquals(1L, snap.windowAccepted, "a corrected frame is a usable frame")
        assertEquals(0L, snap.windowRejected)
    }

    @Test fun `Session 7 shape - frames found but none accepted`() = runTest {
        // The exact failure this exists to surface: the demodulator (and thus
        // CRC) sees frames continuously, but the accept rate is pinned at zero
        // because the sample rate (or some other pipeline stage) is broken.
        val stats = PipelineStats(backgroundScope)
        repeat(50_000) { stats.totalMessages.incrementAndGet(); stats.invalidMessages.incrementAndGet() }
        advanceTimeBy(1_001)

        val snap = stats.stats.value
        assertEquals(50_000L, snap.windowTested)
        assertEquals(0L, snap.windowAccepted)
        assertEquals(0.0, snap.windowAcceptRatePercent)
    }

    @Test fun `window only looks back windowSeconds, not the whole session`() = runTest {
        val stats = PipelineStats(backgroundScope)
        stats.windowSeconds = PipelineStats.MIN_WINDOW_SEC // 5 — the smallest the window allows

        // 1 accepted per second for twice the window length.
        repeat(PipelineStats.MIN_WINDOW_SEC * 2) {
            stats.totalMessages.incrementAndGet(); stats.validMessages.incrementAndGet()
            advanceTimeBy(1_001)
        }

        // Only the last 5 seconds should be counted, not all 10.
        assertEquals(PipelineStats.MIN_WINDOW_SEC.toLong(), stats.stats.value.windowAccepted)
    }

    @Test fun `widening the window after the fact does not fabricate history`() = runTest {
        val stats = PipelineStats(backgroundScope)
        stats.windowSeconds = PipelineStats.MIN_WINDOW_SEC
        repeat(PipelineStats.MIN_WINDOW_SEC) {
            stats.totalMessages.incrementAndGet(); stats.validMessages.incrementAndGet()
            advanceTimeBy(1_001)
        }
        stats.windowSeconds = PipelineStats.MAX_WINDOW_SEC
        advanceTimeBy(1_001) // one more second, nothing new happened

        // 5 real seconds of data plus 1 idle second = 5 accepted total, not 60.
        assertEquals(PipelineStats.MIN_WINDOW_SEC.toLong(), stats.stats.value.windowAccepted)
    }

    @Test fun `windowSeconds is clamped to a sane range`() {
        val stats = PipelineStats(TestScope())
        stats.windowSeconds = 1
        assertEquals(PipelineStats.MIN_WINDOW_SEC, stats.windowSeconds)
        stats.windowSeconds = 1_000
        assertEquals(PipelineStats.MAX_WINDOW_SEC, stats.windowSeconds)
    }

    @Test fun `recovered and strict-bad counters track independently of the UI-facing buckets`() {
        val stats = PipelineStats(TestScope())
        stats.recoveredMessages.incrementAndGet()
        stats.badCrcMessages.incrementAndGet()
        assertEquals(1L, stats.recoveredMessages.get())
        assertEquals(1L, stats.badCrcMessages.get())
        // Untouched by this: the UI-facing buckets are incremented separately
        // by PipelineService, not derived from these.
        assertEquals(0L, stats.validMessages.get())
        assertEquals(0L, stats.invalidMessages.get())
    }

    @Test fun `reset also clears the performance-metrics breakout counters`() {
        val stats = PipelineStats(TestScope())
        stats.recoveredMessages.incrementAndGet()
        stats.badCrcMessages.incrementAndGet()
        stats.reset()
        assertEquals(0L, stats.recoveredMessages.get())
        assertEquals(0L, stats.badCrcMessages.get())
    }

    @Test fun `reset clears the window along with the totals`() = runTest {
        val stats = PipelineStats(backgroundScope)
        stats.totalMessages.incrementAndGet(); stats.validMessages.incrementAndGet()
        advanceTimeBy(1_001)
        assertTrue(stats.stats.value.windowAccepted > 0)

        stats.reset()
        assertEquals(0L, stats.stats.value.windowTested)
        assertEquals(0L, stats.stats.value.windowAccepted)
    }
}
