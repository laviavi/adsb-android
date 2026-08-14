package com.laviavi.adsbandroid.offline

import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import kotlin.coroutines.resume

/**
 * Offline map downloads via MapLibre's native `OfflineManager` — regions are opaque,
 * native-code-managed downloads (vector tiles, sprites, glyphs, style JSON all
 * together), nothing like the old per-tile-file raster system this replaced. There is
 * no cheap pre-download byte estimate the way counting raster z/x/y tiles allowed, no
 * "import from cache" (the native ambient cache isn't exposed as individual files),
 * and no append-to-existing-region merge — those v1 features depended entirely on
 * raster tiles being individually addressable files, which vector tiles are not.
 */
data class RegionMeta(val name: String, val createdAtMs: Long) {
    /** Newline-delimited, not JSON — :app doesn't carry the kotlinx.serialization compiler plugin (only :core:receiver does), not worth adding for two fields. */
    fun encode(): ByteArray = "$name\n$createdAtMs".toByteArray()

    companion object {
        fun decode(bytes: ByteArray): RegionMeta {
            val text = String(bytes)
            val newline = text.indexOf('\n')
            return if (newline < 0) RegionMeta(text.ifBlank { "Offline area" }, 0L)
            else RegionMeta(text.substring(0, newline), text.substring(newline + 1).toLongOrNull() ?: 0L)
        }
    }
}

data class SavedRegion(
    val id: Long,
    val meta: RegionMeta,
    val definition: OfflineTilePyramidRegionDefinition,
    val status: OfflineRegionStatus?,
)

class MapLibreOfflineRepository(context: Context) {

    private val manager = OfflineManager.getInstance(context)

    suspend fun list(): List<SavedRegion> = suspendCancellableCoroutine { cont ->
        manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                cont.resume(offlineRegions.orEmpty().mapNotNull { toSavedRegion(it) })
            }
            override fun onError(error: String) = cont.resume(emptyList())
        })
    }

    private fun toSavedRegion(region: OfflineRegion): SavedRegion? {
        val def = region.definition as? OfflineTilePyramidRegionDefinition ?: return null
        val meta = runCatching { RegionMeta.decode(region.metadata) }.getOrElse { RegionMeta("Offline area", 0L) }
        return SavedRegion(region.id, meta, def, status = null)
    }

    /**
     * Starts a download and emits progress until it completes, errors, or the caller
     * cancels (which leaves whatever was already downloaded in place — MapLibre keeps
     * a region's downloaded tiles until it is explicitly deleted).
     */
    fun download(
        styleUrl: String,
        bounds: LatLngBounds,
        minZoom: Double,
        maxZoom: Double,
        pixelRatio: Float,
        name: String,
    ): Flow<OfflineDownloadEvent> = callbackFlow {
        val definition = OfflineTilePyramidRegionDefinition(styleUrl, bounds, minZoom, maxZoom, pixelRatio)
        val metadata = RegionMeta(name, System.currentTimeMillis()).encode()

        manager.createOfflineRegion(definition, metadata, object : OfflineManager.CreateOfflineRegionCallback {
            override fun onCreate(offlineRegion: OfflineRegion) {
                offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
                    override fun onStatusChanged(status: OfflineRegionStatus) {
                        trySend(OfflineDownloadEvent.Progress(status.completedResourceCount, status.requiredResourceCount, status.completedResourceSize))
                        if (status.isComplete) {
                            trySend(OfflineDownloadEvent.Completed(status.completedResourceSize))
                            close()
                        }
                    }
                    override fun onError(error: OfflineRegionError) {
                        trySend(OfflineDownloadEvent.Failed(error.message ?: error.reason))
                        close()
                    }
                    override fun mapboxTileCountLimitExceeded(limit: Long) {
                        trySend(OfflineDownloadEvent.Failed("Tile limit exceeded ($limit) — choose a smaller area."))
                        close()
                    }
                })
                offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
            }
            override fun onError(error: String) {
                trySend(OfflineDownloadEvent.Failed(error))
                close()
            }
        })
        awaitClose { }
    }

    suspend fun delete(region: OfflineRegion): Boolean = suspendCancellableCoroutine { cont ->
        region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
            override fun onDelete() = cont.resume(true)
            override fun onError(error: String) = cont.resume(false)
        })
    }

    suspend fun deleteById(id: Long): Boolean = suspendCancellableCoroutine { cont ->
        manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                val region = offlineRegions.orEmpty().firstOrNull { it.id == id }
                if (region == null) {
                    cont.resume(false)
                } else {
                    region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                        override fun onDelete() = cont.resume(true)
                        override fun onError(error: String) = cont.resume(false)
                    })
                }
            }
            override fun onError(error: String) = cont.resume(false)
        })
    }
}

sealed interface OfflineDownloadEvent {
    data class Progress(val completed: Long, val required: Long, val bytes: Long) : OfflineDownloadEvent
    data class Completed(val bytes: Long) : OfflineDownloadEvent
    data class Failed(val reason: String) : OfflineDownloadEvent
}
