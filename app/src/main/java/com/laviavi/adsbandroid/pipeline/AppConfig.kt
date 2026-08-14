package com.laviavi.adsbandroid.pipeline

import com.laviavi.adsbandroid.aircraft.AircraftSort
import com.laviavi.adsbandroid.aircraft.AircraftSortOrder
import com.laviavi.adsbandroid.location.ObserverMode
import com.laviavi.adsbandroid.map.BaseMap
import com.laviavi.adsbandroid.ui.map.RingColorPreset
import com.laviavi.adsbandroid.ui.map.RingLineStyle
import com.laviavi.adsbandroid.ui.map.RingWidth
import com.laviavi.adsbandroid.units.DistanceUnit

/**
 * User-visible receiver settings.
 *
 * There is no source selection: the USB RTL-SDR dongle on OTG is the only signal
 * source. Network/file/dummy sources were removed along with `sourceType`, whose
 * NETWORK default pointed a fresh install at a hard-coded LAN address instead of
 * the antenna.
 */
data class AppConfig(
    /** True = hand gain to the tuner's own AGC; false = pin [gainTenths] from the device's table. */
    val autoGain: Boolean               = true,
    /**
     * Manual gain in tenths of a dB, or [GAIN_UNSET] when the user hasn't chosen
     * one. Deliberately not defaulted to a number: 0 is a real (minimum-gain)
     * level on an R82xx, so defaulting to it would silently pin the tuner to its
     * least sensitive step. Unset means "leave the tuner alone and say so".
     */
    val gainTenths: Int                 = GAIN_UNSET,
    val ppmCorrection: Int              = 0,
    /** Powers the dongle's bias tee (for inline LNAs) over the live rtl_tcp control channel. */
    val biasTee: Boolean                = false,

    /**
     * Preamble gap divisor. Higher accepts weaker preambles, lower is stricter.
     * Applied to the live demodulator without a reconnect.
     */
    val preambleGapDivisor: Int         = DEFAULT_PREAMBLE_GAP_DIVISOR,
    /**
     * Minimum mean bit-pair delta for a frame to be accepted. Lower accepts less
     * signal contrast; too low floods the decoder with noise, too high silences
     * it. Applied live.
     */
    val deltaFloor: Int                 = DEFAULT_DELTA_FLOOR,

    val crcCorrectSingleBit: Boolean    = true,
    /**
     * Independent of [crcCorrectSingleBit] — either, both, or neither may be on.
     * Off by default: two-bit correction has a higher false-accept rate than
     * single-bit (see [com.laviavi.adsbandroid.crc.CrcChecker]'s doc comment).
     */
    val crcCorrectTwoBit: Boolean       = false,
    val aircraftExpirySeconds: Int      = 60,
    /**
     * Master network kill switch. When true nothing in the app opens a socket —
     * enrichment stops and the map serves cached tiles only.
     *
     * Deliberately separate from [enrichmentEnabled] and checked *in addition* to
     * it, rather than implemented by flipping that flag off: enrichment is a
     * preference the user set for its own reasons, and a temporary offline period
     * must not silently rewrite it. This also has to gate map tiles, which
     * [enrichmentEnabled] never covered — see `MapScreen`'s `setUseDataConnection`.
     *
     * Decoding is unaffected either way. The dongle is the only signal source and
     * it is a USB device; the receiver has never needed the internet to work.
     */
    val offlineMode: Boolean            = false,
    val enrichmentEnabled: Boolean      = true,
    val rawLoggingEnabled: Boolean      = false,
    val observerLatitude: Double        = 33.9524737,
    val observerLongitude: Double       = -117.3317861,
    val observerMode: ObserverMode      = ObserverMode.FIXED,
    /** Minutes between periodic high-accuracy GPS re-fixes while Follow GPS is active. 0 = disabled. */
    val gpsRefreshIntervalMinutes: Int  = 60,
    /** Minutes with no active source before the app stops itself to save battery. 0 = disabled. */
    val sourceWatchdogTimeoutMinutes: Int = 5,
    /**
     * Live-list display order. Defaults to first-seen because it is the only
     * order that does not reshuffle rows while someone is reading them — every
     * other key (distance, altitude, message count, last seen) changes
     * continuously in flight. Presentation-only: applying it never restarts the
     * pipeline or touches the aircraft table, see [AircraftSort].
     */
    val sortOrder: AircraftSortOrder    = AircraftSortOrder.FIRST_SEEN,
    /** Blink the receiver badge red when accept rate drops below this percentage. 0 = off. */
    val lowAcceptRateAlertPct: Int      = 20,
    /** Rolling window used to compute the accept rate, in seconds. Changing this resets the counters. */
    val acceptRateWindowSeconds: Int    = 10,

    /** Display unit for every distance shown. Presentation-only — nothing is stored converted. */
    val distanceUnit: DistanceUnit      = DistanceUnit.MILES,

    // --- Map layers. Presentation-only; none of these touch the pipeline. ---
    /** Which tile source the live map renders. Independent of [offlineTileUrlTemplate]. */
    val mapBaseMap: BaseMap             = BaseMap.OSM,
    val mapShowRangeRings: Boolean      = true,
    val mapShowLabels: Boolean          = true,
    val mapShowGroundTraffic: Boolean   = true,
    /** Trail length in points. One of [TRAIL_LENGTHS]; 0 = trails off. */
    val mapTrailLength: Int             = 0,
    /**
     * Range rings drawn around the observer, in statute miles, innermost first.
     * User-configurable in Settings — up to [MAX_MAP_RINGS] entries, each up to
     * [MAX_MAP_RING_MI] mi. Not sorted on write; sorted at the point of use so
     * editing one ring's value never reorders the rows the user is looking at.
     */
    val mapRingRadiiMi: List<Int>       = listOf(10, 20, 30),
    val mapRingColor: RingColorPreset   = RingColorPreset.CYAN,
    val mapRingWidth: RingWidth         = RingWidth.THIN,
    val mapRingLineStyle: RingLineStyle = RingLineStyle.SOLID,

    /**
     * Tile endpoint for *downloading* offline maps, with `{z}`/`{x}`/`{y}` placeholders.
     *
     * Defaults to the same OpenStreetMap Mapnik source the live map uses. Downloads are
     * gated by [offlineDownloadEnabled] so the URL being set does not itself start any
     * network activity — the user opts in explicitly with the toggle.
     */
    val offlineTileUrlTemplate: String  = "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
    /** When false, offline map downloads are inert; import from cache still works. */
    val offlineDownloadEnabled: Boolean = false,
) {
    /** Downloads are possible only when the toggle is on and an endpoint is named. Import always is. */
    val offlineDownloadConfigured: Boolean get() = offlineDownloadEnabled && offlineTileUrlTemplate.isNotBlank()

    /** URL exposed to the downloader — blank when downloads are disabled, so the downloader stays inert. */
    val effectiveTileUrlTemplate: String get() = if (offlineDownloadEnabled) offlineTileUrlTemplate else ""

    /**
     * The only thing enrichment call sites should test. A derived getter rather
     * than a stored field so no `copy()` can produce a state where offline mode is
     * on and network lookups are still permitted.
     */
    val networkEnrichmentAllowed: Boolean get() = enrichmentEnabled && !offlineMode

    companion object {
        /** Sentinel for "no manual gain chosen" — outside every librtlsdr gain table. */
        const val GAIN_UNSET = Int.MIN_VALUE

        // Mirrors the demodulator's own defaults; the tuning UI offers these ranges.
        const val DEFAULT_PREAMBLE_GAP_DIVISOR = 6
        const val GAP_DIVISOR_MIN = 3
        const val GAP_DIVISOR_MAX = 12

        const val DEFAULT_DELTA_FLOOR = 2550
        const val DELTA_FLOOR_MIN = 255
        const val DELTA_FLOOR_MAX = 5100
        const val DELTA_FLOOR_STEP = 255

        const val ACCEPT_WINDOW_MIN = 5
        const val ACCEPT_WINDOW_MAX = 60
        const val ACCEPT_WINDOW_STEP = 5

        /** Trail lengths offered by the map layers panel, per the design spec. */
        val TRAIL_LENGTHS = listOf(0, 10, 50, 200)

        const val MAX_MAP_RINGS = 5
        const val MAX_MAP_RING_MI = 250
    }
}

/** GPS hardware accuracy tops out around 5-6 meaningful decimal digits (~11cm/digit); anything beyond is float noise. */
fun Double.roundToGpsPrecision(): Double = kotlin.math.round(this * 1_000_000.0) / 1_000_000.0

/**
 * Which settings actually disturb a live receiver session.
 *
 * PPM and switching auto/manual gain mode force a reconnect; everything else,
 * including picking a different level within the current gain mode and
 * demodulator tuning, is pushed into the running pipeline.
 */
object ConfigChange {

    fun requiresPipelineRestart(old: AppConfig, new: AppConfig): Boolean =
        old.ppmCorrection != new.ppmCorrection || old.autoGain != new.autoGain

    /** Only a level change within the same gain mode — a mode switch restarts instead. */
    fun requiresGainReapply(old: AppConfig, new: AppConfig): Boolean =
        old.autoGain == new.autoGain && old.gainTenths != new.gainTenths

    fun requiresBiasTeeReapply(old: AppConfig, new: AppConfig): Boolean =
        old.biasTee != new.biasTee

    fun requiresDemodRetune(old: AppConfig, new: AppConfig): Boolean =
        old.preambleGapDivisor != new.preambleGapDivisor || old.deltaFloor != new.deltaFloor
}
