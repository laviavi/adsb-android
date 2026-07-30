package com.laviavi.adsbandroid.offline

import android.content.Context
import android.location.Geocoder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale

/**
 * Platform implementations of the offline ports.
 *
 * Everything Android-specific lives here so `:core:receiver` stays pure and the
 * whole manager remains JVM-testable. Each adapter is deliberately thin — no policy
 * decisions are made in this file, only translation.
 */

/**
 * Wi-Fi eligibility from `ConnectivityManager`.
 *
 * Reads live on every call rather than caching a callback's last value: the manager
 * re-checks before each batch precisely so a network that changed mid-download is
 * noticed, and a cached answer would defeat that.
 */
class AndroidNetworkEligibility(context: Context) : NetworkEligibility {

    private val appContext = context.applicationContext
    private val cm get() = appContext.getSystemService(ConnectivityManager::class.java)

    override fun currentState(): NetworkState {
        val manager = cm ?: return NetworkState.UNKNOWN
        val network = manager.activeNetwork ?: return NetworkState.DISCONNECTED
        val caps = manager.getNetworkCapabilities(network) ?: return NetworkState.UNKNOWN

        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return NetworkState.DISCONNECTED
        }

        // NOT_METERED is the capability the user's data plan actually cares about;
        // a Wi-Fi transport that is metered (a phone hotspot) is reported as such so
        // policy can refuse it, rather than being waved through on transport alone.
        val unmetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)

        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->
                if (unmetered) NetworkState.WIFI_UNMETERED else NetworkState.WIFI_METERED
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkState.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkState.OTHER
            // A transport we cannot classify is never assumed safe.
            else -> NetworkState.UNKNOWN
        }
    }
}

/** Reverse-geocodes a coordinate to a place name for segment naming. */
class AndroidLocationNamer(context: Context) : LocationNamer {

    private val appContext = context.applicationContext

    override suspend fun nameFor(lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        runCatching {
            @Suppress("DEPRECATION")
            val results = Geocoder(appContext, Locale.getDefault()).getFromLocation(lat, lon, 1)
            results?.firstOrNull()?.let { a ->
                // Locality first, then the progressively coarser fields — a coordinate
                // in open country has no city but usually has a county or state, and a
                // coarse name still beats raw degrees for telling segments apart.
                a.locality
                    ?: a.subAdminArea
                    ?: a.adminArea
                    ?: a.countryName
            }
        }.getOrNull()
    }
}

/**
 * Tiles as files under `filesDir/offline/tiles/z/x/y.png`.
 *
 * Separate from osmdroid's own cache directory on purpose: osmdroid trims its cache
 * to 500 MB by age, which would silently eat a deliberately downloaded region. Files
 * here are owned by the segment manifest and are never removed except by an explicit
 * user deletion.
 */
class FileTileStore(root: File) : TileStore {

    private val dir = File(root, "offline/tiles").apply { mkdirs() }

    private fun fileFor(key: String): File = File(dir, "$key.png")

    override fun has(key: String): Boolean = fileFor(key).exists()

    override fun storedKeys(): Set<String> {
        if (!dir.exists()) return emptySet()
        val out = LinkedHashSet<String>()
        dir.walkTopDown().filter { it.isFile && it.extension == "png" }.forEach { f ->
            val rel = f.relativeTo(dir).path.replace(File.separatorChar, '/').removeSuffix(".png")
            if (TileRef.parse(rel) != null) out += rel
        }
        return out
    }

