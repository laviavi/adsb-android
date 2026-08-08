package com.laviavi.adsbandroid.data

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val TAG = "FlightAwareEnrich"
private const val FA_INITIAL_DELAY  = 5_000L
private const val FA_RETRY_INTERVAL = 30_000L
private const val FA_TIMEOUT_MS     = 10_000L
private const val FA_SWEEP_INTERVAL_MS = 1_000L
private const val FA_MAX_REQUESTS_PER_SEC = 2

private val CORP_SUFFIXES = listOf(
    " co.", " corp.", " corporation", " inc.", " incorporated",
    " ltd.", " limited", " llc", " l.l.c.", " plc",
)

private val json = Json { ignoreUnknownKeys = true }

data class FaResult(
    val origin:      String = "",
    val destination: String = "",
    val airlineName: String = "",
    val airlineIcao: String = "",
    val callsign:    String = "",
    val typeCode:    String = "",
    val manufacturer: String = "",
    val model:       String = "",
)

fun FaResult.hasRoute()    = origin.isNotEmpty() || destination.isNotEmpty()
fun FaResult.hasAircraft() = typeCode.isNotEmpty() || manufacturer.isNotEmpty()

/** True once [initialDelayMs] has elapsed since an ident's first sighting. */
internal fun isFirstAttemptDue(firstSeenMs: Long, nowMs: Long, initialDelayMs: Long = FA_INITIAL_DELAY): Boolean =
    nowMs - firstSeenMs >= initialDelayMs

/**
 * Caps outbound requests to at most [maxRequests] in any trailing [windowMs] —
 * shared across every ident, so a burst of new aircraft can't exceed it either.
 * Not synchronized itself; callers serialise access (see [FlightAwareEnrichment]'s
 * own lock).
 */
internal class FaRateLimiter(
    private val maxRequests: Int = FA_MAX_REQUESTS_PER_SEC,
    private val windowMs: Long = 1_000L,
) {
    private val recent = ArrayDeque<Long>()

    fun tryAcquire(nowMs: Long): Boolean {
        while (recent.isNotEmpty() && nowMs - recent.first() >= windowMs) recent.removeFirst()
        if (recent.size >= maxRequests) return false
        recent.addLast(nowMs)
        return true
    }
}

/**
 * Schedules and executes FlightAware page scrapes.
 *
 * 5s initial delay after first seen, then at most 30s between retries on a
 * null result. The initial attempt is also swept on a 1s timer independent of
 * new messages arriving — [maybeSchedule] alone only re-checks an ident's
 * timing when a *new* message for it comes in, which silently starved a
 * marginal contact (weak signal, few messages) that went quiet before a
 * message arrived after the delay window: nothing was left to trigger the
 * check. The sweep only covers first attempts, not retries — a retry still
 * needs a live message, so an aircraft that's actually departed stops
 * consuming budget the same way it always has, rather than being retried
 * forever by the sweep.
 *
 * Every outbound request (sweep or reactive) shares one [FaRateLimiter], so
 * this app never sends more than [FA_MAX_REQUESTS_PER_SEC] requests/second to
 * FlightAware regardless of how many aircraft are due at once.
 *
 * Mirrors Python lookup.py _maybe_schedule_fa + flightaware.py fetch_fa_result.
 */
class FlightAwareEnrichment(private val scope: CoroutineScope) {

    private val lock          = Any()
    private val faFirstSeen   = HashMap<String, Long>()   // ident → first-seen ms
    private val faLastAttempt = HashMap<String, Long>()   // ident → last attempt ms
    private val faInFlight    = HashSet<String>()         // idents currently fetching
    private val faCache       = HashMap<String, FaResult?>() // null = tried+failed
    private val faIdentMap    = HashMap<String, String>() // icao → current ident
    private val faOnResult    = HashMap<String, (FaResult) -> Unit>() // ident → latest result callback
    private val rateLimiter   = FaRateLimiter()

    private val client = HttpClient(CIO) {
        engine { requestTimeout = FA_TIMEOUT_MS }
    }

    init {
        scope.launch {
            while (true) {
                delay(FA_SWEEP_INTERVAL_MS)
                sweepDueFirstAttempts()
            }
        }
    }

    /**
     * Called on every aircraft update. Schedules a fetch when timing allows;
     * delivers result via [onResult] on IO dispatcher.
     * Ident priority: callsign > registration — mirrors Python _maybe_schedule_fa.
     */
    fun maybeSchedule(icaoHex: String, ident: String, onResult: (FaResult) -> Unit) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val prevIdent = faIdentMap[icaoHex]
            // Callsign upgrade: invalidate registration-keyed state
            if (prevIdent != null && prevIdent != ident) {
                faFirstSeen.remove(prevIdent)
                faLastAttempt.remove(prevIdent)
                faInFlight.remove(prevIdent)
                faCache.remove(prevIdent)
                faOnResult.remove(prevIdent)
            }
            faIdentMap[icaoHex] = ident
            faOnResult[ident] = onResult
            faFirstSeen.getOrPut(ident) { now }

