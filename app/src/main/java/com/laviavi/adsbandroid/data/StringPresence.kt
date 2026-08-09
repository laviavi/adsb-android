package com.laviavi.adsbandroid.data

/**
 * Trims, and treats the literal string `Null`/`null` as absent.
 *
 * Multiple upstream sources (hexdb.io, the bundled `icao_db.json`, and
 * FlightAware's scraped JSON) spell a missing field as the literal string
 * "Null"/"null" rather than omitting the field or using a real JSON null —
 * every field parsed from any of them must go through this, or the literal
 * word ends up on the Live list as if it were real data.
 */
fun String?.present(): String? =
    this?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
