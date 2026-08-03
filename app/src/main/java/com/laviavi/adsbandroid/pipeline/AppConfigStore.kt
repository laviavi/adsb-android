package com.laviavi.adsbandroid.pipeline

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.laviavi.adsbandroid.aircraft.AircraftSortOrder
import com.laviavi.adsbandroid.location.ObserverMode
import com.laviavi.adsbandroid.units.DistanceUnit
import kotlinx.coroutines.flow.first

private val Context.configDataStore by preferencesDataStore(name = "app_config")

/** Persists [AppConfig] across process death and app restarts. */
class AppConfigStore(private val context: Context) {

    private object Keys {
        val autoGain        = booleanPreferencesKey("auto_gain")
        val gainTenths      = intPreferencesKey("gain_tenths")
        val ppmCorrection   = intPreferencesKey("ppm_correction")
        val biasTee         = booleanPreferencesKey("bias_tee")
        val gapDivisor      = intPreferencesKey("preamble_gap_divisor")
        val deltaFloor      = intPreferencesKey("delta_floor")
        val crcCorrect      = booleanPreferencesKey("crc_correct_single_bit")
        val crcCorrectTwoBit = booleanPreferencesKey("crc_correct_two_bit")
        val expirySeconds   = intPreferencesKey("aircraft_expiry_seconds")
        val enrichment      = booleanPreferencesKey("enrichment_enabled")
        val rawLogging      = booleanPreferencesKey("raw_logging_enabled")
        val observerLat     = doublePreferencesKey("observer_latitude")
        val observerLon     = doublePreferencesKey("observer_longitude")
        val observerMode    = stringPreferencesKey("observer_mode")
        val gpsRefreshMin   = intPreferencesKey("gps_refresh_interval_minutes")
        val watchdogMin     = intPreferencesKey("source_watchdog_timeout_minutes")
        val offlineMode          = booleanPreferencesKey("offline_mode")
        val offlineTileUrl       = stringPreferencesKey("offline_tile_url_template")
        val offlineDownload      = booleanPreferencesKey("offline_download_enabled")
        val sortOrder            = stringPreferencesKey("aircraft_sort_order")
        val lowAcceptRateAlert   = intPreferencesKey("low_accept_rate_alert_pct")
        val acceptRateWindow     = intPreferencesKey("accept_rate_window_seconds")
        val distanceUnit         = stringPreferencesKey("distance_unit")
        val mapRangeRings        = booleanPreferencesKey("map_show_range_rings")
        val mapLabels            = booleanPreferencesKey("map_show_labels")
        val mapGroundTraffic     = booleanPreferencesKey("map_show_ground_traffic")
        val mapTrailLength       = intPreferencesKey("map_trail_length")
        val mapRingRadii         = stringPreferencesKey("map_ring_radii_mi")
    }

