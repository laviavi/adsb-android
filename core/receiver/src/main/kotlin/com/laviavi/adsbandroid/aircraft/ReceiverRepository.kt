package com.laviavi.adsbandroid.aircraft

import com.laviavi.adsbandroid.decoder.DecodedMessage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the live aircraft table and everything that touches it: ingesting
 * decoded messages, expiring stale aircraft, and publishing a sorted snapshot
 * to observers at a fixed rate.
 *
 * This is the "repository" layer from `ANDROID_MIGRATION_PLAN.md` §4.2/§4.3 —
 * the pipeline updates state as fast as frames arrive, and a ticker publishes
 * to observers at a fixed rate, independent of decode rate. Previously this
 * logic lived inline in `PipelineService` (Sessions 13/14); pulling it into its
 * own class makes it testable without Android or a running service, and gives
 * the ingest path a real bounded queue instead of a direct call.
 *
 * Thread-safety: every touch of the underlying [AircraftManager] — ingest,
 * expiry, route patch, reset — is confined to one single-parallelism
 * dispatcher. `AircraftManager`'s own doc comment says it needs external
 * synchronization; before Session 13 nothing enforced that and two call sites
 * already raced on it.
 *
 * Back-pressure: [offer] pushes onto a bounded [Channel] with
 * [BufferOverflow.DROP_OLDEST] rather than calling into the dispatcher
 * directly, so a producer (the IQ read loop) can never be made to wait on
 * decode falling behind. [droppedBatches] counts what that policy actually
 * discards, via the channel's own `onUndeliveredElement` hook — not a
 * hand-rolled counter. Sized generously (see [DEFAULT_INGEST_QUEUE_CAPACITY]):
 * decode is cheap relative to how often IQ buffers arrive, so under normal
 * operation this should read zero. It is a real queue with real drop
 * semantics, not speculative infrastructure — it can be exercised by anyone
 * who overwhelms it (see the tests), it is simply not expected to fill in
 * practice today.
 */
