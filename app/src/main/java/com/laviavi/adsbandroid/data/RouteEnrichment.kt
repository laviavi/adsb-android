package com.laviavi.adsbandroid.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
private data class AdsbdbCallsignResponse(val response: AdsbdbResponseBody? = null)
@Serializable
private data class AdsbdbResponseBody(val flightroute: AdsbdbFlightRoute? = null)
@Serializable
private data class AdsbdbFlightRoute(val origin: AdsbdbAirport? = null, val destination: AdsbdbAirport? = null)
@Serializable
private data class AdsbdbAirport(@SerialName("icao_code") val icaoCode: String? = null)

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
        install(ContentNegotiation) { json() }
    }

    /** [icao] is only for the audit log below — the actual lookup is keyed by [callsign], adsbdb has no hex-based route endpoint. */
    suspend fun lookupRoute(icao: String, callsign: String): String? {
        val key = callsign.trim().uppercase()
        if (key.isEmpty()) return null
        val now = System.currentTimeMillis()

        val cached = cacheDao.get(key)
        if (cached != null && now - cached.cachedAtMs < CACHE_TTL_MS) {
            logAttempt(
                icao = icao, servedFromCache = true, requestKey = key, requestUrl = null,
                success = cached.route != null, result = cached.route ?: "no data (cached)", durationMs = 0,
            )
            return cached.route
        }

        val url = "https://api.adsbdb.com/v0/callsign/$key"
        val startedAt = System.currentTimeMillis()
        val outcome = runCatching {
            val resp: AdsbdbCallsignResponse = client.get(url).body()
            val fr = resp.response?.flightroute ?: return@runCatching null
            val origin = fr.origin?.icaoCode.present()
            val dest = fr.destination?.icaoCode.present()
            if (origin != null && dest != null) "$origin-$dest" else null
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
