package com.laviavi.adsbandroid.offline

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.readBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay

/**
 * Fetches raster tiles over HTTP from a configurable source.
 *
 * **The tile source is deliberately injected and has no default.**
 * `tile.openstreetmap.org` explicitly prohibits bulk downloading and offline use —
 * their policy names "pre-seeding large areas or multiple zoom levels" and
 * "Download region for offline use" as blockable, and this feature is exactly that
 * shape. Pointing this class at OSM's community servers would get the device
 * blocked and is against their terms. Supply a self-hosted endpoint, a commercial
 * provider whose licence permits offline packaging, or a pre-built offline archive.
 *
 * See `docs/OFFLINE_MAPS.md` for the licensing note in full.
 *
 * @param urlTemplate must contain `{z}`, `{x}` and `{y}` placeholders.
 * @param userAgent must identify this application; generic or library-default agents
 *   are rejected by most tile providers.
 */
class OsmTileDownloader(
    private val client: HttpClient,
    private val urlTemplate: String,
    private val userAgent: String,
    private val maxRetries: Int = 2,
) : TileDownloader {

    override suspend fun fetch(tile: TileRef): ByteArray? {
        val url = urlTemplate
            .replace("{z}", tile.z.toString())
            .replace("{x}", tile.x.toString())
            .replace("{y}", tile.y.toString())

        repeat(maxRetries + 1) { attempt ->
            val result = runCatching {
                val response = client.get(url) {
                    headers { append(HttpHeaders.UserAgent, userAgent) }
                }
                if (response.status.isSuccess()) response.readBytes() else null
            }.getOrNull()

            // A non-null empty body is a provider error, not a valid tile; treating it
            // as success would store a zero-byte file that never re-downloads.
            if (result != null && result.isNotEmpty()) return result

            if (attempt < maxRetries) delay(RETRY_DELAY_MS * (attempt + 1))
        }
        // Null leaves the tile in the pending set, so the run stays resumable rather
        // than failing the whole segment for one bad tile.
        return null
    }

    private companion object { const val RETRY_DELAY_MS = 400L }
}

/**
 * Serves tiles from local storage only, for rendering while offline.
 *
 * Wraps a [TileStore] as a [TileDownloader] so the same manager code path can be
 * pointed at local content in tests, previews, or a build with no configured
 * provider — in which case a download simply stores nothing rather than reaching
 * for a network that must not be used.
 */
class LocalOnlyTileDownloader(private val store: TileStore) : TileDownloader {
    override suspend fun fetch(tile: TileRef): ByteArray? = store.read(tile.key)
}

/**
 * Routes to an HTTP source when the user has configured an endpoint, and to
 * [fallback] when they have not.
 *
 * The template is resolved per fetch rather than at construction so a change in
 * Settings applies immediately, and so the app can ship with downloads inert without
 * a second build. The user agent identifies this app by name, which every tile
 * provider requires and library defaults fail.
 */
class ConfigurableTileDownloader(
    private val fallback: TileDownloader,
    private val templateProvider: () -> String,
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val clientFactory: () -> HttpClient = { HttpClient() },
) : TileDownloader {

    private val client by lazy(clientFactory)

    override suspend fun fetch(tile: TileRef): ByteArray? {
        val template = templateProvider()
        if (template.isBlank()) return fallback.fetch(tile)
        return OsmTileDownloader(client, template, userAgent).fetch(tile)
    }

    companion object {
        const val DEFAULT_USER_AGENT = "adsb-android/1.0 (+https://github.com/laviavi/adsb-android)"

        /** A template is only usable if it says where each tile goes. */
        fun isValidTemplate(template: String): Boolean =
            template.isBlank() || (
                template.contains("{z}") && template.contains("{x}") && template.contains("{y}")
                )
    }
}