class ReceiverRepository(
    private val scope: CoroutineScope,
    private val sortOrderProvider: () -> AircraftSortOrder,
    private val expirySecondsProvider: () -> Int,
    private val onUpdated: (AircraftState) -> Unit = {},
    private val onDeparted: (List<AircraftState>) -> Unit = {},
    private val publishIntervalMs: Long = DEFAULT_PUBLISH_INTERVAL_MS,
    ingestQueueCapacity: Int = DEFAULT_INGEST_QUEUE_CAPACITY,
    /**
     * Injectable so tests can supply a dispatcher that shares their virtual
     * clock (e.g. `Dispatchers.Unconfined`). The real
     * `Dispatchers.Default.limitedParallelism(1)` is a genuine thread pool —
     * `runTest`'s `advanceTimeBy` has no way to wait for work running on it,
     * which made the initial version of these tests flaky by construction.
     */
    dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) {
    private val aircraftMgr = AircraftManager()
    private val dispatcher = dispatcher

    private val _aircraft = MutableStateFlow<List<AircraftState>>(emptyList())
    val aircraft: StateFlow<List<AircraftState>> = _aircraft.asStateFlow()

    private val _droppedBatches = MutableStateFlow(0L)
    /** Batches discarded by [offer]'s overflow policy because the ingest queue was full. */
    val droppedBatches: StateFlow<Long> = _droppedBatches.asStateFlow()

    private class Batch(val messages: List<DecodedMessage>, val nowMs: Long)

    private val inbox = Channel<Batch>(
        capacity = ingestQueueCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
        onUndeliveredElement = { _droppedBatches.value += it.messages.size },
    )

    private var jobs: List<Job> = emptyList()

    /** Starts the publish, expiry and ingest loops. Idempotent. */
    fun start() {
        if (jobs.isNotEmpty()) return
        jobs = listOf(
            scope.launch { runPublishLoop() },
            scope.launch { runExpiryLoop() },
            scope.launch { runIngestLoop() },
        )
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
    }

    /**
     * Enqueues one buffer's worth of already-decoded messages for merging into
     * the aircraft table. Never suspends and never fails to enqueue — with
     * [BufferOverflow.DROP_OLDEST] the channel always accepts a new element by
     * evicting the oldest one if full, which is what makes this safe to call
     * from the IQ read loop without risking a stall there.
     */
    fun offer(messages: List<DecodedMessage>, nowMs: Long = System.currentTimeMillis()) {
        if (messages.isEmpty()) return
        inbox.trySend(Batch(messages, nowMs))
    }

    private suspend fun runIngestLoop() {
        for (batch in inbox) {
            withContext(dispatcher) {
                batch.messages.forEach { msg -> onUpdated(aircraftMgr.update(msg, batch.nowMs)) }
            }
        }
    }

    /**
     * Reads and sorts the aircraft table at most every [publishIntervalMs],
     * emitting only when the result differs from what was last published.
     * `AircraftState`/`List` have structural `equals()`, so "did anything
     * change" is a plain `!=` — no separate diff type needed. This is what
     * decouples UI update rate from frame arrival rate: Session 7's own
     * capture logged ~870 msg/s on real hardware, and a full re-sort that
     * often is wasted work no UI can render anyway.
     */
    private suspend fun runPublishLoop() {
        var lastPublished: List<AircraftState> = emptyList()
        while (true) {
            delay(publishIntervalMs)
            val sorted = withContext(dispatcher) { AircraftSort.apply(aircraftMgr.aircraft, sortOrderProvider()) }
            if (sorted != lastPublished) {
                _aircraft.value = sorted
                lastPublished = sorted
            }
        }
    }

    /** Cadence mirrors the Python reference (`core.py:_run_purge`): a quarter of the expiry window, capped at 15 s. */
    private suspend fun runExpiryLoop() {
        while (true) {
            val window = expirySecondsProvider().coerceAtLeast(1)
            delay(minOf(window * 250L, 15_000L))
            val departed = withContext(dispatcher) {
                aircraftMgr.expirySeconds = window
                aircraftMgr.expireStale()
            }
            if (departed.isNotEmpty()) onDeparted(departed)
        }
    }

    suspend fun setRoute(icao: String, route: String) {
        withContext(dispatcher) { aircraftMgr.setRoute(icao, route) }
    }

    suspend fun setAircraftMeta(icao: String, registration: String?, owner: String?, typeDisplay: String?) {
        withContext(dispatcher) { aircraftMgr.setAircraftMeta(icao, registration, owner, typeDisplay) }
    }

    suspend fun setFaResult(icao: String, route: String?, airlineName: String?, typeDisplay: String?) {
        withContext(dispatcher) { aircraftMgr.setFaResult(icao, route, airlineName, typeDisplay) }
    }

    /** Loads the offline ICAO -> registration/operator/type database once at startup. */
    suspend fun setLookup(entries: Map<String, IcaoEntry>) {
        withContext(dispatcher) { aircraftMgr.setLookup(entries) }
    }

    /** Publishes immediately rather than waiting for the next tick — used when the sort order itself changes. */
    suspend fun publishNow() {
        _aircraft.value = withContext(dispatcher) { AircraftSort.apply(aircraftMgr.aircraft, sortOrderProvider()) }
    }

    /**
     * Aircraft still tracked at reset time are reported via [onDeparted] before the
     * table is cleared — same treatment an expiry sweep would eventually give them.
     * [reset] only ever fires at a genuine session boundary (Start, Reconnect, a
     * dongle replug — see `PipelineService.clearSessionState`'s doc comment), where
     * whatever was being tracked is about to become unreachable anyway; previously
     * this cleared the table with no callback at all, so anything still live at
     * that exact moment was silently dropped and never written to History.
     */
    suspend fun reset() = withContext(dispatcher) {
        val stillTracked = aircraftMgr.aircraft
        aircraftMgr.reset()
        if (stillTracked.isNotEmpty()) onDeparted(stillTracked)
    }

    /**
     * Not confined to [dispatcher]: these are two independent primitive vars on
     * `AircraftManager`, not the table itself, and read only by [runPublishLoop]
     * via the table's own snapshot — a pre-existing property of `AircraftManager`,
     * unchanged by this extraction.
     */
    fun setObserverPosition(lat: Double, lon: Double) {
        aircraftMgr.observerLat = lat
        aircraftMgr.observerLon = lon
    }

    fun setDecoder(decoder: com.laviavi.adsbandroid.decoder.MessageDecoder) {
        aircraftMgr.decoder = decoder
    }

    companion object {
        const val DEFAULT_PUBLISH_INTERVAL_MS = 250L
        const val DEFAULT_INGEST_QUEUE_CAPACITY = 64
    }
}
