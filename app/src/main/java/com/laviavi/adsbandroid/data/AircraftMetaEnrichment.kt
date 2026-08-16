package com.laviavi.adsbandroid.data

import android.util.Log
import com.laviavi.adsbandroid.enrich.IcaoTypeNames
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

private const val TAG = "AircraftMetaEnrich"

/**
 * Ktor's typed `.body<T>()` deserialization (via ContentNegotiation) is broken for this
 * app on Android — confirmed via a real exported enrichment log: hexdb.io and adsbdb both
 * threw "Serializer for class X is not found" on every real fetch despite both returning
 * correctly-shaped JSON (verified live with curl), and OpenSky's own failure explicitly said
 * "(Kotlin reflection is not available)". [RouteEnrichment] and [FlightAwareEnrichment] never
 * hit this because they already fetch raw text and parse manually — this bypasses Ktor's
 * converter the same way, decoding straight from the response body via kotlinx-serialization.
 */
private val metaJson = Json { ignoreUnknownKeys = true }

/**
 * Was 30 days. A negative result (all three sources returned nothing — a
 * transient outage, not necessarily "this aircraft has no data") used to stay
 * cached for a month with no way to force a recheck. Real case: ICAO C05737
 * (a Canadian Harbour Air DHC-3 Turbo Otter, registration C-GHAS) got cached
 * as "none" while hexdb.io was erroring and OpenSky's metadata endpoint was
 * down — even though adsbdb has full data for it. 2 hours lets a transient
 * failure retry the same session instead of waiting a month.
 */
private const val CACHE_TTL_MS = 2 * 3600 * 1000L

/** True when a cached lookup is still fresh enough to skip a new network fetch. */
internal fun isCacheFresh(cachedAtMs: Long, nowMs: Long, ttlMs: Long = CACHE_TTL_MS): Boolean =
    nowMs - cachedAtMs < ttlMs

data class AircraftMeta(
    val icao: String,
    val registration: String?,
    val manufacturer: String?,
    val model: String?,
    val typeCode: String?,
    val owner: String?,
    val source: String,
)

fun AircraftMeta.isEmpty() =
    registration == null && manufacturer == null && model == null && typeCode == null && owner == null

fun AircraftMeta.typeDisplay(): String? {
    val fromMap = typeCode?.let { IcaoTypeNames.TYPE_MAP[it.uppercase()] }
    return when {
        manufacturer != null && model != null -> "$manufacturer $model"
        model != null -> model
        fromMap != null -> fromMap
        typeCode != null -> typeCode
        else -> null
    }
}

// ── hexdb.io response ─────────────────────────────────────────────────────────

@Serializable
internal data class HexdbResponse(
    @SerialName("Registration")    val registration:   String? = null,
    @SerialName("Manufacturer")    val manufacturer:   String? = null,
    @SerialName("Type")            val type:           String? = null,
    @SerialName("RegisteredOwners") val registeredOwners: String? = null,
    @SerialName("ICAOTypeCode")    val icaoTypeCode:   String? = null,
)

/** Pure so the hexdb.io field mapping is directly testable without a network call. */
internal fun mapHexdbResponse(icao: String, resp: HexdbResponse): AircraftMeta? {
    val reg = resp.registration.present()
    val mfr = resp.manufacturer.present()
    val mdl = resp.type.present()
    val own = resp.registeredOwners.present()
    val tc  = resp.icaoTypeCode.present()?.uppercase()
    if (reg == null && mfr == null && mdl == null) return null
    return AircraftMeta(icao, reg, mfr, mdl, tc, own, "hexdb")
}

// ── adsbdb aircraft response ──────────────────────────────────────────────────

@Serializable
internal data class AdsbdbAircraftFields(
    val registration: String?  = null,
    /** Free-text model, e.g. "172M" — NOT an ICAO type designator despite the field name. */
    val type: String?          = null,
    val manufacturer: String?  = null,
    /** The actual ICAO type designator, e.g. "C172". `registerType` (the old field
     *  name here) doesn't exist anywhere in adsbdb's real response — it always
     *  deserialized to null, which meant `model` below was always null too, and
     *  `typeDisplay()` could never show "$manufacturer $model" for an adsbdb-sourced
     *  aircraft, only a bare, non-ICAO-mapped fallback string like "172M". */
    @SerialName("icao_type") val icaoType: String? = null,
)

