package com.laviavi.adsbandroid.offline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/**
 * Owns every offline-map operation: download, append, delete, resume, inspect.
 *
 * All platform contact goes through the ports in `OfflinePorts.kt`, so the whole of
 * this class — including Wi-Fi loss mid-download and interrupted manifest writes —
 * runs in plain JVM tests. Nothing here knows about Android, osmdroid, or Compose,
 * which is what lets the same manager back a CLI and a UI.
 *
 * Two rules hold throughout and are asserted by tests:
 *  - **Nothing is ever deleted implicitly.** No operation removes tiles or segments
 *    except [deleteSegments], which the user drives explicitly.
 *  - **Every operation is idempotent.** Re-running a download or an append requests
 *    only what is missing, so an interrupted run costs the remainder, never a repeat.
 */
class OfflineMapManager(
    private val store: ManifestStore,
    private val tiles: TileStore,
    private val downloader: TileDownloader,
    private val eligibility: NetworkEligibility,
    private val clock: OfflineClock,
    private val ids: IdGenerator,
    private val namer: LocationNamer? = null,
    private val log: OfflineLogger = OfflineLogger.None,
) {

    /**
     * Tiles fetched between eligibility re-checks.
     *
     * Small on purpose: this is the granularity at which Wi-Fi loss is noticed, so a
     * large batch would keep downloading over cellular for however long the batch
     * takes. Twenty tiles is a fraction of a second of transfer.
     */
    private val batchSize = 20

    // ── Inspection ────────────────────────────────────────────────────────────

    fun manifest(): OfflineManifest = store.load()

    fun segments(): List<OfflineSegment> = store.load().segments

    fun travelLog(): TravelLog = store.loadTravelLog()

    /** Total bytes plus the shared-tile saving, for the storage screen. */
    fun storageUsage(): StorageUsage {
        val m = store.load()
        val distinct = m.distinctTileCount
        val summed = m.segments.sumOf { it.storedTileKeys.size }
        return StorageUsage(
            segmentCount = m.segments.size,
            distinctTiles = distinct,
            totalBytes = tiles.sizeOf(m.segments.flatMapTo(HashSet()) { it.storedTileKeys }),
            sharedTiles = (summed - distinct).coerceAtLeast(0),
        )
    }

    /** Estimate shown before a download starts. Costs no network. */
    fun estimateForRadius(lat: Double, lon: Double, radius: OfflineRadius, detail: MapDetail): DownloadEstimate {
        val wanted = TileGeometry.tilesForRadius(lat, lon, radius.nauticalMiles, detail.zoomRange)
        val already = tiles.storedKeys()
        val missing = wanted.filterNot { it.key in already }
        return DownloadEstimate(
            totalTiles = wanted.size,
            newTiles = missing.size,
            estimatedBytes = TileGeometry.estimateBytes(missing.size),
            estimatedBytesLow = TileGeometry.estimateBytesLow(missing.size),
            estimatedBytesHigh = TileGeometry.estimateBytesHigh(missing.size),
            radius = radius,
            detail = detail,
        )
    }

    // ── Download ──────────────────────────────────────────────────────────────

    /**
     * Creates a new segment and downloads its radius.
     *
     * [radiusNm] is taken as the validated enum rather than an Int precisely so an
     * unsupported radius cannot reach here; [downloadNewByNauticalMiles] is the
     * checked entry point for untrusted input.
     */
    suspend fun downloadNew(
        lat: Double,
        lon: Double,
        radius: OfflineRadius,
        detail: MapDetail = MapDetail.DEFAULT,
        explicitName: String? = null,
        onProgress: (DownloadProgress) -> Unit = {},
    ): DownloadOutcome {
        // Gate before anything is created. A rejected request must leave no segment,
        // no coverage entry and no partial state behind.
        val gate = gateFor("downloadNew")
        if (gate is EligibilityResult.Ineligible) {
            return DownloadOutcome.Rejected(gate.reason, gate.state)
        }
        return createAndRun(
            lat = lat, lon = lon, radius = radius, detail = detail,
            tileSet = TileGeometry.tilesForRadius(lat, lon, radius.nauticalMiles, detail.zoomRange),
            explicitName = explicitName, source = null, requireWifi = true, onProgress = onProgress,
        )
    }

    /**
     * Creates the segment and runs its first coverage entry.
     *
     * Shared by download and import so the two cannot drift: naming, collision
     * handling, the initial reconciliation against disk and the manifest shape are
     * defined once. Only the tile set, the source and whether Wi-Fi is required
     * differ between them.
     */
    private suspend fun createAndRun(
        lat: Double,
        lon: Double,
        radius: OfflineRadius,
        detail: MapDetail,
        tileSet: Set<TileRef>,
        explicitName: String?,
        source: TileDownloader?,
        requireWifi: Boolean,
        onProgress: (DownloadProgress) -> Unit,
    ): DownloadOutcome {
        val manifest = store.load()
        val place = explicitName
            ?: namer?.runCatching { nameFor(lat, lon) }?.getOrNull()
            ?: SegmentNaming.coordinateLabel(lat, lon)
        val name = SegmentNaming.uniqueName(
            locationName = place,
            existingNames = manifest.segments.map { it.displayName },
            dateStamp = clock.todayStamp(),
        )

        val wanted = tileSet
        val now = clock.nowMs()
        val coverage = CoverageEntry(
            id = ids.newId(),
            source = CoverageSource.INITIAL_DOWNLOAD,
            tileKeys = wanted.mapTo(LinkedHashSet()) { it.key },
            minZoom = detail.minZoom,
            maxZoom = detail.maxZoom,
            createdAtMs = now,
            state = DownloadState.INCOMPLETE,
            // Tiles already on disk from another segment count as stored immediately —
            // this is what makes overlapping downloads cheap and re-runs idempotent.
            storedTileKeys = wanted.mapNotNull { it.key.takeIf(tiles::has) }.toSet(),
            radiusNm = radius.nauticalMiles,
            centerLat = lat,
            centerLon = lon,
        )
        val segment = OfflineSegment(
            id = ids.newId(),
            displayName = name,
            locationName = place,
            centerLat = lat,
            centerLon = lon,
            requestedRadiusNm = radius.nauticalMiles,
            createdAtMs = now,
            updatedAtMs = now,
            coverage = listOf(coverage),
        )
        store.save(manifest.copy(segments = manifest.segments + segment))

        return runCoverage(segment.id, coverage.id, onProgress, source, requireWifi)
    }

    /**
     * Adopts tiles the device already holds into a managed segment.
     *
     * The map library caches every tile the user views and then trims that cache by
     * age, so coverage someone has actually looked at disappears on its own schedule.
     * Importing copies it somewhere nothing deletes without being asked.
     *
     * **Deliberately not Wi-Fi gated.** The rule protects a data allowance, and this
     * transfers nothing off the device — refusing it on cellular would block an
     * operation that cannot cost anything. Everything else is identical to a
     * download: same segment shape, same idempotency, same manifest.
     */
    suspend fun importFromCache(
        lat: Double,
        lon: Double,
        radius: OfflineRadius,
        source: LocalTileSource,
        detail: MapDetail = MapDetail.DEFAULT,
        explicitName: String? = null,
        onProgress: (DownloadProgress) -> Unit = {},
    ): DownloadOutcome {
        val wanted = TileGeometry.tilesForRadius(lat, lon, radius.nauticalMiles, detail.zoomRange)
        val available = source.availableTiles()
        val importable = wanted.filterTo(LinkedHashSet()) { it in available || tiles.has(it.key) }

        if (importable.isEmpty()) {
            return DownloadOutcome.Failed(
                null,
                "No map areas for this radius have been viewed yet. Open the map around this " +
                    "area first, then import.",
            )
        }
        return createAndRun(
            lat = lat, lon = lon, radius = radius, detail = detail,
            tileSet = importable, explicitName = explicitName,
            source = source, requireWifi = false, onProgress = onProgress,
        )
    }

    /** How much of a radius could be imported from local cache right now. Costs no network. */
    fun estimateImport(
        lat: Double,
        lon: Double,
        radius: OfflineRadius,
        source: LocalTileSource,
        detail: MapDetail = MapDetail.DEFAULT,
    ): DownloadEstimate {
        val wanted = TileGeometry.tilesForRadius(lat, lon, radius.nauticalMiles, detail.zoomRange)
        val available = source.availableTiles()
        val already = tiles.storedKeys()
        val importable = wanted.filter { it in available && it.key !in already }
        return DownloadEstimate(
            totalTiles = wanted.size,
            newTiles = importable.size,
            estimatedBytes = TileGeometry.estimateBytes(importable.size),
            estimatedBytesLow = TileGeometry.estimateBytesLow(importable.size),
            estimatedBytesHigh = TileGeometry.estimateBytesHigh(importable.size),
            radius = radius,
            detail = detail,
        )
    }

    /** Checked entry point. Rejects any radius outside [OfflineRadius] without touching the network. */
    suspend fun downloadNewByNauticalMiles(
        lat: Double,
        lon: Double,
        radiusNm: Int,
        detail: MapDetail = MapDetail.DEFAULT,
        explicitName: String? = null,
        onProgress: (DownloadProgress) -> Unit = {},
    ): DownloadOutcome {
        val radius = OfflineRadius.fromNauticalMiles(radiusNm)
            ?: return DownloadOutcome.Failed(
                null,
                "Unsupported radius ${radiusNm} NM. Choose 90, 150 or 250 NM.",
            )
        return downloadNew(lat, lon, radius, detail, explicitName, onProgress)
    }

    /**
     * Resumes an unfinished coverage entry.
     *
     * Safe to call on a complete entry — it re-checks what is on disk and returns
     * [DownloadOutcome.NothingToDo] rather than re-fetching.
     */
    suspend fun resume(
        segmentId: String,
        coverageId: String,
        onProgress: (DownloadProgress) -> Unit = {},
    ): DownloadOutcome {
        val gate = gateFor("resume")
        if (gate is EligibilityResult.Ineligible) return DownloadOutcome.Rejected(gate.reason, gate.state)
        return runCoverage(segmentId, coverageId, onProgress)
    }

    /** Every coverage entry with outstanding tiles, for a "resume all" affordance. */
    fun resumableCoverage(): List<Pair<OfflineSegment, CoverageEntry>> =
        store.load().segments.flatMap { seg ->
            seg.coverage.filter { !it.isComplete }.map { seg to it }
        }

    // ── Append ────────────────────────────────────────────────────────────────

    /**
     * Adds travelled coverage to an existing segment.
     *
     * Only the tiles missing from disk are requested, and the append is recorded as a
     * new [CoverageEntry] — the original segment definition is never rewritten, so an
     * append cannot shrink or invalidate what was already downloaded.
     */
    suspend fun appendTravelCoverage(
        recordId: String,
        targetSegmentId: String,
        detail: MapDetail = MapDetail.DEFAULT,
        corridorNm: Int = TravelTracker.DEFAULT_CORRIDOR_NM,
        onProgress: (DownloadProgress) -> Unit = {},
    ): DownloadOutcome {
        val gate = gateFor("append")
        if (gate is EligibilityResult.Ineligible) return DownloadOutcome.Rejected(gate.reason, gate.state)

        val travel = store.loadTravelLog()
        val record = travel.records.firstOrNull { it.id == recordId }
            ?: return DownloadOutcome.Failed(targetSegmentId, "Travel record not found.")
        val manifest = store.load()
        val segment = manifest.segment(targetSegmentId)
            ?: return DownloadOutcome.Failed(targetSegmentId, "Segment not found.")

        val needed = TileGeometry.tilesForRoute(record.latLonPath, corridorNm, detail.zoomRange)
        // Subtract what the segment already covers *and* what is on disk from any
        // other segment. Re-running an append therefore converges on doing nothing.
        val already = segment.allTileKeys + tiles.storedKeys()
        val missing = needed.filterNot { it.key in already }

        if (missing.isEmpty()) {
            markTravelHandled(recordId)
            return DownloadOutcome.NothingToDo(targetSegmentId)
        }

        val now = clock.nowMs()
        val entry = CoverageEntry(
            id = ids.newId(),
            source = CoverageSource.APPENDED_TRAVEL,
            tileKeys = missing.mapTo(LinkedHashSet()) { it.key },
            minZoom = detail.minZoom,
            maxZoom = detail.maxZoom,
            createdAtMs = now,
            state = DownloadState.INCOMPLETE,
            note = record.destinationName?.let { "Travel to $it" },
        )
        val updated = segment.copy(
            coverage = segment.coverage + entry,
            updatedAtMs = now,
        )
        store.save(manifest.copy(segments = manifest.segments.map { if (it.id == segment.id) updated else it }))

        val outcome = runCoverage(segment.id, entry.id, onProgress)
        if (outcome is DownloadOutcome.Completed) markTravelHandled(recordId)
        return outcome
    }

    /** Applies the deterministic target rules to a pending travel record. */
    fun chooseAppendTarget(recordId: String, detail: MapDetail = MapDetail.DEFAULT): AppendTarget? {
        val record = store.loadTravelLog().records.firstOrNull { it.id == recordId } ?: return null
        val target = AppendTargeting.choose(record, store.load().segments, detail.zoomRange)
        log.appendDecision(
            recordId = recordId,
            target = when (target) {
                is AppendTarget.Segment -> target.segmentId
                is AppendTarget.AmbiguousChoice -> "ambiguous"
                is AppendTarget.CreateNew -> "create-new"
            },
            reason = when (target) {
                is AppendTarget.Segment -> target.reason.name
                is AppendTarget.AmbiguousChoice -> "TIE"
                is AppendTarget.CreateNew -> "NO_SUITABLE_SEGMENT"
            },
            candidates = (target as? AppendTarget.AmbiguousChoice)?.candidateSegmentIds ?: emptyList(),
        )
        return target
    }

    // ── Travel tracking ───────────────────────────────────────────────────────

    /**
     * Records a position if it falls outside existing coverage.
     *
     * Never downloads and never checks the network — the whole point is that this
     * runs while travelling on cellular, accumulating only a note. Returns the active
     * record when one is open.
     */
    fun observePosition(lat: Double, lon: Double): TravelRecord? {
        val manifest = store.load()
        val outside = TravelTracker.isOutsideCoverage(lat, lon, manifest.segments)
        val travel = store.loadTravelLog()
        val open = travel.records.lastOrNull()?.takeIf { !it.deferred }
        val now = clock.nowMs()

        if (!outside) {
            // Back inside coverage: leave any open record alone so it can be offered
            // on the next Wi-Fi. Nothing is closed or discarded here.
            return open
        }

        return if (open == null) {
            val origin = manifest.segments.firstOrNull { it.contains(lat, lon) }?.id
            val record = TravelRecord(
                id = ids.newId(),
                path = listOf(TravelPoint(lat, lon, now)),
                startedAtMs = now,
                lastUpdatedAtMs = now,
                originSegmentId = origin,
            )
            store.saveTravelLog(travel.copy(records = travel.records + record))
            record
        } else {
            val updated = TravelTracker.appendPoint(open, lat, lon, now)
            if (updated !== open) {
                store.saveTravelLog(
                    travel.copy(records = travel.records.map { if (it.id == open.id) updated else it }),
                )
            }
            updated
        }
    }

    /** Records the resolved destination name, used to name a new segment if one is needed. */
    fun setTravelDestinationName(recordId: String, name: String) {
        val travel = store.loadTravelLog()
        store.saveTravelLog(
            travel.copy(
                records = travel.records.map { if (it.id == recordId) it.copy(destinationName = name) else it },
            ),
        )
    }

    /** "Not now" — keeps the record but stops it prompting. */
    fun deferTravelSuggestion(recordId: String) {
        val travel = store.loadTravelLog()
        store.saveTravelLog(
            travel.copy(records = travel.records.map { if (it.id == recordId) it.copy(deferred = true) else it }),
        )
    }

    /** "Dismiss" — drops the suggestion. Removes only the note; no map data is touched. */
    fun dismissTravelSuggestion(recordId: String) {
        val travel = store.loadTravelLog()
        store.saveTravelLog(travel.copy(records = travel.records.filterNot { it.id == recordId }))
    }

    private fun markTravelHandled(recordId: String) {
        val travel = store.loadTravelLog()
        store.saveTravelLog(travel.copy(records = travel.records.filterNot { it.id == recordId }))
    }

    /** Pending suggestions worth prompting about — only meaningful once on Wi-Fi. */
    fun pendingTravelSuggestions(): List<TravelRecord> =
        store.loadTravelLog().pending().filter { it.path.size >= 2 }

    // ── Deletion ──────────────────────────────────────────────────────────────

    /** What the confirmation screen shows before anything is removed. */
    fun deletionPreview(segmentIds: Collection<String>): DeletionPreview {
        val manifest = store.load()
        val targets = manifest.segments.filter { it.id in segmentIds }
        val othersTiles = manifest.segments.filterNot { it.id in segmentIds }
            .flatMapTo(HashSet()) { it.storedTileKeys }
        val selectedTiles = targets.flatMapTo(HashSet()) { it.storedTileKeys }
        val exclusive = selectedTiles - othersTiles
        return DeletionPreview(
            segments = targets.map { seg ->
                DeletionPreview.Item(
                    id = seg.id,
                    name = seg.displayName,
                    location = seg.locationName,
                    bytes = tiles.sizeOf(seg.storedTileKeys),
                    createdAtMs = seg.createdAtMs,
                    updatedAtMs = seg.updatedAtMs,
                    hasAppendedCoverage = seg.hasAppendedCoverage,
                )
            },
            tilesToRemove = exclusive.size,
            tilesRetainedShared = (selectedTiles - exclusive).size,
            bytesFreed = tiles.sizeOf(exclusive),
        )
    }

    /**
     * Removes the named segments and only the tiles they exclusively own.
     *
     * Shared tiles are retained while any remaining segment references them, which is
     * why deletion consults the manifest rather than deleting the segment's tile list
     * outright — the latter would silently punch holes in a neighbouring region.
     */
    fun deleteSegments(segmentIds: Collection<String>): DeletionResult {
        val manifest = store.load()
        val targets = manifest.segments.filter { it.id in segmentIds }
        if (targets.isEmpty()) return DeletionResult(0, 0, 0L)

        val remaining = manifest.segments.filterNot { it.id in segmentIds }
        val retainedTiles = remaining.flatMapTo(HashSet()) { it.storedTileKeys }
        val selectedTiles = targets.flatMapTo(HashSet()) { it.storedTileKeys }
        val exclusive = selectedTiles - retainedTiles
        val freed = tiles.sizeOf(exclusive)

        // Manifest first: if the process dies between the two, the tiles are orphaned
        // but no segment claims content that is gone. The reverse order would leave a
        // segment pointing at deleted tiles, which reads as corruption to the renderer.
        store.save(manifest.copy(segments = remaining))
        val removed = runCatching { tiles.delete(exclusive) }
            .onFailure { log.storageError("deleteSegments", it.message ?: it.javaClass.simpleName) }
            .getOrDefault(0)

        targets.forEach {
            log.segmentDeleted(it.id, it.displayName, removed, (selectedTiles - exclusive).size)
        }
        return DeletionResult(
            segmentsRemoved = targets.size,
            tilesRemoved = removed,
            bytesFreed = freed,
        )
    }

    /** Removes tiles on disk that no segment references. Never runs automatically. */
    fun pruneOrphanedTiles(): Int {
        val referenced = store.load().segments.flatMapTo(HashSet()) { it.storedTileKeys }
        val orphans = tiles.storedKeys() - referenced
        if (orphans.isEmpty()) return 0
        return runCatching { tiles.delete(orphans) }
            .onFailure { log.storageError("pruneOrphanedTiles", it.message ?: "") }
            .getOrDefault(0)
    }

    // ── Rendering support ─────────────────────────────────────────────────────

    /** True when a tile is available offline, regardless of which segment supplied it. */
    fun hasTileOffline(tile: TileRef): Boolean = tiles.has(tile.key)

    fun readTile(tile: TileRef): ByteArray? = tiles.read(tile.key)

    // ── Internals ─────────────────────────────────────────────────────────────

    /**
     * Reads the network once, evaluates it, and logs that one reading.
     *
     * Deliberately a single [NetworkEligibility.currentState] call: asking twice —
     * once to decide and once to log — can straddle a network change, so the log
     * would record a state the decision was never based on, and every extra read is
     * a chance for the two to disagree.
     */
    private fun gateFor(operation: String): EligibilityResult {
        val state = eligibility.currentState()
        val result = OfflineDownloadPolicy.evaluate(state)
        log.eligibilityChecked(state, result.isEligible, operation)
        return result
    }

    /**
     * Fetches one coverage entry's outstanding tiles in batches.
     *
     * The eligibility re-check sits at the top of each batch, so losing Wi-Fi part way
     * stops the run within at most [batchSize] tiles instead of continuing on whatever
     * the device fell back to. On stopping, everything already written stays written
     * and the entry is marked resumable — no rollback, because a partial download is
     * still useful coverage.
     */
    private suspend fun runCoverage(
        segmentId: String,
        coverageId: String,
        onProgress: (DownloadProgress) -> Unit,
        sourceOverride: TileDownloader? = null,
        requireWifi: Boolean = true,
    ): DownloadOutcome {
        val fetcher = sourceOverride ?: downloader
        var manifest = store.load()
        var segment = manifest.segment(segmentId)
            ?: return DownloadOutcome.Failed(segmentId, "Segment not found.")
        var entry = segment.coverage.firstOrNull { it.id == coverageId }
            ?: return DownloadOutcome.Failed(segmentId, "Coverage entry not found.")

        // Reconcile against disk before deciding what is outstanding: a previous run
        // may have written tiles whose manifest update never landed.
        val onDisk = entry.tileKeys.filterTo(LinkedHashSet()) { tiles.has(it) }
        var stored = LinkedHashSet(entry.storedTileKeys + onDisk)
        var pending = entry.tileKeys - stored

        if (pending.isEmpty()) {
            entry = entry.copy(
                state = DownloadState.COMPLETE,
                storedTileKeys = stored,
                bytesStored = tiles.sizeOf(stored),
            )
            persist(segmentId, entry)
            return DownloadOutcome.NothingToDo(segmentId)
        }

        log.downloadStarted(segmentId, coverageId, pending.size, TileGeometry.estimateBytes(pending.size))

        var bytes = entry.bytesStored
        val queue = pending.toMutableList()
        var failures = 0

        try {
            while (queue.isNotEmpty()) {
                if (!currentCoroutineContext().isActive) {
                    return pauseWith(segmentId, coverageId, entry, stored, bytes, "Cancelled")
                }
                // Re-checked per batch, not once at the start: the answer when the user
                // pressed the button says nothing about the network 30 seconds later.
                // Skipped entirely for a local import, which moves no bytes off-device.
                val state = eligibility.currentState()
                if (requireWifi) {
                    log.eligibilityChecked(state, OfflineDownloadPolicy.isDownloadAllowed(state), "batch")
                    if (!OfflineDownloadPolicy.isDownloadAllowed(state)) {
                        return pauseWith(
                            segmentId, coverageId, entry, stored, bytes,
                            "Wi-Fi unavailable — download paused. Already downloaded areas are kept.",
                        )
                    }
                }

                val batch = queue.take(batchSize)
                for (key in batch) {
                    val tile = TileRef.parse(key) ?: continue
                    val data = runCatching { fetcher.fetch(tile) }.getOrNull()
                    if (data == null) {
                        failures++
                        continue
                    }
                    runCatching { tiles.write(key, data) }
                        .onFailure {
                            log.storageError("write", it.message ?: it.javaClass.simpleName)
                            failures++
                        }
                        .onSuccess {
                            stored += key
                            bytes += data.size
                        }
                }
                queue.subList(0, batch.size).clear()

                entry = entry.copy(storedTileKeys = LinkedHashSet(stored), bytesStored = bytes)
                persist(segmentId, entry)
                log.downloadProgress(segmentId, coverageId, stored.size, entry.tileKeys.size, bytes)
                onProgress(
                    DownloadProgress(
                        segmentId = segmentId,
                        coverageId = coverageId,
                        storedTiles = stored.size,
                        totalTiles = entry.tileKeys.size,
                        bytesStored = bytes,
                        estimatedTotalBytes = TileGeometry.estimateBytes(entry.tileKeys.size),
                        networkState = state,
                    ),
                )
            }
        } catch (e: CancellationException) {
            pauseWith(segmentId, coverageId, entry, stored, bytes, "Cancelled")
            throw e
        } catch (e: Exception) {
            log.downloadFailed(segmentId, coverageId, e.message ?: e.javaClass.simpleName)
            entry = entry.copy(
                state = DownloadState.FAILED,
                storedTileKeys = LinkedHashSet(stored),
                bytesStored = bytes,
            )
            persist(segmentId, entry)
            return DownloadOutcome.Failed(segmentId, e.message ?: "Download failed.")
        }

        val complete = stored.containsAll(entry.tileKeys)
        entry = entry.copy(
            state = if (complete) DownloadState.COMPLETE else DownloadState.INCOMPLETE,
            storedTileKeys = LinkedHashSet(stored),
            bytesStored = bytes,
        )
        persist(segmentId, entry)

        return if (complete) {
            log.downloadCompleted(segmentId, coverageId, stored.size, bytes)
            DownloadOutcome.Completed(segmentId, stored.size, bytes)
        } else {
            // Tiles the provider would not serve. Resumable, and nothing is discarded.
            log.downloadPaused(segmentId, coverageId, "$failures tiles unavailable", stored.size, entry.tileKeys.size)
            DownloadOutcome.Paused(segmentId, stored.size, entry.tileKeys.size, "$failures tiles could not be fetched.")
        }
    }

    private fun pauseWith(
        segmentId: String,
        coverageId: String,
        entry: CoverageEntry,
        stored: Set<String>,
        bytes: Long,
        reason: String,
    ): DownloadOutcome {
        val paused = entry.copy(
            state = DownloadState.PAUSED,
            storedTileKeys = LinkedHashSet(stored),
            bytesStored = bytes,
        )
        persist(segmentId, paused)
        log.downloadPaused(segmentId, coverageId, reason, stored.size, entry.tileKeys.size)
        return DownloadOutcome.Paused(segmentId, stored.size, entry.tileKeys.size, reason)
    }

    /** Writes one coverage entry back. Reloads first so a concurrent edit is not clobbered. */
    private fun persist(segmentId: String, entry: CoverageEntry) {
        val manifest = store.load()
        val segment = manifest.segment(segmentId) ?: return
        val updated = segment.copy(
            coverage = segment.coverage.map { if (it.id == entry.id) entry else it },
            updatedAtMs = clock.nowMs(),
        )
        runCatching { store.save(manifest.copy(segments = manifest.segments.map { if (it.id == segmentId) updated else it })) }
            .onFailure { log.storageError("saveManifest", it.message ?: it.javaClass.simpleName) }
    }
}

