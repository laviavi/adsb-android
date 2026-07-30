package com.laviavi.adsbandroid.pipeline

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class PipelineStats(private val scope: CoroutineScope) {

    val totalMessages     = AtomicLong(0)
    val validMessages     = AtomicLong(0)
    val correctedMessages = AtomicLong(0)
    val invalidMessages   = AtomicLong(0)

    /**
     * Breakout counters for the performance CSV port (see
     * `:core:receiver`'s `PerformanceMetrics`), tracked separately from the
     * four above so those UI-facing counters' existing meaning doesn't
     * change: [recoveredMessages] is a subset already folded into
     * [validMessages]; [badCrcMessages] is strictly CRC-invalid frames,
     * excluding the parity-address frames [invalidMessages] also counts.
     */
    val recoveredMessages = AtomicLong(0)
    val badCrcMessages    = AtomicLong(0)

    /** Frames whose dBFS >= -3 (near ADC full scale). */
    val strongSignalCount = AtomicLong(0)

    /**
     * Parity-address frames whose ICAO could not be resolved against the cache.
     * Shown as "unresolved", kept distinct from [badCrcMessages] — the CLI's
     * three-way CRC split that the UI must not collapse (spec §Detail).
     */
    val unresolvedMessages = AtomicLong(0)

    // --- Source throughput, fed by the USB read loop. ---
    val bytesRead   = AtomicLong(0)
    val buffersRead = AtomicLong(0)
    val overruns    = AtomicLong(0)

    /** Preamble candidates found by the demodulator; mirrored from `Demodulator.candidateCount`. */
    @Volatile var candidateCount: Long = 0L

    /** Per-DF accepted-frame counts, matching Python's `df_counts` dict. Keyed by DF integer. */
    val dfCounts = ConcurrentHashMap<Int, AtomicLong>()

    private val _stats = MutableStateFlow(Snapshot())
    val stats: StateFlow<Snapshot> = _stats.asStateFlow()
    private var startTimeMs = System.currentTimeMillis()

    /**
     * Rolling accept-rate window, in seconds. Not the same thing as the 1 Hz
     * snapshot cadence below: this is how far back "tested / accepted / rejected"
     * looks, independent of how often the UI is told about it. Matches the
     * Python reference's `<`/`>` window stepper (default 5 s there; kept wider
     * here since there is no keyboard to nudge it live yet).
     */
    @Volatile var windowSeconds: Int = DEFAULT_WINDOW_SEC
        set(value) { field = value.coerceIn(MIN_WINDOW_SEC, MAX_WINDOW_SEC) }

    /** One entry per elapsed second: (accepted-this-second, rejected-this-second). */
    private val perSecond = ArrayDeque<Pair<Long, Long>>()

    /** One entry per elapsed second, oldest first, for the Receiver rate chart. */
    private val rateSamples = ArrayDeque<RateSample>()

    /** One second's CRC outcomes, the four stacked bands of the 60 s rate chart. */
    data class RateSample(
        val valid: Long = 0,
        val corrected: Long = 0,
        val recovered: Long = 0,
        val bad: Long = 0,
    ) {
        val total: Long get() = valid + corrected + recovered + bad
    }

    data class Snapshot(
        val totalMessages: Long     = 0,
        val validMessages: Long     = 0,
        val correctedMessages: Long = 0,
        val invalidMessages: Long   = 0,
        val uptimeMs: Long          = 0,
        val messagesPerSecond: Double = 0.0,

        /**
         * "Tested" over the last [windowSeconds]: every frame the demodulator
         * found and handed to the CRC checker, whether or not it survived.
         * This is the number that would have shown Session 7's failure in
         * seconds instead of after 1,043,100 frames — "tested" climbing while
         * "accepted" stays at zero is a decode-pipeline problem, not an antenna
         * problem, and the two mean completely different next steps.
         */
        val windowSeconds: Int    = 0,
        val windowTested: Long    = 0,
        val windowAccepted: Long  = 0,
        val windowRejected: Long  = 0,
        val strongSignalCount: Long = 0,
        /** Accepted frames per DF, sorted by DF number. */
        val dfCounts: Map<Int, Long> = emptyMap(),

        // --- Pipeline card (spec §Receiver). ---
        val recoveredMessages: Long  = 0,
        val badCrcMessages: Long     = 0,
        val unresolvedMessages: Long = 0,
        val bytesPerSecond: Double   = 0.0,
        val buffersPerSecond: Double = 0.0,
        val overruns: Long           = 0,
        val candidatesPerSecond: Double = 0.0,
        /** Last 60 one-second CRC breakdowns, oldest first. */
        val rateHistory: List<RateSample> = emptyList(),
    ) {
        val windowAcceptRatePercent: Double
            get() = if (windowTested > 0) windowAccepted * 100.0 / windowTested else 0.0
        val strongSignalPct: Double
            get() = if (validMessages > 0) strongSignalCount * 100.0 / validMessages else 0.0
    }

    init {
        scope.launch {
            var lastTotal = 0L
            var lastAccepted = 0L
            var lastRejected = 0L
            var lastValid = 0L
            var lastCorrected = 0L
            var lastRecovered = 0L
            var lastBad = 0L
            var lastBytes = 0L
            var lastBuffers = 0L
            var lastCandidates = 0L
            while (true) {
                delay(1_000L)
                val total = totalMessages.get()
                val accepted = validMessages.get() + correctedMessages.get()
                val rejected = invalidMessages.get()

                perSecond.addLast(Pair(accepted - lastAccepted, rejected - lastRejected))
                while (perSecond.size > MAX_WINDOW_SEC) perSecond.removeFirst()

                val validNow = validMessages.get()
                val correctedNow = correctedMessages.get()
                val recoveredNow = recoveredMessages.get()
                val badNow = badCrcMessages.get()
                rateSamples.addLast(
                    RateSample(
                        valid     = validNow - lastValid,
                        corrected = correctedNow - lastCorrected,
                        recovered = recoveredNow - lastRecovered,
                        bad       = badNow - lastBad,
                    )
                )
                while (rateSamples.size > RATE_HISTORY_SEC) rateSamples.removeFirst()

                val bytesNow = bytesRead.get()
                val buffersNow = buffersRead.get()
                val candidatesNow = candidateCount

                val window = windowSeconds
                var wAccepted = 0L; var wRejected = 0L
                var seen = 0
                val it = perSecond.descendingIterator()
                while (it.hasNext() && seen < window) {
                    val (a, r) = it.next()
                    wAccepted += a; wRejected += r
                    seen++
                }

                _stats.value = Snapshot(
                    totalMessages     = total,
                    validMessages     = validMessages.get(),
                    correctedMessages = correctedMessages.get(),
                    invalidMessages   = invalidMessages.get(),
                    uptimeMs          = System.currentTimeMillis() - startTimeMs,
                    messagesPerSecond = (total - lastTotal).toDouble(),
                    windowSeconds     = window,
                    windowTested      = wAccepted + wRejected,
                    windowAccepted    = wAccepted,
                    windowRejected    = wRejected,
                    strongSignalCount = strongSignalCount.get(),
                    dfCounts = dfCounts.entries
                        .associate { it.key to it.value.get() }
                        .toSortedMap(),
                    recoveredMessages  = recoveredNow,
                    badCrcMessages     = badNow,
                    unresolvedMessages = unresolvedMessages.get(),
                    bytesPerSecond     = (bytesNow - lastBytes).toDouble(),
                    buffersPerSecond   = (buffersNow - lastBuffers).toDouble(),
                    overruns           = overruns.get(),
                    candidatesPerSecond = (candidatesNow - lastCandidates).toDouble(),
                    rateHistory        = rateSamples.toList(),
                )
                lastTotal = total
                lastAccepted = accepted
                lastRejected = rejected
                lastValid = validNow
                lastCorrected = correctedNow
                lastRecovered = recoveredNow
                lastBad = badNow
                lastBytes = bytesNow
                lastBuffers = buffersNow
                lastCandidates = candidatesNow
            }
        }
    }

    fun incrementDf(df: Int) {
        dfCounts.getOrPut(df) { AtomicLong(0) }.incrementAndGet()
    }

    fun reset() {
        totalMessages.set(0); validMessages.set(0)
        correctedMessages.set(0); invalidMessages.set(0)
        recoveredMessages.set(0); badCrcMessages.set(0)
        unresolvedMessages.set(0)
        strongSignalCount.set(0)
        bytesRead.set(0); buffersRead.set(0); overruns.set(0)
        candidateCount = 0L
        dfCounts.clear()
        startTimeMs = System.currentTimeMillis()
        perSecond.clear()
        rateSamples.clear()
        _stats.value = Snapshot(windowSeconds = windowSeconds)
    }

    /**
     * Clears only the rolling accept-rate window, leaving session totals intact.
     * Called whenever a demod knob or the window length changes, matching the
     * reference's `_reset_win_counters` — the accept rate visibly restarts from
     * zero so the operator knows the reading is fresh, not carried over from the
     * previous tuning.
     */
    fun resetWindow() {
        perSecond.clear()
        _stats.value = _stats.value.copy(
            windowSeconds  = windowSeconds,
            windowTested   = 0,
            windowAccepted = 0,
            windowRejected = 0,
        )
    }

    companion object {
        const val MIN_WINDOW_SEC = 5
        const val DEFAULT_WINDOW_SEC = 10
        const val MAX_WINDOW_SEC = 60
        /** Samples kept for the Receiver rate chart — one per second over 60 s. */
        const val RATE_HISTORY_SEC = 60
    }
}