    suspend fun load(): AppConfig {
        val defaults = AppConfig()
        val prefs = context.configDataStore.data.first()
        return AppConfig(
            autoGain        = prefs[Keys.autoGain] ?: defaults.autoGain,
            gainTenths      = prefs[Keys.gainTenths] ?: defaults.gainTenths,
            ppmCorrection   = prefs[Keys.ppmCorrection] ?: defaults.ppmCorrection,
            biasTee         = prefs[Keys.biasTee] ?: defaults.biasTee,
            preambleGapDivisor = prefs[Keys.gapDivisor] ?: defaults.preambleGapDivisor,
            deltaFloor         = prefs[Keys.deltaFloor] ?: defaults.deltaFloor,
            crcCorrectSingleBit   = prefs[Keys.crcCorrect] ?: defaults.crcCorrectSingleBit,
            crcCorrectTwoBit      = prefs[Keys.crcCorrectTwoBit] ?: defaults.crcCorrectTwoBit,
            aircraftExpirySeconds = prefs[Keys.expirySeconds] ?: defaults.aircraftExpirySeconds,
            enrichmentEnabled     = prefs[Keys.enrichment] ?: defaults.enrichmentEnabled,
            offlineMode           = prefs[Keys.offlineMode] ?: defaults.offlineMode,
            offlineTileUrlTemplate = prefs[Keys.offlineTileUrl] ?: defaults.offlineTileUrlTemplate,
            offlineDownloadEnabled = prefs[Keys.offlineDownload] ?: defaults.offlineDownloadEnabled,
            rawLoggingEnabled     = prefs[Keys.rawLogging] ?: defaults.rawLoggingEnabled,
            observerLatitude      = prefs[Keys.observerLat] ?: defaults.observerLatitude,
            observerLongitude     = prefs[Keys.observerLon] ?: defaults.observerLongitude,
            observerMode          = prefs[Keys.observerMode]?.let { runCatching { ObserverMode.valueOf(it) }.getOrNull() } ?: defaults.observerMode,
            gpsRefreshIntervalMinutes   = prefs[Keys.gpsRefreshMin] ?: defaults.gpsRefreshIntervalMinutes,
            sourceWatchdogTimeoutMinutes = prefs[Keys.watchdogMin] ?: defaults.sourceWatchdogTimeoutMinutes,
            sortOrder = prefs[Keys.sortOrder]
                ?.let { runCatching { AircraftSortOrder.valueOf(it) }.getOrNull() }
                ?: defaults.sortOrder,
            lowAcceptRateAlertPct = prefs[Keys.lowAcceptRateAlert] ?: defaults.lowAcceptRateAlertPct,
            acceptRateWindowSeconds = prefs[Keys.acceptRateWindow] ?: defaults.acceptRateWindowSeconds,
            distanceUnit = prefs[Keys.distanceUnit]
                ?.let { runCatching { DistanceUnit.valueOf(it) }.getOrNull() }
                ?: defaults.distanceUnit,
            mapShowRangeRings    = prefs[Keys.mapRangeRings] ?: defaults.mapShowRangeRings,
            mapShowLabels        = prefs[Keys.mapLabels] ?: defaults.mapShowLabels,
            mapShowGroundTraffic = prefs[Keys.mapGroundTraffic] ?: defaults.mapShowGroundTraffic,
            mapTrailLength       = prefs[Keys.mapTrailLength] ?: defaults.mapTrailLength,
            mapRingRadiiMi = prefs[Keys.mapRingRadii]
                ?.let { s -> if (s.isBlank()) emptyList() else s.split(",").mapNotNull { it.trim().toIntOrNull() } }
                ?: defaults.mapRingRadiiMi,
        )
    }

    suspend fun save(config: AppConfig) {
        context.configDataStore.edit { prefs ->
            prefs[Keys.autoGain]      = config.autoGain
            prefs[Keys.gainTenths]    = config.gainTenths
            prefs[Keys.ppmCorrection] = config.ppmCorrection
            prefs[Keys.biasTee]       = config.biasTee
            prefs[Keys.gapDivisor]    = config.preambleGapDivisor
            prefs[Keys.deltaFloor]    = config.deltaFloor
            prefs[Keys.crcCorrect]    = config.crcCorrectSingleBit
            prefs[Keys.crcCorrectTwoBit] = config.crcCorrectTwoBit
            prefs[Keys.expirySeconds] = config.aircraftExpirySeconds
            prefs[Keys.enrichment]    = config.enrichmentEnabled
            prefs[Keys.offlineMode]   = config.offlineMode
            prefs[Keys.offlineTileUrl] = config.offlineTileUrlTemplate
            prefs[Keys.offlineDownload] = config.offlineDownloadEnabled
            prefs[Keys.rawLogging]    = config.rawLoggingEnabled
            prefs[Keys.observerLat]   = config.observerLatitude
            prefs[Keys.observerLon]   = config.observerLongitude
            prefs[Keys.observerMode]  = config.observerMode.name
            prefs[Keys.gpsRefreshMin] = config.gpsRefreshIntervalMinutes
            prefs[Keys.watchdogMin]   = config.sourceWatchdogTimeoutMinutes
            prefs[Keys.sortOrder]          = config.sortOrder.name
            prefs[Keys.lowAcceptRateAlert] = config.lowAcceptRateAlertPct
            prefs[Keys.acceptRateWindow]   = config.acceptRateWindowSeconds
            prefs[Keys.distanceUnit]       = config.distanceUnit.name
            prefs[Keys.mapRangeRings]      = config.mapShowRangeRings
            prefs[Keys.mapLabels]          = config.mapShowLabels
            prefs[Keys.mapGroundTraffic]   = config.mapShowGroundTraffic
            prefs[Keys.mapTrailLength]     = config.mapTrailLength
            prefs[Keys.mapRingRadii]       = config.mapRingRadiiMi.joinToString(",")
        }
    }
}