/** Pure so the field-mapping (icaoType -> typeCode, type -> model) is directly testable. */
internal fun mapAdsbdbFields(icao: String, ac: AdsbdbAircraftFields): AircraftMeta? {
    val reg = ac.registration.present()
    val tc  = ac.icaoType.present()?.uppercase()
    val mfr = ac.manufacturer.present()
    val mdl = ac.type.present()
    if (reg == null && mfr == null && mdl == null && tc == null) return null
    return AircraftMeta(icao, reg, mfr, mdl, tc, null, "adsbdb")
}

/**
 * adsbdb's "response" field is an OBJECT (`{"aircraft": {...}}`) for a recognized ICAO but a
 * plain STRING ("unknown aircraft") for one it isn't — the same shape mismatch already found
 * and fixed for the callsign/route endpoint. Parsed as raw JsonElement so a string response is
 * just "no data" instead of throwing.
 */
internal fun parseAdsbdbAircraft(icao: String, json: String): AircraftMeta? {
    val root = runCatching { metaJson.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
    val aircraft = (root["response"] as? JsonObject)?.get("aircraft") as? JsonObject ?: return null
    return mapAdsbdbFields(icao, metaJson.decodeFromJsonElement<AdsbdbAircraftFields>(aircraft))
}

/**
 * Combines results from every source queried for one ICAO: first non-null
 * value per field wins, in the sources' priority order (hexdb > adsbdb) — a
 * partial result from one source no longer hides a field only a later
 * source had. Pure so it's directly testable without a network call.
 */
internal fun mergeSources(icao: String, vararg results: AircraftMeta?): AircraftMeta? {
    val present = results.filterNotNull()
    if (present.isEmpty()) return null
    return AircraftMeta(
        icao = icao,
        registration = present.firstNotNullOfOrNull { it.registration },
        manufacturer = present.firstNotNullOfOrNull { it.manufacturer },
        model = present.firstNotNullOfOrNull { it.model },
        typeCode = present.firstNotNullOfOrNull { it.typeCode },
        owner = present.firstNotNullOfOrNull { it.owner },
        source = present.map { it.source }.distinct().joinToString("+"),
    ).takeUnless { it.isEmpty() }
}

/**
 * Per-ICAO metadata from public APIs: hexdb.io + adsbdb, merged field-by-field.
 * OpenSky's metadata endpoint was dropped — confirmed permanently gone (410
 * Gone) every time it's been checked this session, and every attempt only
 * added log noise for zero signal.
 * Mirrors Python aircraft_meta.py lookup().
 */
class AircraftMetaEnrichment(
    private val cacheDao: AircraftMetaCacheDao,
    private val eventLogDao: AircraftEventLogDao,
) {
    private val client = HttpClient(CIO) {
        engine { requestTimeout = 8_000 }
    }

    /** icao -> cachedAtMs already logged, so a live aircraft's repeated cache hits (once per
     *  position update, several times a second) log once per distinct cached value instead of
     *  flooding the event log — confirmed via a real export: 99% of logged rows were re-logged
     *  cache checks, not real attempts. */
    private val loggedCacheHitAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    suspend fun lookup(icao: String): AircraftMeta? {
        val key = icao.uppercase().trim()
        if (key.isEmpty()) return null

        val cached = cacheDao.get(key)
        if (cached != null && isCacheFresh(cached.cachedAtMs, System.currentTimeMillis())) {
            val result = if (cached.source == "none") null
            // Also guarded on read: rows cached before this fix still hold "Null".
            else AircraftMeta(key, cached.registration.present(), cached.manufacturer.present(),
                cached.model.present(), cached.typeCode.present(), cached.owner.present(), cached.source)
            if (loggedCacheHitAt.put(key, cached.cachedAtMs) != cached.cachedAtMs) {
                logCacheHit(key, result)
            }
            return result
        }

        val meta = if (key.startsWith("A")) lookupUs(key) else lookupIntl(key)
        val now = System.currentTimeMillis()
        cacheDao.insert(
            AircraftMetaCacheEntity(
                icao         = key,
                registration = meta?.registration,
                manufacturer = meta?.manufacturer,
                model        = meta?.model,
                typeCode     = meta?.typeCode,
                owner        = meta?.owner,
                source       = meta?.source ?: "none",
                cachedAtMs   = now,
            )
        )
        return meta
    }

    /** Releases the underlying HTTP engine's connection pool/threads. */
    fun close() = client.close()

    // US aircraft only have hexdb.io to check now that OpenSky's gone.
    private suspend fun lookupUs(icao: String): AircraftMeta? = fetchHexdb(icao)

    // Non-US: hexdb.io + adsbdb, queried in parallel and merged field-by-field —
    // one source having a registration doesn't mean it also has the owner.
    private suspend fun lookupIntl(icao: String): AircraftMeta? = coroutineScope {
        val hexdb = async { fetchHexdb(icao) }
        val adsbdb = async { fetchAdsbdb(icao) }
        mergeSources(icao, hexdb.await(), adsbdb.await())
    }

    private suspend fun fetchHexdb(icao: String): AircraftMeta? {
        val url = "https://hexdb.io/api/v1/aircraft/${icao.lowercase()}"
        val startedAt = System.currentTimeMillis()
        val outcome = runCatching {
            val text = client.get(url) {
                headers { append(HttpHeaders.UserAgent, "adsb-receiver/1.0 (open source)") }
            }.bodyAsText()
            mapHexdbResponse(icao, metaJson.decodeFromString<HexdbResponse>(text))
        }
        outcome.exceptionOrNull()?.let { Log.d(TAG, "hexdb.io error for $icao: $it") }
        logAttempt(icao, "hexdb", false, url, outcome, System.currentTimeMillis() - startedAt)
        return outcome.getOrNull()
    }

    private suspend fun fetchAdsbdb(icao: String): AircraftMeta? {
        val url = "https://api.adsbdb.com/v0/aircraft/${icao.lowercase()}"
        val startedAt = System.currentTimeMillis()
        val outcome = runCatching {
            val text = client.get(url) {
                headers { append(HttpHeaders.UserAgent, "adsb-receiver/1.0 (open source)") }
            }.bodyAsText()
            parseAdsbdbAircraft(icao, text)
        }
        outcome.exceptionOrNull()?.let { Log.d(TAG, "adsbdb error for $icao: $it") }
        logAttempt(icao, "adsbdb-aircraft", false, url, outcome, System.currentTimeMillis() - startedAt)
        return outcome.getOrNull()
    }

    /** [outcome]'s exception (if any) is what previously vanished into logcat only — now
     *  captured in the persisted resultSummary so a real failure reads as "error: ..." instead
     *  of an indistinguishable "no data". Confirmed live: hexdb.io/adsbdb both had complete
     *  data for an ICAO (C04205) that this audit log recorded as two separate genuine "no
     *  data" fetches 4+ hours apart — a real fetch failure, not missing data or a stale cache,
     *  that the old logging had no way to explain. */
    private suspend fun logAttempt(
        icao: String, source: String?, servedFromCache: Boolean, requestUrl: String?,
        outcome: Result<AircraftMeta?>, durationMs: Long,
    ) {
        val meta = outcome.getOrNull()
        val summary = outcome.fold(
            onSuccess = { it.summarize() },
            onFailure = { "error: ${it.javaClass.simpleName}: ${it.message}" },
        )
        runCatching {
            eventLogDao.insert(
                AircraftEventLogEntity(
                    icao = icao, timestampMs = System.currentTimeMillis(), eventType = "ENRICHMENT_ATTEMPT",
                    source = source, requestKey = icao, requestUrl = requestUrl, servedFromCache = servedFromCache,
                    success = meta != null, resultSummary = summary, durationMs = durationMs,
                )
            )
        }
    }

    /** Cache-hit path never fetched anything, so there's no [Result] to report — always a plain summary. */
    private suspend fun logCacheHit(icao: String, meta: AircraftMeta?) {
        runCatching {
            eventLogDao.insert(
                AircraftEventLogEntity(
                    icao = icao, timestampMs = System.currentTimeMillis(), eventType = "ENRICHMENT_ATTEMPT",
                    source = null, requestKey = icao, requestUrl = null, servedFromCache = true,
                    success = meta != null, resultSummary = meta.summarize(), durationMs = 0,
                )
            )
        }
    }
}

private fun AircraftMeta?.summarize(): String {
    if (this == null) return "no data"
    return listOfNotNull(
        registration?.let { "reg=$it" },
        typeCode?.let { "type=$it" },
        owner?.let { "owner=$it" },
    ).ifEmpty { listOf("no data") }.joinToString(" ")
}
