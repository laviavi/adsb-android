package com.laviavi.adsbandroid.offline

/**
 * The platform seams the offline manager depends on.
 *
 * Every one of these is an interface so the manager is exercised end to end on the
 * JVM — network states, Wi-Fi loss mid-download, interrupted manifest writes and
 * shared-tile deletion are all reproducible with fakes, none of which is true if the
 * manager reaches for a file system or a socket directly.
 */

/** Bytes on disk, keyed by [TileRef.key]. Implemented over the file system in `:app`. */
interface TileStore {
    fun has(key: String): Boolean
    fun storedKeys(): Set<String>
    fun write(key: String, bytes: ByteArray)
    fun read(key: String): ByteArray?
    /** Removes only the keys given. Callers are responsible for reference counting. */
    fun delete(keys: Collection<String>): Int
    fun sizeOf(keys: Collection<String>): Long
    fun totalBytes(): Long
}

/** Fetches one tile. Provider-agnostic so the tile source can be swapped without touching policy. */
interface TileDownloader {
    /** Null means "not retrieved" — the caller keeps the tile in the pending set rather than failing the run. */
    suspend fun fetch(tile: TileRef): ByteArray?
}

/**
 * A tile source already present on the device — no network, no provider, no transfer.
 *
 * Separated from [TileDownloader] because it changes what the Wi-Fi rule means. That
 * rule exists to stop the app spending someone's data allowance; copying bytes that
 * are already on the phone spends none, so an import is permitted on any connection
 * including none at all. [availableTiles] makes the size knowable up front, which a
 * network source cannot promise.
 */
interface LocalTileSource : TileDownloader {
    fun availableTiles(): Set<TileRef>
}

/**
 * Persists the manifest and the travel log.
 *
 * [save] must be atomic — write-temp-then-rename, or an equivalent — because a
 * manifest torn by a process death is the one failure that could orphan every tile
 * on disk. Recovery from a partial write is an acceptance case.
 */
interface ManifestStore {
    fun load(): OfflineManifest
    fun save(manifest: OfflineManifest)
    fun loadTravelLog(): TravelLog
    fun saveTravelLog(log: TravelLog)
}

/** Monotonic-ish clock seam, so timestamps and date-stamped names are deterministic in tests. */
interface OfflineClock {
    fun nowMs(): Long
    /** `yyyy-MM-dd`, used by the duplicate-name fallback. */
    fun todayStamp(): String
}

/** Supplies unique ids. Injected so segment and coverage ids are stable under test. */
interface IdGenerator {
    fun newId(): String
}

/**
 * Structured logging for every decision worth auditing after the fact.
 *
 * Deliberately its own port rather than a string logger: the questions asked later
 * are "why did this append land on that segment" and "what was the network when
 * this started", and those need fields, not prose.
 */
interface OfflineLogger {
    fun eligibilityChecked(state: NetworkState, allowed: Boolean, operation: String)
    fun downloadStarted(segmentId: String, coverageId: String, tileCount: Int, estimatedBytes: Long)
    fun downloadProgress(segmentId: String, coverageId: String, stored: Int, total: Int, bytes: Long)
    fun downloadPaused(segmentId: String, coverageId: String, reason: String, stored: Int, total: Int)
    fun downloadCompleted(segmentId: String, coverageId: String, tileCount: Int, bytes: Long)
    fun downloadFailed(segmentId: String, coverageId: String, reason: String)
    fun appendDecision(recordId: String, target: String, reason: String, candidates: List<String>)
    fun segmentDeleted(segmentId: String, name: String, tilesRemoved: Int, tilesRetainedShared: Int)
    fun storageError(operation: String, message: String)

    /** No-op sink for tests and for callers that do not care. */
    object None : OfflineLogger {
        override fun eligibilityChecked(state: NetworkState, allowed: Boolean, operation: String) {}
        override fun downloadStarted(segmentId: String, coverageId: String, tileCount: Int, estimatedBytes: Long) {}
        override fun downloadProgress(segmentId: String, coverageId: String, stored: Int, total: Int, bytes: Long) {}
        override fun downloadPaused(segmentId: String, coverageId: String, reason: String, stored: Int, total: Int) {}
        override fun downloadCompleted(segmentId: String, coverageId: String, tileCount: Int, bytes: Long) {}
        override fun downloadFailed(segmentId: String, coverageId: String, reason: String) {}
        override fun appendDecision(recordId: String, target: String, reason: String, candidates: List<String>) {}
        override fun segmentDeleted(segmentId: String, name: String, tilesRemoved: Int, tilesRetainedShared: Int) {}
        override fun storageError(operation: String, message: String) {}
    }
}

/** Emitted during a download so the UI can show percent, bytes and remaining count. */
data class DownloadProgress(
    val segmentId: String,
    val coverageId: String,
    val storedTiles: Int,
    val totalTiles: Int,
    val bytesStored: Long,
    val estimatedTotalBytes: Long,
    val networkState: NetworkState,
) {
    val percent: Int get() = if (totalTiles == 0) 100 else storedTiles * 100 / totalTiles
    val remainingTiles: Int get() = (totalTiles - storedTiles).coerceAtLeast(0)
}

/** Terminal outcome of a download or append. */
sealed interface DownloadOutcome {
    data class Completed(val segmentId: String, val tilesStored: Int, val bytes: Long) : DownloadOutcome
    /** Stopped with work outstanding. Everything already written is kept and is resumable. */
    data class Paused(val segmentId: String, val stored: Int, val total: Int, val reason: String) : DownloadOutcome
    /** Refused before any network use — nothing was started, queued or partially written. */
    data class Rejected(val reason: String, val state: NetworkState) : DownloadOutcome
    data class Failed(val segmentId: String?, val reason: String) : DownloadOutcome
    /** Every requested tile was already on disk. */
    data class NothingToDo(val segmentId: String) : DownloadOutcome
}
