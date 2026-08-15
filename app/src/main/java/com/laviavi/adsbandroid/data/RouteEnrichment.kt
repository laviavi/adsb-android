package com.laviavi.adsbandroid.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val routeJson = Json { ignoreUnknownKeys = true }

/**
 * adsbdb's "response" field is an OBJECT (`{"flightroute": {...}}`) for a callsign it
 * recognizes, but a plain STRING (e.g. "unknown callsign", "invalid callsign: X") for one
 * it doesn't — confirmed live (curl) against real unrecognized callsigns. Decoding that
 * field with a fixed object-shaped @Serializable class threw "Serializer for class X is
 * not found" on every miss, which is most callsigns — every real route fetch was failing
 * silently and getting cached as a negative result. Parsed as raw JsonElement instead, so
 * a string "response" is just treated as "no route" rather than a decode error.
 */
internal fun parseAdsbdbRoute(json: String): String? {
    val root = runCatching { routeJson.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
    val fr = (root["response"] as? JsonObject)?.get("flightroute") as? JsonObject ?: return null
    val origin = (fr["origin"] as? JsonObject)?.get("icao_code")?.jsonPrimitive?.content.present()
    val dest = (fr["destination"] as? JsonObject)?.get("icao_code")?.jsonPrimitive?.content.present()
    return if (origin != null && dest != null) "$origin-$dest" else null
}

/**
 * Route lookup via adsbdb.com's free public API (no key required) — a hobbyist-friendly
 * source instead of scraping FlightAware's website. Results (including negative ones)
 * cached in [EnrichmentCacheDao]; one source only, add more via the same DAO if ever needed.
 */
class RouteEnrichment(
    private val cacheDao: EnrichmentCacheDao,
    private val eventLogDao: AircraftEventLogDao,
) {

    private val client = HttpClient(CIO) {
        engine { requestTimeout = 8_000 }
    }

    /** icao+source -> cachedAtMs already logged, so a live aircraft's repeated cache hits (once
     *  per position update, several times a second) log once per distinct cached value instead
     *  of flooding the event log — confirmed via a real export: 99% of logged rows were
     *  re-logged cache checks, not real attempts. */
    private val loggedCacheHitAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** [icao] is only for the audit log below — the actual lookup is keyed by [callsign], adsbdb has no hex-based route endpoint. */
    suspend fun lookupRoute(icao: String, callsign: String): String? {
        val key = callsign.trim().uppercase()
        if (key.isEmpty()) return null
        val now = System.currentTimeMillis()

        val cached = cacheDao.get(key)
        if (cached != null && now - cached.cachedAtMs < CACHE_TTL_MS) {
            if (loggedCacheHitAt.put(key, cached.cachedAtMs) != cached.cachedAtMs) {
                logAttempt(
                    icao = icao, servedFromCache = true, requestKey = key, requestUrl = null,
                    success = cached.route != null, result = cached.route ?: "no data (cached)", durationMs = 0,
                )
            }
            return cached.route
        }

        val url = "https://api.adsbdb.com/v0/callsign/$key"
        val startedAt = System.currentTimeMillis()
        val outcome = runCatching {
            val text = client.get(url) {
                headers { append(HttpHeaders.UserAgent, "adsb-receiver/1.0 (open source)") }
            }.bodyAsText()
            parseAdsbdbRoute(text)
        }
        val duration = System.currentTimeMillis() - startedAt
        val route = outcome.getOrNull()

        logAttempt(
            icao = icao, servedFromCache = false, requestKey = key, requestUrl = url,
            success = outcome.isSuccess, durationMs = duration,
            result = outcome.fold(
                onSuccess = { it ?: "no data" },
                onFailure = { "error: ${it.message ?: it.javaClass.simpleName}" },
            ),
        )

        cacheDao.insert(EnrichmentCacheEntity(key = key, route = route, origin = null, destination = null, cachedAtMs = now))
        return route
    }

    private suspend fun logAttempt(
        icao: String, servedFromCache: Boolean, requestKey: String, requestUrl: String?,
        success: Boolean, result: String, durationMs: Long,
    ) {
        runCatching {
            eventLogDao.insert(
                AircraftEventLogEntity(
                    icao = icao, timestampMs = System.currentTimeMillis(), eventType = "ENRICHMENT_ATTEMPT",
                    source = "adsbdb-route", requestKey = requestKey, requestUrl = requestUrl,
                    servedFromCache = servedFromCache, success = success, resultSummary = result, durationMs = durationMs,
                )
            )
        }
    }

    /** Releases the underlying HTTP engine's connection pool/threads. */
    fun close() = client.close()

    companion object {
        // Was 24h. A transient adsbdb hiccup used to lock in a negative result for a
        // full day with no way to force a recheck — same class of problem
        // AircraftMetaEnrichment's TTL already got fixed for (30 days -> 2h).
        private const val CACHE_TTL_MS = 2 * 3600 * 1000L
    }
}
