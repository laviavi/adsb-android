package com.laviavi.adsbandroid.enrich

/**
 * Where an enriched value came from.
 *
 * Every enriched field carries one of these so the UI can say what it knows
 * rather than presenting a guess and a fact identically. Ordered weakest to
 * strongest so [betterThan] can decide whether a newly arrived value should
 * displace one already held.
 */
enum class DataSource {
    /** Derived from the ICAO address by exact algorithm. Offline, always correct for its block. */
    ALGORITHMIC,

    /** Read from a bundled offline database. */
    DATABASE,

    /** Fetched from a network service and cached. */
    NETWORK,

    /** Decoded from the aircraft's own transmissions. Nothing outranks this. */
    DECODED,
    ;

    fun betterThan(other: DataSource?): Boolean = other == null || ordinal > other.ordinal
}

/**
 * What kind of thing [AircraftState.operator] actually names — a registry
 * owner and an operating airline are two different concepts that happen to
 * often be the same string, and often aren't (a leased/financed airliner is
 * routinely registered to a trust bank, not the airline flying it). Every
 * write to `operator` must say which one it is; nothing may write `OWNER`
 * over an already-known `AIRLINE` (see `AircraftManager.setAircraftMeta`).
 */
enum class OperatorKind { AIRLINE, OWNER }

/**
 * The offline half of enrichment: everything derivable from an ICAO address and
 * a callsign with no network, no database and no I/O.
 *
 * Kept separate from the network half deliberately — this always runs, costs
 * nothing, and works in airplane mode, so it must not be gated behind the
 * enrichment setting or delayed by a lookup that may never return.
 */
object OfflineEnrichment {

    data class Result(
        val registration: String? = null,
        val registrationSource: DataSource? = null,
        val operator: String? = null,
        val operatorSource: DataSource? = null,
        val operatorKind: OperatorKind? = null,
    )

    /**
     * Enrich from ICAO and callsign alone.
     *
     * Registration comes from the ICAO address for US aircraft. For a
     * general-aviation aircraft the callsign *is* the registration, which is a
     * stronger source than the algorithm, so it wins.
     */
    fun enrich(icaoHex: String, callsign: String?): Result {
        val cs = callsign?.trim()?.takeIf { it.isNotEmpty() }

        val decodedReg = cs?.takeIf { Airlines.isRegistrationCallsign(it) }
        val derivedReg = Registration.fromIcao(icaoHex)

        val registration = decodedReg ?: derivedReg
        val registrationSource = when {
            decodedReg != null -> DataSource.DECODED
            derivedReg != null -> DataSource.ALGORITHMIC
            else -> null
        }

        val operator = Airlines.fromCallsign(cs)

        return Result(
            registration = registration,
            registrationSource = registrationSource,
            operator = operator,
            operatorSource = operator?.let { DataSource.ALGORITHMIC },
            operatorKind = operator?.let { OperatorKind.AIRLINE },
        )
    }
}
