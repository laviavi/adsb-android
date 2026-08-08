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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val TAG = "AircraftMetaEnrich"

/**
 * Trims, and treats the literal string `Null` as absent.
 *
 * hexdb.io returns `"Null"` rather than omitting the field when it has no
 * registered owner, which put an operator named "Null" on the Live list. Every
 * field parsed from an upstream response goes through this — the spelling varies
 * by source and none of them mean anything but "we don't know".
 */
private fun String?.present(): String? =
    this?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }

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
private data class HexdbResponse(
    @SerialName("Registration")    val registration:   String? = null,
    @SerialName("Manufacturer")    val manufacturer:   String? = null,
    @SerialName("Type")            val type:           String? = null,
    @SerialName("RegisteredOwners") val registeredOwners: String? = null,
    @SerialName("ICAOTypeCode")    val icaoTypeCode:   String? = null,
)

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
private data class AdsbdbAircraftFields(
    val registration: String?  = null,
    val type: String?          = null,
    val manufacturer: String?  = null,
    val registerType: String?  = null,
)

/**
 * Per-ICAO metadata from three public APIs: hexdb.io → OpenSky → adsbdb.
 * For US aircraft (ICAO prefix A), FAA local CSV is tried first.
 * Results cached in Room for 30 days.
 * Mirrors Python aircraft_meta.py lookup().
 */
class AircraftMetaEnrichment(
    private val cacheDao: AircraftMetaCacheDao,
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
            return if (cached.source == "none") null
            // Also guarded on read: rows cached before this fix still hold "Null".
            else AircraftMeta(key, cached.registration.present(), cached.manufacturer.present(),
                cached.model.present(), cached.typeCode.present(), cached.owner.present(), cached.source)
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

    // US: hexdb.io → OpenSky
    private suspend fun lookupUs(icao: String): AircraftMeta? {
        fetchHexdb(icao)?.let { return it }
        fetchOpenSky(icao)?.let { return it }
        return null
    }

    // Non-US: hexdb.io → OpenSky → adsbdb
    private suspend fun lookupIntl(icao: String): AircraftMeta? {
        fetchHexdb(icao)?.let { return it }
        fetchOpenSky(icao)?.let { return it }
        fetchAdsbdb(icao)?.let { return it }
        return null
    }

    private suspend fun fetchHexdb(icao: String): AircraftMeta? = runCatching {
        val resp: HexdbResponse = client.get("https://hexdb.io/api/v1/aircraft/${icao.lowercase()}") {
            headers { append(HttpHeaders.UserAgent, "adsb-receiver/1.0 (open source)") }
        }.body()
        val reg  = resp.registration.present()
        val mfr  = resp.manufacturer.present()
        val mdl  = resp.type.present()
        val own  = resp.registeredOwners.present()
        val tc   = resp.icaoTypeCode.present()?.uppercase()
        if (reg == null && mfr == null && mdl == null) return@runCatching null
        AircraftMeta(icao, reg, mfr, mdl, tc, own, "hexdb")
    }.getOrElse {
        Log.d(TAG, "hexdb.io error for $icao: $it")
        null
    }

    private suspend fun fetchOpenSky(icao: String): AircraftMeta? = runCatching {
        val resp: OpenSkyResponse = client.get(
            "https://opensky-network.org/api/metadata/aircraft/icao/${icao.lowercase()}"
        ) {
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

    private suspend fun fetchAdsbdb(icao: String): AircraftMeta? = runCatching {
        val resp: AdsbdbAircraftResponse = client.get(
            "https://api.adsbdb.com/v0/aircraft/${icao.lowercase()}"
        ) {
            headers { append(HttpHeaders.UserAgent, "adsb-receiver/1.0 (open source)") }
        }.body()
        val ac   = resp.response?.aircraft ?: return@runCatching null
        val reg  = ac.registration.present()
        val tc   = ac.type.present()?.uppercase()
        val mfr  = ac.manufacturer.present()
        val mdl  = ac.registerType.present()
        if (reg == null && mfr == null && mdl == null && tc == null) return@runCatching null
        AircraftMeta(icao, reg, mfr, mdl, tc, null, "adsbdb")
    }.getOrElse {
        Log.d(TAG, "adsbdb error for $icao: $it")
        null
    }
}
