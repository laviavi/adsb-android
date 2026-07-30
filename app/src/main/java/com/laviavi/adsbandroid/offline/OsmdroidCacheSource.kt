package com.laviavi.adsbandroid.offline

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import org.osmdroid.util.MapTileIndex
import java.io.File

/**
 * Reads tiles the map library has already cached, so viewed coverage can be adopted
 * into a managed segment.
 *
 * This exists because osmdroid's cache is not durable storage: it trims itself to
 * 500 MB **by age**, so an area someone deliberately studied disappears on a schedule
 * nobody chose. Importing copies those tiles into `FileTileStore`, where nothing is
 * removed except by an explicit deletion.
 *
 * Reading is direct SQLite rather than through osmdroid's provider stack, which is
 * built to serve a live map view and has no "enumerate everything cached" entry
 * point. The key encoding comes from [MapTileIndex] rather than being reimplemented:
 * the packing is `(zoom << 58) + (x << 29) + y`, and a hardcoded copy would break
 * silently against a library upgrade — as a wrong tile key reads as "not cached"
 * rather than as an error.
 */
class OsmdroidCacheSource(private val cacheDir: File) : LocalTileSource {

    private val dbFile: File get() = File(cacheDir, DB_NAME)

    private fun openReadOnly(): SQLiteDatabase? {
        if (!dbFile.exists()) return null
        return runCatching {
            SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        }.onFailure {
            // A cache locked by the running map, or mid-write, is a normal condition —
            // the import simply finds nothing this time rather than failing loudly.
            Log.w(TAG, "offline event=cache_open_failed message=${it.message}")
        }.getOrNull()
    }

    override fun availableTiles(): Set<TileRef> {
        val db = openReadOnly() ?: return emptySet()
        val out = LinkedHashSet<TileRef>()
        db.use { database ->
            runCatching {
                database.rawQuery("SELECT key FROM $TABLE", null).use { c ->
                    while (c.moveToNext()) {
                        val key = c.getLong(0)
                        out += TileRef(
                            z = MapTileIndex.getZoom(key),
                            x = MapTileIndex.getX(key),
                            y = MapTileIndex.getY(key),
                        )
                    }
                }
            }.onFailure { Log.w(TAG, "offline event=cache_scan_failed message=${it.message}") }
        }
        return out
    }

    override suspend fun fetch(tile: TileRef): ByteArray? {
        val db = openReadOnly() ?: return null
        return db.use { database ->
            runCatching {
                val key = MapTileIndex.getTileIndex(tile.z, tile.x, tile.y)
                database.rawQuery(
                    "SELECT tile FROM $TABLE WHERE key = ?",
                    arrayOf(key.toString()),
                ).use { c ->
                    // A row with a null or empty blob is a cached miss, not a tile;
                    // storing it would count as coverage and never be corrected.
                    if (c.moveToFirst()) c.getBlob(0)?.takeIf { it.isNotEmpty() } else null
                }
            }.getOrNull()
        }
    }

    /** Rough size of everything cached, for the import estimate. */
    fun cachedTileCount(): Int = availableTiles().size

    private companion object {
        const val DB_NAME = "cache.db"
        const val TABLE = "tiles"
        const val TAG = "OfflineMaps"
    }
}
