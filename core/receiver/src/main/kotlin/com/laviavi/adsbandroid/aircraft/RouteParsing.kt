package com.laviavi.adsbandroid.aircraft

/**
 * [AircraftState.route] comes from two enrichment sources with different formats:
 * adsbdb's `"ORIGIN-DEST"` (`RouteEnrichment.parseAdsbdbRoute`) and FlightAware's
 * `"ORIGIN → DEST"` (`PipelineService.maybeEnrichFa`) — the arrow format is what's
 * displayed as-is elsewhere (the Live row, the map's own route text), so the stored
 * value is never normalized to one format. Any caller that needs origin/destination
 * split out (map label, History's origin/destination grouping) must handle both.
 */
private val ROUTE_SEPARATOR = Regex("\\s*(?:→|-)\\s*")

fun routeOrigin(route: String?): String? =
    route?.split(ROUTE_SEPARATOR, limit = 2)?.getOrNull(0)?.trim()?.takeIf { it.isNotBlank() }

fun routeDestination(route: String?): String? =
    route?.split(ROUTE_SEPARATOR, limit = 2)?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
