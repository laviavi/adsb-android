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
private const val FA_INITIAL_DELAY  = 4_000L
private const val FA_RETRY_INTERVAL = 30_000L
private const val FA_TIMEOUT_MS     = 10_000L

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

/**
 * Schedules and executes FlightAware page scrapes.
 * 4s initial delay after first seen; 30s retry on null result.
 * Mirrors Python lookup.py _maybe_schedule_fa + flightaware.py fetch_fa_result.
 */
class FlightAwareEnrichment(private val scope: CoroutineScope) {

    private val lock          = Any()
    private val faFirstSeen   = HashMap<String, Long>()   // ident → first-seen ms
    private val faLastAttempt = HashMap<String, Long>()   // ident → last attempt ms
    private val faInFlight    = HashSet<String>()         // idents currently fetching
    private val faCache       = HashMap<String, FaResult?>() // null = tried+failed
    private val faIdentMap    = HashMap<String, String>() // icao → current ident

    private val client = HttpClient(CIO) {
        engine { requestTimeout = FA_TIMEOUT_MS }
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
            }
            faIdentMap[icaoHex] = ident

            if (faCache.containsKey(ident) && faCache[ident] != null) return  // already have result
            val firstSeen = faFirstSeen.getOrPut(ident) { now }
            if (now - firstSeen < FA_INITIAL_DELAY) return
            val lastAttempt = faLastAttempt[ident] ?: 0L
            if (now - lastAttempt < FA_RETRY_INTERVAL && faCache.containsKey(ident)) return
            if (faInFlight.contains(ident)) return
            faInFlight.add(ident)
            faLastAttempt[ident] = now
        }

        scope.launch(Dispatchers.IO) {
            val result = fetchFaResult(ident)
            synchronized(lock) {
                faInFlight.remove(ident)
                faCache[ident] = result
            }
            result?.let { onResult(it) }
        }
    }

    private suspend fun fetchFaResult(ident: String): FaResult? = runCatching {
        val html = client.get("https://www.flightaware.com/live/flight/$ident") {
            headers {
                append(HttpHeaders.UserAgent, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36")
                append(HttpHeaders.AcceptLanguage, "en-US,en;q=0.9")
            }
        }.bodyAsText()
        parse(html, ident)
    }.getOrElse {
        Log.d(TAG, "FA fetch error for $ident: $it")
        null
    }

    private fun parse(html: String, ident: String): FaResult? {
        val marker = "trackpollBootstrap = "
        val start = html.indexOf(marker).takeIf { it >= 0 } ?: return null
        val tail = html.substring(start + marker.length)
        val end = tail.indexOf("</script>").takeIf { it >= 0 } ?: return null
        val block = tail.substring(0, end).trim().trimEnd(';').trim()

        val data = runCatching { json.parseToJsonElement(block).jsonObject }.getOrNull() ?: return null
        val flights = data["flights"]?.jsonObject ?: return null
        val airborne = flights.values.firstOrNull {
            (it.jsonObject["flightStatus"]?.jsonPrimitive?.content ?: "").lowercase() == "airborne"
        }?.jsonObject ?: run {
            Log.d(TAG, "FA: no airborne flight for $ident")
            return null
        }

        val origin      = airborne["origin"]?.jsonObject?.get("iata")?.jsonPrimitive?.content?.trim() ?: ""
        val destination = airborne["destination"]?.jsonObject?.get("iata")?.jsonPrimitive?.content?.trim() ?: ""
        val callsign    = airborne["displayIdent"]?.jsonPrimitive?.content?.trim() ?: ""

        val airline     = airborne["airline"]?.jsonObject
        val airlineName = cleanAirlineName(airline?.get("fullName")?.jsonPrimitive?.content)
        val airlineIcao = airline?.get("icao")?.jsonPrimitive?.content?.trim()?.uppercase() ?: ""

        val aircraft    = airborne["aircraft"]?.jsonObject
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
}
