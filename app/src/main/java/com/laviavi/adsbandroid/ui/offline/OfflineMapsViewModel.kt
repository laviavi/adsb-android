package com.laviavi.adsbandroid.ui.offline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laviavi.adsbandroid.offline.EligibilityResult
import com.laviavi.adsbandroid.offline.LocationNamer
import com.laviavi.adsbandroid.offline.MapLibreOfflineRepository
import com.laviavi.adsbandroid.offline.NetworkEligibility
import com.laviavi.adsbandroid.offline.NetworkState
import com.laviavi.adsbandroid.offline.OfflineDownloadEvent
import com.laviavi.adsbandroid.offline.OfflineDownloadPolicy
import com.laviavi.adsbandroid.offline.SavedRegion
import com.laviavi.adsbandroid.pipeline.AppConfigStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLngBounds
import kotlin.math.cos
import kotlin.math.max
import javax.inject.Inject

data class OfflineMapsUiState(
    val regions: List<SavedRegion> = emptyList(),
    val networkState: NetworkState = NetworkState.UNKNOWN,
    val downloadProgress: OfflineDownloadEvent.Progress? = null,
    val message: String? = null,
) {
    val wifiReady: Boolean get() = OfflineDownloadPolicy.isDownloadAllowed(networkState)
    val isDownloading: Boolean get() = downloadProgress != null
}

/**
 * Offline maps, v2.0: a fixed-radius area download around a point via MapLibre's
 * native `OfflineManager`, not the old raster-tile segment/manifest system (see
 * `MapLibreOfflineRepository`'s doc comment for why that couldn't carry over —
 * MapLibre's offline regions are opaque, not individually addressable files, so
 * there's no equivalent to the old radius/detail byte estimate, cache import, or
 * append-to-existing-region merge).
 */
@HiltViewModel
class OfflineMapsViewModel @Inject constructor(
    private val repository: MapLibreOfflineRepository,
    private val eligibility: NetworkEligibility,
    private val namer: LocationNamer,
    private val configStore: AppConfigStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OfflineMapsUiState())
    val uiState: StateFlow<OfflineMapsUiState> = _uiState.asStateFlow()

    private var downloadJob: Job? = null

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            val regions = repository.list()
            _uiState.value = _uiState.value.copy(regions = regions, networkState = eligibility.currentState())
        }
    }

    /** Downloads a fixed-radius area around [lat]/[lon] using the currently selected base map style. */
    fun download(lat: Double, lon: Double) {
        val result = eligibility.check()
        if (result is EligibilityResult.Ineligible) {
            _uiState.value = _uiState.value.copy(message = result.reason)
            return
        }
        downloadJob = viewModelScope.launch {
            val styleUrl = runCatching { configStore.load().mapBaseMap.styleUrl }.getOrDefault(DEFAULT_STYLE_URL)
            val name = namer.nameFor(lat, lon) ?: "Offline area"
            val bounds = boundsAround(lat, lon, RADIUS_NM)
            repository.download(styleUrl, bounds, MIN_ZOOM, MAX_ZOOM, 1f, name).collect { event ->
                when (event) {
                    is OfflineDownloadEvent.Progress ->
                        _uiState.value = _uiState.value.copy(downloadProgress = event)
                    is OfflineDownloadEvent.Completed -> {
                        _uiState.value = _uiState.value.copy(
                            downloadProgress = null,
                            message = "Download complete — ${formatBytes(event.bytes)}.",
                        )
                        refresh()
                    }
                    is OfflineDownloadEvent.Failed -> {
                        _uiState.value = _uiState.value.copy(downloadProgress = null, message = event.reason)
                    }
                }
            }
        }
    }

    /** Progress already downloaded is kept — MapLibre only discards a region on explicit delete. */
    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _uiState.value = _uiState.value.copy(downloadProgress = null, message = "Download stopped. Progress kept.")
        refresh()
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            repository.deleteById(id)
            refresh()
        }
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    companion object {
        const val RADIUS_NM = 50.0
        const val MIN_ZOOM = 4.0
        const val MAX_ZOOM = 12.0
        private const val DEFAULT_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

        /** A generous rectangular bound around a point — MapLibre's region download doesn't need geodesic precision. */
        fun boundsAround(lat: Double, lon: Double, radiusNm: Double): LatLngBounds {
            val latDelta = radiusNm / 60.0
            val lonDelta = radiusNm / (60.0 * max(cos(Math.toRadians(lat)), 0.1))
            return LatLngBounds.from(lat + latDelta, lon + lonDelta, lat - latDelta, lon - lonDelta)
        }

        fun formatBytes(bytes: Long): String = when {
            bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
            bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
            bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
            else -> "$bytes B"
        }
    }
}
