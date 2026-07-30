package com.laviavi.adsbandroid.ui.offline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laviavi.adsbandroid.offline.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Screen state for Offline Maps.
 *
 * Presentation only — every rule (Wi-Fi gating, append targeting, deletion safety)
 * lives in [OfflineMapManager], so this class can be replaced by a CLI without any
 * behaviour moving with it.
 */
data class OfflineMapsUiState(
    val segments: List<OfflineSegment> = emptyList(),
    val usage: StorageUsage = StorageUsage(0, 0, 0L, 0),
    val networkState: NetworkState = NetworkState.UNKNOWN,
    val pendingSuggestions: List<TravelRecord> = emptyList(),
    val resumable: List<Pair<OfflineSegment, CoverageEntry>> = emptyList(),

    // Download flow
    val selectedRadius: OfflineRadius? = null,
    val selectedDetail: MapDetail = MapDetail.DEFAULT,
    val estimate: DownloadEstimate? = null,
    /** How much of the chosen radius is already in the map's own cache and can be adopted now. */
    val importEstimate: DownloadEstimate? = null,
    val downloadConfigured: Boolean = false,
    val activeProgress: DownloadProgress? = null,

    // Deletion flow
    val selectedForDeletion: Set<String> = emptySet(),
    val deletionPreview: DeletionPreview? = null,

    val message: String? = null,
) {
    val wifiReady: Boolean get() = OfflineDownloadPolicy.isDownloadAllowed(networkState)
    /** The radius is chosen before a download can begin. */
    val canStartDownload: Boolean
        get() = selectedRadius != null && wifiReady && downloadConfigured && activeProgress == null
    /** Import needs no network and no endpoint — only tiles the map has already cached. */
    val canImport: Boolean
        get() = selectedRadius != null && (importEstimate?.newTiles ?: 0) > 0 && activeProgress == null
    val isDownloading: Boolean get() = activeProgress != null
}