data class StorageUsage(
    val segmentCount: Int,
    val distinctTiles: Int,
    val totalBytes: Long,
    /** Tiles referenced by more than one segment — stored once, counted once. */
    val sharedTiles: Int,
)

data class DownloadEstimate(
    val totalTiles: Int,
    val newTiles: Int,
    val estimatedBytes: Long,
    val estimatedBytesLow: Long,
    val estimatedBytesHigh: Long,
    val radius: OfflineRadius,
    val detail: MapDetail,
) {
    val alreadyStoredTiles: Int get() = totalTiles - newTiles
    val rangeLabel: String
        get() = "${TileGeometry.formatBytes(estimatedBytesLow)} – ${TileGeometry.formatBytes(estimatedBytesHigh)}"
}

data class DeletionPreview(
    val segments: List<Item>,
    val tilesToRemove: Int,
    val tilesRetainedShared: Int,
    val bytesFreed: Long,
) {
    data class Item(
        val id: String,
        val name: String,
        val location: String,
        val bytes: Long,
        val createdAtMs: Long,
        val updatedAtMs: Long,
        val hasAppendedCoverage: Boolean,
    )
}

data class DeletionResult(
    val segmentsRemoved: Int,
    val tilesRemoved: Int,
    val bytesFreed: Long,
)