    override fun write(key: String, bytes: ByteArray) {
        val target = fileFor(key)
        target.parentFile?.mkdirs()
        // Write-then-rename so a kill mid-write cannot leave a truncated tile that
        // would later be counted as present and never re-fetched.
        val tmp = File(target.parentFile, "${target.name}.part")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    override fun read(key: String): ByteArray? =
        fileFor(key).takeIf { it.exists() }?.runCatching { readBytes() }?.getOrNull()

    override fun delete(keys: Collection<String>): Int = keys.count { fileFor(it).delete() }

    override fun sizeOf(keys: Collection<String>): Long = keys.sumOf { fileFor(it).length() }

    override fun totalBytes(): Long =
        if (!dir.exists()) 0L else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}

/**
 * Manifest and travel log as JSON documents.
 *
 * Written temp-then-rename, which is the whole reason this is not a plain `writeText`:
 * a manifest torn by process death is the one failure that could orphan every tile on
 * disk, and rename is atomic on the same filesystem.
 */
class FileManifestStore(
    root: File,
    /**
     * Routed through the offline port rather than `ErrorLog`, which calls
     * `android.util.Log` and would make this adapter require Robolectric to test —
     * for a class whose whole job is file I/O that works fine on a plain JVM.
     */
    private val log: OfflineLogger = OfflineLogger.None,
) : ManifestStore {

    private val dir = File(root, "offline").apply { mkdirs() }
    private val manifestFile = File(dir, "manifest.json")
    private val travelFile = File(dir, "travel.json")

    private val json = Json {
        ignoreUnknownKeys = true      // forward compatibility with a later schema
        encodeDefaults = true
        prettyPrint = false
    }

    // Explicit serializers rather than the reified helpers: those need the
    // serialization compiler plugin in *this* module, and :app has no other reason
    // to carry it — the @Serializable types all live in :core:receiver.
    override fun load(): OfflineManifest = read(manifestFile, OfflineManifest.EMPTY) {
        json.decodeFromString(OfflineManifest.serializer(), it)
    }

    override fun save(manifest: OfflineManifest) =
        writeAtomic(manifestFile, json.encodeToString(OfflineManifest.serializer(), manifest))

    override fun loadTravelLog(): TravelLog = read(travelFile, TravelLog.EMPTY) {
        json.decodeFromString(TravelLog.serializer(), it)
    }

    override fun saveTravelLog(log: TravelLog) =
        writeAtomic(travelFile, json.encodeToString(TravelLog.serializer(), log))

    private fun <T> read(file: File, fallback: T, parse: (String) -> T): T {
        // A leftover .tmp means the last write did not complete. The committed file is
        // still the previous good state, so recovery is simply to ignore the partial.
        File(file.parentFile, "${file.name}.tmp").takeIf { it.exists() }?.delete()
        if (!file.exists()) return fallback
        return runCatching { parse(file.readText()) }
            // Losing the index is bad; taking the app down with it is worse. The tiles
            // are still on disk and pruneOrphanedTiles can reclaim them.
            .onFailure { log.storageError("readManifest", it.message ?: it.javaClass.simpleName) }
            .getOrDefault(fallback)
    }

    private fun writeAtomic(file: File, text: String) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }
}

/** Wall clock. Split out so tests can pin timestamps and the date-stamped name fallback. */
class SystemOfflineClock : OfflineClock {
    override fun nowMs(): Long = System.currentTimeMillis()
    override fun todayStamp(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date())
}

class UuidGenerator : IdGenerator {
    override fun newId(): String = java.util.UUID.randomUUID().toString()
}

/** Structured logging to logcat, one key=value line per event so it greps cleanly. */
class LogcatOfflineLogger : OfflineLogger {
    private fun emit(event: String, vararg f: Pair<String, Any?>) {
        Log.i(TAG, "offline event=$event " + f.joinToString(" ") { "${it.first}=${it.second}" })
    }

    override fun eligibilityChecked(state: NetworkState, allowed: Boolean, operation: String) =
        emit("eligibility", "state" to state, "allowed" to allowed, "op" to operation)
    override fun downloadStarted(segmentId: String, coverageId: String, tileCount: Int, estimatedBytes: Long) =
        emit("download_start", "segment" to segmentId, "coverage" to coverageId, "tiles" to tileCount, "est_bytes" to estimatedBytes)
    override fun downloadProgress(segmentId: String, coverageId: String, stored: Int, total: Int, bytes: Long) =
        emit("download_progress", "segment" to segmentId, "stored" to stored, "total" to total, "bytes" to bytes)
    override fun downloadPaused(segmentId: String, coverageId: String, reason: String, stored: Int, total: Int) =
        emit("download_pause", "segment" to segmentId, "reason" to reason, "stored" to stored, "total" to total)
    override fun downloadCompleted(segmentId: String, coverageId: String, tileCount: Int, bytes: Long) =
        emit("download_complete", "segment" to segmentId, "tiles" to tileCount, "bytes" to bytes)
    override fun downloadFailed(segmentId: String, coverageId: String, reason: String) =
        emit("download_failed", "segment" to segmentId, "reason" to reason)
    override fun appendDecision(recordId: String, target: String, reason: String, candidates: List<String>) =
        emit("append_decision", "record" to recordId, "target" to target, "reason" to reason, "candidates" to candidates.size)
    override fun segmentDeleted(segmentId: String, name: String, tilesRemoved: Int, tilesRetainedShared: Int) =
        emit("segment_deleted", "segment" to segmentId, "name" to name, "removed" to tilesRemoved, "kept_shared" to tilesRetainedShared)
    override fun storageError(operation: String, message: String) {
        Log.w(TAG, "offline event=storage_error op=$operation message=$message")
    }

    private companion object { const val TAG = "OfflineMaps" }
}
