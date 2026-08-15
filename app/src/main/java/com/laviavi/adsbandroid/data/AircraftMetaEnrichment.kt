package com.laviavi.adsbandroid.data

import android.util.Log
import com.laviavi.adsbandroid.enrich.IcaoTypeNames
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val TAG = "AircraftMetaEnrich"

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

// ── OpenSky response ──────────────────────────────────────────────────────────

@Serializable
private data class OpenSkyResponse(
    val registration: String?      = null,
    val manufacturername: String?  = null,
    val model: String?             = null,
    val typecode: String?          = null,
)

// ── adsbdb aircraft response ──────────────────────────────────────────────────

@Serializable
private data class AdsbdbAircraftResponse(val response: AdsbdbAircraftBody? = null)
@Serializable
private data class AdsbdbAircraftBody(val aircraft: AdsbdbAircraftFields? = null)
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
 * Combines results from every source queried for one ICAO: first non-null
 * value per field wins, in the sources' priority order (hexdb > OpenSky >
 * adsbdb) — a partial result from one source no longer hides a field only a
 * later source had. Pure so it's directly testable without a network call.
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
 * Per-ICAO metadata from three public APIs: hexdb.io → OpenSky → adsbdb.
 * For US aircraft (ICAO prefix A), FAA local CSV is tried first.
 * Results cached in Room for 30 days.
 * Mirrors Python aircraft_meta.py lookup().
 */
class AircraftMetaEnrichment(
    private val cacheDao: AircraftMetaCacheDao,
    private val eventLogDao: AircraftEventLogDao,
) {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        engine { requestTimeout = 8_000 }
    }

    suspend fun lookup(icao: String): AircraftMeta? {
        val key = icao.uppercase().trim()
        if (key.isEmpty()) return null

        val cached = cacheDao.get(key)
        if (cached != null && isCacheFresh(cached.cachedAtMs, System.currentTimeMillis())) {
            val result = if (cached.source == "none") null
            // Also guarded on read: rows cached before this fix still hold "Null".
            else AircraftMeta(key, cached.registration.present(), cached.manufacturer.present(),
                cached.model.present(), cached.typeCode.present(), cached.owner.present(), cached.source)
            logAttempt(key, source = null, servedFromCache = true, requestUrl = null, meta = result, durationMs = 0)
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

    // US: hexdb.io + OpenSky, queried in parallel and merged field-by-field —
    // one source having a registration doesn't mean it also has the owner.
    private suspend fun lookupUs(icao: String): AircraftMeta? = coroutineScope {
        val hexdb = async { fetchHexdb(icao) }
        val opensky = async { fetchOpenSky(icao) }
        mergeSources(icao, hexdb.await(), opensky.await())
    }

    // Non-US: hexdb.io + OpenSky + adsbdb, same parallel-merge approach.
    private suspend fun lookupIntl(icao: String): AircraftMeta? = coroutineScope {
        val hexdb = async { fetchHexdb(icao) }
        val opensky = async { fetchOpenSky(icao) }
        val adsbdb = async { fetchAdsbdb(icao) }
        mergeSources(icao, hexdb.await(), opensky.await(), adsbdb.await())
    }

    private suspend fun fetchHexdb(icao: String): AircraftMeta? {
        val url = "https://hexdb.io/api/v1/aircraft/${icao.lowercase()}"
        val startedAt = System.currentTimeMillis()
        val meta = runCatching {
            val resp: HexdbResponse = client.get(url) {
                headers { append(HttpHeaders.UserAgent, "adsb-receiver/1.0 (open source)") }
            }.body()
            mapHexdbResponse(icao, resp)
        }.getOrElse {
            Log.d(TAG, "hexdb.io error for $icao: $it")
            null
        }
        logAttempt(icao, "hexdb", false, url, meta, System.currentTimeMillis() - startedAt)
        return meta
    }

    private suspend fun fetchOpenSky(icao: String): AircraftMeta? {
        val url = "https://opensky-network.org/api/metadata/aircraft/icao/${icao.lowercase()}"
        val startedAt = System.currentTimeMillis()
        val meta = runCatching {
            val resp: OpenSkyResponse = client.get(url) {
                headers { append(HttpHeaders.UserAgent, "adsb-receiver/1.0 (open source)") }
            }.body()
            val reg  = resp.registration.present()
            val mfr  = resp.manufacturername.present()
            val mdl  = resp.model.present()
            val tc   = resp.typecode.present()?.uppercase()
            if (reg == null && mfr == null && mdl == null) return@runCatching null
            AircraftMeta(icao, reg, mfr, mdl, tc, null, "opensky")
        }.getOrElse {
            Log.d(TAG, "OpenSky error for $icao: $it")
            null
        }
        logAttempt(icao, "opensky", false, url, meta, System.currentTimeMillis() - startedAt)
        return meta
    }

    private suspend fun fetchAdsbdb(icao: String): AircraftMeta? {
        val url = "https://api.adsbdb.com/v0/aircraft/${icao.lowercase()}"
        val startedAt = System.currentTimeMillis()
        val meta = runCatching {
            val resp: AdsbdbAircraftResponse = client.get(url) {
                headers { append(HttpHeaders.UserAgent, "adsb-receiver/1.0 (open source)") }
            }.body()
            resp.response?.aircraft?.let { mapAdsbdbFields(icao, it) }
        }.getOrElse {
            Log.d(TAG, "adsbdb error for $icao: $it")
            null
        }
        logAttempt(icao, "adsbdb-aircraft", false, url, meta, System.currentTimeMillis() - startedAt)
        return meta
    }

    private suspend fun logAttempt(
        icao: String, source: String?, servedFromCache: Boolean, requestUrl: String?, meta: AircraftMeta?, durationMs: Long,
    ) {
        runCatching {
            eventLogDao.insert(
                AircraftEventLogEntity(
                    icao = icao, timestampMs = System.currentTimeMillis(), eventType = "ENRICHMENT_ATTEMPT",
                    source = source, requestKey = icao, requestUrl = requestUrl, servedFromCache = servedFromCache,
                    success = meta != null, resultSummary = meta.summarize(), durationMs = durationMs,
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