            if (faCache.containsKey(ident) && faCache[ident] != null) return  // already have result
            val firstSeen = faFirstSeen.getValue(ident)
            if (!isFirstAttemptDue(firstSeen, now)) return
            val lastAttempt = faLastAttempt[ident] ?: 0L
            if (now - lastAttempt < FA_RETRY_INTERVAL && faCache.containsKey(ident)) return
            if (faInFlight.contains(ident)) return
            if (!rateLimiter.tryAcquire(now)) return
            faInFlight.add(ident)
            faLastAttempt[ident] = now
        }

        fire(ident, onResult)
    }

    /**
     * Catches idents whose initial delay elapsed with no further message to
     * re-trigger [maybeSchedule] — see class doc. Only ever fires an ident's
     * *first* attempt; retries stay message-gated.
     */
    private fun sweepDueFirstAttempts() {
        val now = System.currentTimeMillis()
        val due = synchronized(lock) {
            faFirstSeen.entries
                .filter { (ident, firstSeen) ->
                    !faCache.containsKey(ident) && !faInFlight.contains(ident) &&
                        isFirstAttemptDue(firstSeen, now)
                }
                .map { it.key }
        }
        for (ident in due) {
            val onResult = synchronized(lock) {
                if (faCache.containsKey(ident) || faInFlight.contains(ident)) return@synchronized null
                if (!rateLimiter.tryAcquire(now)) return@synchronized null
                faInFlight.add(ident)
                faLastAttempt[ident] = now
                faOnResult[ident]
            } ?: continue
            fire(ident, onResult)
        }
    }

    private fun fire(ident: String, onResult: (FaResult) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val result = fetchFaResult(ident)
            synchronized(lock) {
                faInFlight.remove(ident)
                faCache[ident] = result
            }
            result?.let { onResult(it) }
        }
    }

    /** Releases the underlying HTTP engine's connection pool/threads. The sweep loop dies with [scope]. */
    fun close() = client.close()

    private suspend fun fetchFaResult(ident: String): FaResult? = runCatching {
        val html = client.get("https://www.flightaware.com/live/flight/$ident") {
            headers {
                append(HttpHeaders.UserAgent, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36")
                append(HttpHeaders.AcceptLanguage, "en-US,en;q=0.9")
            }
        }.bodyAsText()
        parse(html)
    }.getOrElse {
        Log.d(TAG, "FA fetch error for $ident: $it")
        null
    }

}

/**
 * Picks any flight FlightAware has real tracking data for — previously required
 * `flightStatus == "airborne"` specifically, which meant a short-hop flight (e.g.
 * a 20-30 min seaplane leg) that had already landed by the time a retry landed
 * was silently discarded even though FlightAware had it (as "arrived"). Any
 * status counts now — "airborne", "arrived", "scheduled", whatever FlightAware
 * reports — as long as the entry is a genuine tracked flight, not the
 * `"unknown": true` placeholder FlightAware returns for an ident it has no data
 * for at all (which has no `flightStatus` field, so the blank check excludes it —
 * returning that as an empty-but-non-null [FaResult] would wrongly mark the ident
 * "resolved" in [FlightAwareEnrichment]'s cache and stop it from ever retrying).
 */
internal fun parse(html: String): FaResult? {
    val marker = "trackpollBootstrap = "
    val start = html.indexOf(marker).takeIf { it >= 0 } ?: return null
    val tail = html.substring(start + marker.length)
    val end = tail.indexOf("</script>").takeIf { it >= 0 } ?: return null
    val block = tail.substring(0, end).trim().trimEnd(';').trim()

    val data = runCatching { json.parseToJsonElement(block).jsonObject }.getOrNull() ?: return null
    val flights = data["flights"]?.jsonObject ?: return null
    val flight = flights.values.firstOrNull {
        (it.jsonObject["flightStatus"]?.jsonPrimitive?.content ?: "").isNotBlank()
    }?.jsonObject ?: return null

    val origin      = flight["origin"]?.jsonObject?.get("iata")?.jsonPrimitive?.content?.trim() ?: ""
    val destination = flight["destination"]?.jsonObject?.get("iata")?.jsonPrimitive?.content?.trim() ?: ""
    val callsign    = flight["displayIdent"]?.jsonPrimitive?.content?.trim() ?: ""

    val airline     = flight["airline"]?.jsonObject
    val airlineName = cleanAirlineName(airline?.get("fullName")?.jsonPrimitive?.content)
    val airlineIcao = airline?.get("icao")?.jsonPrimitive?.content?.trim()?.uppercase() ?: ""

    val aircraft    = flight["aircraft"]?.jsonObject
    val typeCode    = aircraft?.get("type")?.jsonPrimitive?.content?.trim()?.uppercase() ?: ""
    val typeDetails = aircraft?.get("typeDetails")?.jsonObject
    val manufacturer = titleCase(typeDetails?.get("manufacturer")?.jsonPrimitive?.content)
    val model        = titleCase(typeDetails?.get("model")?.jsonPrimitive?.content)

    return FaResult(origin, destination, airlineName, airlineIcao, callsign, typeCode, manufacturer, model)
}

private fun titleCase(s: String?): String {
    if (s.isNullOrBlank()) return ""
    return s.trim().split(" ").joinToString(" ") { w ->
        w.lowercase().replaceFirstChar { it.uppercaseChar() }
    }
}

private fun cleanAirlineName(name: String?): String {
    if (name.isNullOrBlank()) return ""
    var cleaned = titleCase(name)
    val low = cleaned.lowercase()
    for (suffix in CORP_SUFFIXES) {
        if (low.endsWith(suffix)) {
            cleaned = cleaned.dropLast(suffix.length).trimEnd().trimEnd(',')
            break
        }
    }
    return cleaned
}