@HiltViewModel
class OfflineMapsViewModel @Inject constructor(
    private val manager: OfflineMapManager,
    private val eligibility: NetworkEligibility,
    private val cacheSource: LocalTileSource,
    private val configStore: com.laviavi.adsbandroid.pipeline.AppConfigStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OfflineMapsUiState())
    val uiState: StateFlow<OfflineMapsUiState> = _uiState.asStateFlow()

    private var downloadJob: Job? = null

    init { refresh() }

    fun refresh() {
        _uiState.value = _uiState.value.copy(
            segments = manager.segments(),
            usage = manager.storageUsage(),
            networkState = eligibility.currentState(),
            pendingSuggestions = manager.pendingTravelSuggestions(),
            resumable = manager.resumableCoverage(),
        )
        viewModelScope.launch {
            val configured = runCatching { configStore.load().offlineDownloadConfigured }.getOrDefault(false)
            _uiState.value = _uiState.value.copy(downloadConfigured = configured)
        }
    }

    // ── Download ──────────────────────────────────────────────────────────────

    /** Radius must be picked before an estimate exists, and no download starts without one. */
    fun selectRadius(radius: OfflineRadius, lat: Double, lon: Double) {
        _uiState.value = _uiState.value.copy(selectedRadius = radius)
        recomputeEstimates(lat, lon)
    }

    fun selectDetail(detail: MapDetail, lat: Double, lon: Double) {
        _uiState.value = _uiState.value.copy(selectedDetail = detail)
        recomputeEstimates(lat, lon)
    }

    /**
     * Both estimates are computed off the main thread: the import figure enumerates
     * every key in the map's tile cache, which is a disk-backed scan and can be tens
     * of thousands of rows.
     */
    private fun recomputeEstimates(lat: Double, lon: Double) {
        val radius = _uiState.value.selectedRadius ?: return
        val detail = _uiState.value.selectedDetail
        viewModelScope.launch {
            val download = manager.estimateForRadius(lat, lon, radius, detail)
            val import = withContext(Dispatchers.IO) {
                manager.estimateImport(lat, lon, radius, cacheSource, detail)
            }
            _uiState.value = _uiState.value.copy(estimate = download, importEstimate = import)
        }
    }

    fun clearRadius() {
        _uiState.value = _uiState.value.copy(selectedRadius = null, estimate = null, importEstimate = null)
    }

    /**
     * Adopts already-cached coverage. No network, no endpoint, no Wi-Fi requirement —
     * the bytes are already on the device.
     */
    fun importFromCache(lat: Double, lon: Double, explicitName: String? = null) {
        val radius = _uiState.value.selectedRadius ?: return
        downloadJob = viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                manager.importFromCache(
                    lat = lat, lon = lon, radius = radius, source = cacheSource,
                    detail = _uiState.value.selectedDetail, explicitName = explicitName,
                    onProgress = { p -> _uiState.value = _uiState.value.copy(activeProgress = p) },
                )
            }
            finish(outcome)
        }
    }

    fun startDownload(lat: Double, lon: Double, explicitName: String? = null) {
        val radius = _uiState.value.selectedRadius ?: run {
            _uiState.value = _uiState.value.copy(message = "Choose a radius first.")
            return
        }
        downloadJob = viewModelScope.launch {
            val outcome = manager.downloadNew(
                lat = lat, lon = lon, radius = radius, detail = _uiState.value.selectedDetail,
                explicitName = explicitName,
                onProgress = { p -> _uiState.value = _uiState.value.copy(activeProgress = p) },
            )
            finish(outcome)
        }
    }

    fun resume(segmentId: String, coverageId: String) {
        downloadJob = viewModelScope.launch {
            finish(
                manager.resume(segmentId, coverageId) { p ->
                    _uiState.value = _uiState.value.copy(activeProgress = p)
                },
            )
        }
    }

    /** Cancelling keeps everything downloaded so far — the manager marks it resumable. */
    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _uiState.value = _uiState.value.copy(activeProgress = null, message = "Download stopped. Progress kept.")
        refresh()
    }

    // ── Append ────────────────────────────────────────────────────────────────

    fun appendTarget(recordId: String): AppendTarget? = manager.chooseAppendTarget(recordId, _uiState.value.selectedDetail)

    fun appendCoverage(recordId: String, segmentId: String) {
        downloadJob = viewModelScope.launch {
            finish(
                manager.appendTravelCoverage(recordId, segmentId, _uiState.value.selectedDetail) { p ->
                    _uiState.value = _uiState.value.copy(activeProgress = p)
                },
            )
        }
    }

    fun deferSuggestion(recordId: String) { manager.deferTravelSuggestion(recordId); refresh() }
    fun dismissSuggestion(recordId: String) { manager.dismissTravelSuggestion(recordId); refresh() }

    // ── Deletion ──────────────────────────────────────────────────────────────

    fun toggleForDeletion(segmentId: String) {
        val current = _uiState.value.selectedForDeletion
        _uiState.value = _uiState.value.copy(
            selectedForDeletion = if (segmentId in current) current - segmentId else current + segmentId,
            deletionPreview = null,
        )
    }

    fun clearDeletionSelection() {
        _uiState.value = _uiState.value.copy(selectedForDeletion = emptySet(), deletionPreview = null)
    }

    /** Builds the confirmation detail. Nothing is removed until [confirmDeletion]. */
    fun prepareDeletion() {
        val ids = _uiState.value.selectedForDeletion
        if (ids.isEmpty()) return
        _uiState.value = _uiState.value.copy(deletionPreview = manager.deletionPreview(ids))
    }

    fun confirmDeletion() {
        val ids = _uiState.value.selectedForDeletion
        if (ids.isEmpty()) return
        val result = manager.deleteSegments(ids)
        _uiState.value = _uiState.value.copy(
            selectedForDeletion = emptySet(),
            deletionPreview = null,
            message = "Removed ${result.segmentsRemoved} map(s), freeing ${TileGeometry.formatBytes(result.bytesFreed)}.",
        )
        refresh()
    }

    fun clearMessage() { _uiState.value = _uiState.value.copy(message = null) }

    private fun finish(outcome: DownloadOutcome) {
        val text = when (outcome) {
            is DownloadOutcome.Completed ->
                "Download complete — ${outcome.tilesStored} areas, ${TileGeometry.formatBytes(outcome.bytes)}."
            is DownloadOutcome.Paused ->
                "${outcome.reason} ${outcome.stored} of ${outcome.total} saved — resume when back on Wi-Fi."
            is DownloadOutcome.Rejected -> outcome.reason
            is DownloadOutcome.Failed -> outcome.reason
            is DownloadOutcome.NothingToDo -> "Already downloaded — nothing new to fetch."
        }
        _uiState.value = _uiState.value.copy(activeProgress = null, message = text)
        refresh()
    }
}
