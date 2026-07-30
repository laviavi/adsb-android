package com.laviavi.adsbandroid.offline

/**
 * In-memory ports for the offline acceptance tests.
 *
 * Everything the manager touches is faked here, which is the point of the port
 * design: Wi-Fi loss mid-download, a failing disk and a torn manifest write are all
 * ordinary function calls rather than device conditions nobody can reproduce.
 */

class FakeNetwork(var state: NetworkState = NetworkState.WIFI_UNMETERED) : NetworkEligibility {
    /** Flips to [thenState] after [afterCalls] checks — models losing Wi-Fi part way through. */
    var afterCalls: Int? = null
    var thenState: NetworkState = NetworkState.CELLULAR
    var checks = 0

    override fun currentState(): NetworkState {
        checks++
        afterCalls?.let { if (checks > it) return thenState }
        return state
    }
}

class FakeTileStore : TileStore {
    val data = LinkedHashMap<String, ByteArray>()
    /** Keys whose write should throw, for storage-error paths. */
    val failWrites = HashSet<String>()

    override fun has(key: String) = data.containsKey(key)
    override fun storedKeys(): Set<String> = data.keys.toSet()
    override fun write(key: String, bytes: ByteArray) {
        if (key in failWrites) throw java.io.IOException("simulated write failure for $key")
        data[key] = bytes
    }
    override fun read(key: String): ByteArray? = data[key]
    override fun delete(keys: Collection<String>): Int = keys.count { data.remove(it) != null }
    override fun sizeOf(keys: Collection<String>): Long = keys.sumOf { (data[it]?.size ?: 0).toLong() }
    override fun totalBytes(): Long = data.values.sumOf { it.size.toLong() }
}

class FakeDownloader(private val bytesPerTile: Int = 100) : TileDownloader {
    val fetched = mutableListOf<TileRef>()
    /** Tiles the provider refuses, for partial-failure paths. */
    val unavailable = HashSet<String>()

    override suspend fun fetch(tile: TileRef): ByteArray? {
        fetched += tile
        if (tile.key in unavailable) return null
        return ByteArray(bytesPerTile) { 1 }
    }

    fun fetchCountFor(key: String) = fetched.count { it.key == key }
}

class FakeManifestStore : ManifestStore {
    var manifest: OfflineManifest = OfflineManifest.EMPTY
    var travel: TravelLog = TravelLog.EMPTY
    var saves = 0
    /** When set, the next save throws — models a process death mid-write. */
    var failNextSave = false

    override fun load(): OfflineManifest = manifest
    override fun save(manifest: OfflineManifest) {
        if (failNextSave) {
            failNextSave = false
            throw java.io.IOException("simulated manifest write failure")
        }
        saves++
        this.manifest = manifest
    }
    override fun loadTravelLog(): TravelLog = travel
    override fun saveTravelLog(log: TravelLog) { travel = log }
}

class FakeClock(var now: Long = 1_000_000L, var stamp: String = "2026-07-29") : OfflineClock {
    override fun nowMs(): Long = now
    override fun todayStamp(): String = stamp
}

class SequentialIds(private val prefix: String = "id") : IdGenerator {
    private var n = 0
    override fun newId(): String = "$prefix-${++n}"
}

class FixedNamer(private val name: String?) : LocationNamer {
    override suspend fun nameFor(lat: Double, lon: Double): String? = name
}

/** Captures structured log calls so tests can assert what was recorded, not just what happened. */
class RecordingLogger : OfflineLogger {
    data class Entry(val kind: String, val fields: Map<String, Any?>)
    val entries = mutableListOf<Entry>()

    private fun add(kind: String, vararg f: Pair<String, Any?>) { entries += Entry(kind, f.toMap()) }
    fun kinds(): List<String> = entries.map { it.kind }
    fun of(kind: String) = entries.filter { it.kind == kind }

    override fun eligibilityChecked(state: NetworkState, allowed: Boolean, operation: String) =
        add("eligibility", "state" to state, "allowed" to allowed, "op" to operation)
    override fun downloadStarted(segmentId: String, coverageId: String, tileCount: Int, estimatedBytes: Long) =
        add("started", "segment" to segmentId, "tiles" to tileCount, "bytes" to estimatedBytes)
    override fun downloadProgress(segmentId: String, coverageId: String, stored: Int, total: Int, bytes: Long) =
        add("progress", "stored" to stored, "total" to total, "bytes" to bytes)
    override fun downloadPaused(segmentId: String, coverageId: String, reason: String, stored: Int, total: Int) =
        add("paused", "segment" to segmentId, "reason" to reason, "stored" to stored)
    override fun downloadCompleted(segmentId: String, coverageId: String, tileCount: Int, bytes: Long) =
        add("completed", "segment" to segmentId, "tiles" to tileCount, "bytes" to bytes)
    override fun downloadFailed(segmentId: String, coverageId: String, reason: String) =
        add("failed", "segment" to segmentId, "reason" to reason)
    override fun appendDecision(recordId: String, target: String, reason: String, candidates: List<String>) =
        add("append", "record" to recordId, "target" to target, "reason" to reason, "candidates" to candidates)
    override fun segmentDeleted(segmentId: String, name: String, tilesRemoved: Int, tilesRetainedShared: Int) =
        add("deleted", "segment" to segmentId, "name" to name, "removed" to tilesRemoved, "shared" to tilesRetainedShared)
    override fun storageError(operation: String, message: String) =
        add("storageError", "op" to operation, "message" to message)
}

/** Builds a manager wired to fakes, with the pieces exposed for assertions. */
class OfflineHarness(
    val network: FakeNetwork = FakeNetwork(),
    val tiles: FakeTileStore = FakeTileStore(),
    val downloader: FakeDownloader = FakeDownloader(),
    val store: FakeManifestStore = FakeManifestStore(),
    val clock: FakeClock = FakeClock(),
    val ids: IdGenerator = SequentialIds(),
    val logger: RecordingLogger = RecordingLogger(),
    namer: LocationNamer? = FixedNamer("Riverside"),
) {
    val manager = OfflineMapManager(
        store = store,
        tiles = tiles,
        downloader = downloader,
        eligibility = network,
        clock = clock,
        ids = ids,
        namer = namer,
        log = logger,
    )
}

/** A small detail level so tests enumerate tens of tiles, not tens of thousands. */
val TEST_DETAIL = MapDetail.STANDARD
val TINY_ZOOM = 6..7
