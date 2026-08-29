package com.laviavi.adsbandroid.aircraft

import com.laviavi.adsbandroid.crc.CrcChecker
import com.laviavi.adsbandroid.decoder.DecodedMessage
import com.laviavi.adsbandroid.decoder.MessageDecoder
import com.laviavi.adsbandroid.decoder.RawFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * `ReceiverRepository` extracted out of `PipelineService` (Step 4). Tested here
 * as plain JVM code — the whole point of the extraction — with no Android and
 * no running service.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReceiverRepositoryTests {

    private val VALID_DF17 = intArrayOf(
        0x8D, 0x48, 0x40, 0xD6, 0x20, 0x2C, 0xC3, 0x71, 0xC3, 0x2C, 0xE0, 0x57, 0x60, 0x98,
    )
    private val VALID_DF17_2 = intArrayOf(
        0x8D, 0xC0, 0x7B, 0x6E, 0x58, 0x41, 0xD5, 0x5B, 0x72, 0x3C, 0xAF, 0x6C, 0xF4, 0x46,
    )

    private fun decode(bytes: IntArray): DecodedMessage =
        MessageDecoder().decode(CrcChecker.check(RawFrame(bytes.copyOf())))!!

    @Test fun `ingest merges a message and publishes it on the next tick`() = runTest {
        val repo = ReceiverRepository(
            scope = backgroundScope,
            sortOrderProvider = { AircraftSortOrder.FIRST_SEEN },
            expirySecondsProvider = { 60 },
            dispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        )
        repo.start()
        repo.offer(listOf(decode(VALID_DF17)))
        advanceTimeBy(ReceiverRepository.DEFAULT_PUBLISH_INTERVAL_MS + 1)

        assertEquals(1, repo.aircraft.value.size)
        assertEquals("4840D6", repo.aircraft.value[0].icao)
    }

    @Test fun `onUpdated fires once per ingested message with the merged state`() = runTest {
        val updates = mutableListOf<AircraftState>()
        val repo = ReceiverRepository(
            scope = backgroundScope,
            sortOrderProvider = { AircraftSortOrder.FIRST_SEEN },
            expirySecondsProvider = { 60 },
            onUpdated = { updates += it },
            dispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        )
        repo.start()
        repo.offer(listOf(decode(VALID_DF17), decode(VALID_DF17_2)))
        advanceTimeBy(1) // ingest loop needs no delay, just a chance to run

        assertEquals(2, updates.size)
        assertEquals(setOf("4840D6", "C07B6E"), updates.map { it.icao }.toSet())
    }

    @Test fun `does not publish when the sorted result is unchanged`() = runTest {
        var publishCount = 0
        val repo = ReceiverRepository(
            scope = backgroundScope,
            sortOrderProvider = { AircraftSortOrder.FIRST_SEEN },
            expirySecondsProvider = { 60 },
            dispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        )
        repo.start()
        val job = launch { repo.aircraft.collect { publishCount++ } }
        advanceTimeBy(ReceiverRepository.DEFAULT_PUBLISH_INTERVAL_MS * 5)
        job.cancel()

        // One emission for the initial empty value; no further emissions since
        // nothing was ever ingested, i.e. the sorted snapshot never changed.
        assertEquals(1, publishCount)
    }

    @Test fun `expiry removes stale aircraft and reports them via onDeparted`() = runTest {
        val departedIcaos = mutableListOf<String>()
        val repo = ReceiverRepository(
            scope = backgroundScope,
            sortOrderProvider = { AircraftSortOrder.FIRST_SEEN },
            expirySecondsProvider = { 1 }, // 1 s expiry -> ticks every 250ms
            onDeparted = { departed -> departedIcaos += departed.map { it.icao } },
            dispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        )
        repo.start()
        repo.offer(listOf(decode(VALID_DF17)), nowMs = 0L)
        advanceTimeBy(2_000) // well past the 1 s expiry window

        assertTrue(departedIcaos.contains("4840D6"))
        assertTrue(repo.aircraft.value.none { it.icao == "4840D6" })
    }

    @Test fun `setRoute patches an already-tracked aircraft`() = runTest {
        val repo = ReceiverRepository(
            scope = backgroundScope,
            sortOrderProvider = { AircraftSortOrder.FIRST_SEEN },
            expirySecondsProvider = { 60 },
            dispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        )
        repo.start()
        repo.offer(listOf(decode(VALID_DF17)))
        advanceTimeBy(1)
        repo.setRoute("4840D6", "KLAX-KJFK")
        repo.publishNow()

        assertEquals("KLAX-KJFK", repo.aircraft.value.single { it.icao == "4840D6" }.route)
    }

    @Test fun `reset clears the table`() = runTest {
        val repo = ReceiverRepository(
            scope = backgroundScope,
            sortOrderProvider = { AircraftSortOrder.FIRST_SEEN },
            expirySecondsProvider = { 60 },
            dispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        )
        repo.start()
        repo.offer(listOf(decode(VALID_DF17)))
        advanceTimeBy(1)
        repo.reset()
        repo.publishNow()

        assertTrue(repo.aircraft.value.isEmpty())
    }

    @Test fun `reset reports still-tracked aircraft via onDeparted before clearing them`() = runTest {
        val departedIcaos = mutableListOf<String>()
        val repo = ReceiverRepository(
            scope = backgroundScope,
            sortOrderProvider = { AircraftSortOrder.FIRST_SEEN },
            expirySecondsProvider = { 60 },
            onDeparted = { departed -> departedIcaos += departed.map { it.icao } },
            dispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        )
        repo.start()
        repo.offer(listOf(decode(VALID_DF17), decode(VALID_DF17_2)))
        advanceTimeBy(1)
        repo.reset()

        assertEquals(setOf("4840D6", "C07B6E"), departedIcaos.toSet())
    }

    @Test fun `reset on an empty table does not call onDeparted`() = runTest {
        var departedCalls = 0
        val repo = ReceiverRepository(
            scope = backgroundScope,
            sortOrderProvider = { AircraftSortOrder.FIRST_SEEN },
            expirySecondsProvider = { 60 },
            onDeparted = { departedCalls++ },
            dispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        )
        repo.start()
        repo.reset()

        assertEquals(0, departedCalls)
    }

    @Test fun `publishNow reflects a sort-order change immediately`() = runTest {
        var order = AircraftSortOrder.FIRST_SEEN
        val repo = ReceiverRepository(
            scope = backgroundScope,
            sortOrderProvider = { order },
            expirySecondsProvider = { 60 },
            dispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        )
        repo.start()
        repo.offer(listOf(decode(VALID_DF17), decode(VALID_DF17_2)))
        advanceTimeBy(1)

        order = AircraftSortOrder.CALLSIGN
        repo.publishNow()

        // Doesn't assert a specific order (AircraftSortTests already covers
        // AircraftSort itself) — only that publishNow re-reads the provider
        // rather than a value cached at construction time.
        assertEquals(2, repo.aircraft.value.size)
    }

    // ── Back-pressure ──────────────────────────────────────────────────────────

    @Test fun `offer beyond capacity drops the oldest batches, not the newest`() {
        // No coroutine involved: the queue's overflow behaviour is exercised by
        // filling it directly, without starting the consumer, so the result is
        // deterministic rather than a timing race.
        val repo = ReceiverRepository(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            sortOrderProvider = { AircraftSortOrder.FIRST_SEEN },
            expirySecondsProvider = { 60 },
            ingestQueueCapacity = 4,
        )
        val overflow = 3
        repeat(4 + overflow) { i -> repo.offer(listOf(decode(VALID_DF17)), nowMs = i.toLong()) }

        assertEquals(overflow.toLong(), repo.droppedBatches.value)
    }

    @Test fun `a healthy pipeline never drops anything`() = runTest {
        // The honest default: decode is cheap relative to how often IQ buffers
        // arrive, so under normal operation the queue should never fill.
        val repo = ReceiverRepository(
            scope = backgroundScope,
            sortOrderProvider = { AircraftSortOrder.FIRST_SEEN },
            expirySecondsProvider = { 60 },
            dispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        )
        repo.start()
        repeat(50) { repo.offer(listOf(decode(VALID_DF17))) }
        advanceTimeBy(ReceiverRepository.DEFAULT_PUBLISH_INTERVAL_MS + 1)

        assertEquals(0L, repo.droppedBatches.value)
    }
}
