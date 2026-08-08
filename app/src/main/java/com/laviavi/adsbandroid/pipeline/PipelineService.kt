package com.laviavi.adsbandroid.pipeline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Binder
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.laviavi.adsbandroid.aircraft.AircraftManager
import com.laviavi.adsbandroid.aircraft.ReceiverRepository
import com.laviavi.adsbandroid.aircraft.AircraftState
import com.laviavi.adsbandroid.decoder.DecodedMessage
import com.laviavi.adsbandroid.capture.*
import com.laviavi.adsbandroid.data.AircraftHistoryDao
import com.laviavi.adsbandroid.data.AircraftMetaCacheDao
import com.laviavi.adsbandroid.data.AircraftMetaEnrichment
import com.laviavi.adsbandroid.data.AircraftSeenDao
import com.laviavi.adsbandroid.data.AircraftSeenEntity
import com.laviavi.adsbandroid.data.AircraftHistoryEntity
import com.laviavi.adsbandroid.data.AircraftVisitDao
import com.laviavi.adsbandroid.data.AircraftVisitEntity
import com.laviavi.adsbandroid.data.BestRangeRecordEntity
import com.laviavi.adsbandroid.data.CoverageSampleEntity
import com.laviavi.adsbandroid.data.EnrichmentCacheDao
import com.laviavi.adsbandroid.data.FlightAwareEnrichment
import com.laviavi.adsbandroid.data.IcaoLookup
import com.laviavi.adsbandroid.data.typeDisplay
import com.laviavi.adsbandroid.data.RouteEnrichment
import com.laviavi.adsbandroid.enrich.Airlines
import com.laviavi.adsbandroid.enrich.DataSource
import com.laviavi.adsbandroid.location.GpsPolicy
import com.laviavi.adsbandroid.location.GpsThrottlePolicy
import com.laviavi.adsbandroid.location.ObserverMode
import com.laviavi.adsbandroid.location.ObserverPositionResolver
import com.laviavi.adsbandroid.observability.CompassSector
import com.laviavi.adsbandroid.observability.CoverageMetrics
import com.laviavi.adsbandroid.observability.MessageCounters
import com.laviavi.adsbandroid.observability.PerformanceMetrics
import com.laviavi.adsbandroid.observability.PositionedAircraft
import com.laviavi.adsbandroid.observability.SectorTotal
import com.laviavi.adsbandroid.pipeline.ErrorLog
import com.laviavi.adsbandroid.crc.CrcChecker
import com.laviavi.adsbandroid.crc.IcaoCache
import com.laviavi.adsbandroid.decoder.MessageDecoder
import com.laviavi.adsbandroid.decoder.RawFrame
import com.laviavi.adsbandroid.demod.Demodulator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import javax.inject.Inject

@AndroidEntryPoint
class PipelineService : Service() {

    @Inject lateinit var injectedConfig: AppConfig
    @Inject lateinit var configStore: AppConfigStore
    @Inject lateinit var historyDao: AircraftHistoryDao
    @Inject lateinit var seenDao: AircraftSeenDao
    @Inject lateinit var visitDao: AircraftVisitDao
    @Inject lateinit var coverageSampleDao: com.laviavi.adsbandroid.data.CoverageSampleDao
    @Inject lateinit var bestRangeDao: com.laviavi.adsbandroid.data.BestRangeDao
    @Inject lateinit var enrichmentDao: EnrichmentCacheDao
    @Inject lateinit var aircraftMetaCacheDao: AircraftMetaCacheDao
    @Inject lateinit var offlineMapManager: com.laviavi.adsbandroid.offline.OfflineMapManager

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)

    private val demodulator = Demodulator()
    private val decoder     = MessageDecoder()
    /** Shared with CrcChecker so parity-address frames (DF0/4/5/16/20/21) can be validated. */
    private val icaoCache   = IcaoCache()
    val stats = PipelineStats(serviceScope)

    /**
     * Owns the aircraft table, the fixed-rate publish tick, expiry, and the
     * bounded ingest queue (see `ReceiverRepository`'s own doc comment for the
     * back-pressure/drop-counter rationale). Extracted out of this class
     * (Step 4) so it is unit-testable as plain JVM code.
     */
    private val receiverRepository = ReceiverRepository(
        scope = serviceScope,
        sortOrderProvider = { currentConfig.sortOrder },
        expirySecondsProvider = { currentConfig.aircraftExpirySeconds },
        onUpdated = ::onAircraftUpdated,
        onDeparted = ::recordDeparted,
    )

    private val rawLogger by lazy { RawMessageLogger(applicationContext) }
    private val performanceCsvLogger by lazy { PerformanceCsvLogger(applicationContext) }
    private val coverageCsvLogger by lazy { CoverageCsvLogger(applicationContext) }
    private val routeEnrichment by lazy { RouteEnrichment(enrichmentDao) }
    private val aircraftMetaEnrichment by lazy { AircraftMetaEnrichment(aircraftMetaCacheDao) }
    private val flightAwareEnrichment by lazy { FlightAwareEnrichment(serviceScope) }
    private val routeLookupInFlight = HashSet<String>()
    private val metaLookupInFlight  = HashSet<String>()

    private val locationProvider by lazy { GpsLocationProvider(applicationContext) }
    private val observerPosition = ObserverPositionResolver()
    private val gpsThrottle = GpsThrottlePolicy()
    private var lastPeriodicRefixMs = 0L
    private var hadErrorSinceLastRunning = false
    private val sourceWatchdog = SourceWatchdog(serviceScope) {
        ErrorLog.warn("No active source for ${currentConfig.sourceWatchdogTimeoutMinutes} min - stopping to save battery")
        releaseResources()
        ServiceCompat.stopForeground(this@PipelineService, ServiceCompat.STOP_FOREGROUND_REMOVE)
        _shutdownRequested.value = true
        stopSelf()
        serviceScope.cancel()
    }

    private var pipelineJob: Job? = null
    private var currentSource: IqSource? = null

    /**
     * Serialises stop/start so a new driver session is never requested while the
     * previous loopback socket is still open.
     *
     * rtl_tcp serves exactly one client and holds the USB interface claimed for as
     * long as that client is connected. Closing the old socket fire-and-forget
     * meant a reconnect could fire `iqsrc://` first, which asks the driver to open
     * a device it has not released yet — that fails with LIBUSB_ERROR_BUSY, and no
     * amount of retrying recovers it because every retry repeats the same race.
     */
    private val sessionLock = kotlinx.coroutines.sync.Mutex()
    private val lastHistoryInsertMs = HashMap<String, Long>()
    private var lastNotificationText = "Starting…"

    val aircraft: StateFlow<List<AircraftState>> = receiverRepository.aircraft

    /** Batches dropped by the ingest queue's overflow policy — see `ReceiverRepository`. */
    val droppedBatches: StateFlow<Long> = receiverRepository.droppedBatches

    /** Aircraft that have left the live list, newest departure first. Room-backed. */
    val history by lazy { seenDao.observeAll() }

    /** Every departure ever recorded, newest first — independent of [history]/Clear. Backs the Stats screen. */
    val visits by lazy { visitDao.observeAll() }

    fun clearHistory() {
        serviceScope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { seenDao.clear() } }
    }

    /** Writes the History screen's data to a CSV under app external storage; null on failure. */
    fun exportHistoryCsv(onResult: (java.io.File?) -> Unit = {}) {
        serviceScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val file = runCatching {
                com.laviavi.adsbandroid.data.CsvExporter.exportAircraftSeen(applicationContext, seenDao)
            }.getOrNull()
            onResult(file)
        }
    }

    /** User-triggered from the Live screen's overflow menu — zeroes session totals without a reconnect. */
    fun resetStatsCounters() {
        stats.reset()
    }

    private val _sourceState = MutableStateFlow<SourceState>(SourceState.Idle)
    val sourceState: StateFlow<SourceState> = _sourceState.asStateFlow()

    /** Live coverage for the Receiver polar. Null until enough positioned aircraft exist. */
    private val _coverage = MutableStateFlow<com.laviavi.adsbandroid.observability.CoverageMetricsRow?>(null)
    val coverage: StateFlow<com.laviavi.adsbandroid.observability.CoverageMetricsRow?> = _coverage.asStateFlow()

    /** Coverage aggregated across every 5-minute tick ever recorded, not just the live window. */
    private val _allTimeCoverage = MutableStateFlow<com.laviavi.adsbandroid.observability.CoverageMetricsRow?>(null)
    val allTimeCoverage: StateFlow<com.laviavi.adsbandroid.observability.CoverageMetricsRow?> = _allTimeCoverage.asStateFlow()

    private val _bestRangeEver = MutableStateFlow<BestRangeRecordEntity?>(null)
    val bestRangeEver: StateFlow<BestRangeRecordEntity?> = _bestRangeEver.asStateFlow()

    /**
     * Farthest distance seen in the current receiver session — unlike an
     * instantaneous max over the live aircraft table, this doesn't drop back
     * down once the far aircraft leaves range. Resets only where a new
     * session begins: [clearSessionState] (app start, Start button, a
     * reconnect, or the dongle being replugged) — never on a manual
     * "Reset counters".
     */
    private val _sessionMaxRangeNm = MutableStateFlow<Double?>(null)
    val sessionMaxRangeNm: StateFlow<Double?> = _sessionMaxRangeNm.asStateFlow()

    /**
     * True once the idle-source watchdog has fully released every resource
     * this service holds and stopped itself. [Service.stopSelf] alone does
     * not destroy a service that is still bound — and `MainActivity` stays
     * bound for as long as it's alive, not just while in the foreground — so
     * without an explicit signal the "stopped" service would sit inert but
     * still resident, still holding its binding, indefinitely. MainActivity
     * observes this to unbind once it fires.
     */
    private val _shutdownRequested = MutableStateFlow(false)
    val shutdownRequested: StateFlow<Boolean> = _shutdownRequested.asStateFlow()

    private val _config = MutableStateFlow(AppConfig())
    val config: StateFlow<AppConfig> = _config.asStateFlow()

    /** Resolved observer lat/lon — the live GPS fix in FOLLOW_GPS mode, else the fixed coordinates. */
    private val _resolvedObserverPosition =
        MutableStateFlow(AppConfig().observerLatitude to AppConfig().observerLongitude)
    val resolvedObserverPosition: StateFlow<Pair<Double, Double>> = _resolvedObserverPosition.asStateFlow()

    /** Manual gain steps reported by the attached dongle, or why they're unavailable. */
    private val _gainOptions = MutableStateFlow<GainOptions>(
        GainOptions.Unavailable("Connect a USB dongle to read its supported gain levels.")
    )
    val gainOptions: StateFlow<GainOptions> = _gainOptions.asStateFlow()

    private val currentConfig get() = _config.value

    /**
     * Applies a settings change with the least disruption it allows: only the
     * fields that define what we connect to force a reconnect. Gain changes go
     * out over the live rtl_tcp control channel; everything else (observer,
     * logging, watchdog, CRC options) is picked up in place by the running loop.
     */
    fun updateConfig(newConfig: AppConfig) {
        val old = _config.value
        _config.value = newConfig
        serviceScope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { configStore.save(newConfig) } }

        if (ConfigChange.requiresPipelineRestart(old, newConfig)) {
            restartPipeline()
            return
        }
        if (ConfigChange.requiresGainReapply(old, newConfig)) {
            serviceScope.launch { applyGainToSource() }
        }
        if (ConfigChange.requiresBiasTeeReapply(old, newConfig)) {
            serviceScope.launch { applyBiasTeeToSource() }
        }
        // Changing a demod knob invalidates the rolling window: the rate it shows
        // was measured under the previous tuning. Only the window is cleared —
        // session totals and uptime survive, so this is not a `reset()`.
        if (ConfigChange.requiresDemodRetune(old, newConfig)) {
            applyDemodTuning()
            stats.resetWindow()
        }
        // The only publish that can't wait for the next tick: a user picking a
        // different sort order in Settings expects to see it applied immediately.
        if (old.sortOrder != newConfig.sortOrder) serviceScope.launch { receiverRepository.publishNow() }
        if (old.acceptRateWindowSeconds != newConfig.acceptRateWindowSeconds) {
            stats.windowSeconds = newConfig.acceptRateWindowSeconds
            stats.resetWindow()
        }
        applyObserverPosition()
    }

    /**
     * Pushes the demodulator thresholds into the running instance.
     *
     * The demodulator is long-lived and reads these on every buffer, so a change
     * takes effect on the next block — no reconnect, and the message counters
     * keep running so the effect is visible immediately.
     */
    private fun applyDemodTuning() {
        demodulator.preambleGapDivisor = currentConfig.preambleGapDivisor
        demodulator.deltaFloor = currentConfig.deltaFloor
        ErrorLog.info(
            "Demod retuned live: gap divisor ${currentConfig.preambleGapDivisor}, " +
                "delta floor ${currentConfig.deltaFloor}"
        )
    }

    /** Pushes the configured gain to a live USB source. No-op for other source types. */
    private suspend fun applyGainToSource() {
        val usb = currentSource as? UsbRtlSdrSource ?: return
        if (!currentConfig.autoGain && currentConfig.gainTenths == AppConfig.GAIN_UNSET) {
            ErrorLog.warn("Manual gain selected but no level chosen — leaving the tuner unchanged")
            return
        }
        val ok = usb.applyGain(currentConfig.autoGain, currentConfig.gainTenths)
        ErrorLog.info(
            if (ok) "Gain applied live: " +
                (if (currentConfig.autoGain) "auto (tuner AGC)" else "${currentConfig.gainTenths} tenths dB")
            else "Gain change could not be sent to the driver"
        )
    }

    /** Pushes the configured bias-tee state to a live USB source. No-op for other source types. */
    private suspend fun applyBiasTeeToSource() {
        val usb = currentSource as? UsbRtlSdrSource ?: return
        val ok = usb.applyBiasTee(currentConfig.biasTee)
        ErrorLog.info(
            if (ok) "Bias tee ${if (currentConfig.biasTee) "enabled" else "disabled"}"
            else "Bias tee change could not be sent to the driver"
        )
    }

    // Both callbacks are accelerators, not the recovery mechanism: the retry loop
    // in startPipeline() reconnects on its own timer whether or not these fire.
    private val hotplugReceiver = UsbHotplugReceiver(
        onAttached = { restartPipeline() },
        onDetached = { _sourceState.value = SourceState.Error(NO_DONGLE_MESSAGE) },
    )

    inner class LocalBinder : Binder() {
        fun getService(): PipelineService = this@PipelineService
    }
    private val binder = LocalBinder()
    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        // Explicit type subset (not the 2-arg overload) - the 2-arg call implicitly requests
        // every type declared in the manifest, which would require the location permission
        // to be granted just to start the service at all, even in Fixed mode.
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification("Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        UsbHotplugReceiver.register(this, hotplugReceiver)
        serviceScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            receiverRepository.setLookup(IcaoLookup.load(applicationContext))
        }
        receiverRepository.setDecoder(decoder)
        receiverRepository.start()
        applyDemodTuning()
        serviceScope.launch {
            while (true) {
                kotlinx.coroutines.delay(3_600_000L)
                val cutoff = System.currentTimeMillis() - 7 * 24 * 3600 * 1000L
                runCatching { historyDao.purgeOlderThan(cutoff) }
                runCatching { seenDao.purgeOlderThan(cutoff) }
            }
        }
        // Load the last selected source/config before starting - without this, every
        // restart silently reverts to the hardcoded NETWORK default (the original bug).
        serviceScope.launch {
            _config.value = runCatching { configStore.load() }.getOrDefault(injectedConfig)
            stats.windowSeconds = currentConfig.acceptRateWindowSeconds
            updateForegroundLocationType()
            sessionLock.withLock { startPipelineInternal() }
            requestFreshGpsFix() // "at every app start" - never trust a cached/last-known fix
            refreshGpsCoordinates() // ditto, but persisted as the saved coordinates regardless of mode
            refreshAllTimeCoverage()
            _bestRangeEver.value = runCatching { bestRangeDao.get() }.getOrNull()
        }
        serviceScope.launch {
            _sourceState.collect { state ->
                val running = state is SourceState.Running
                sourceWatchdog.onStateChanged(running, currentConfig.sourceWatchdogTimeoutMinutes * 60_000L)

                if (state is SourceState.Error) hadErrorSinceLastRunning = true
                if (GpsPolicy.shouldRefixOnReconnect(hadErrorSinceLastRunning, running)) {
                    observerPosition.clearLiveFix()
                    gpsThrottle.reset()
                    applyObserverPosition()
                    requestFreshGpsFix()
                }
                if (running) hadErrorSinceLastRunning = false

                updateForegroundLocationType()
                if (GpsPolicy.shouldRunContinuousGps(currentConfig.observerMode, running) && locationProvider.hasPermission()) {
                    locationProvider.startUpdates(gpsThrottle.currentParams(), ::onGpsFix)
                } else {
                    locationProvider.stopUpdates()
                }
            }
        }
        serviceScope.launch {
            while (true) {
                delay(60_000L)
                val running = _sourceState.value is SourceState.Running
                if (currentConfig.observerMode == ObserverMode.FOLLOW_GPS && running &&
                    GpsPolicy.isPeriodicRefixDue(lastPeriodicRefixMs, System.currentTimeMillis(), currentConfig.gpsRefreshIntervalMinutes)
                ) {
                    requestFreshGpsFix()
                }
            }
        }
        serviceScope.launch {
            while (true) {
                delay(PerformanceMetrics.INTERVAL_SEC * 1_000L)
                performanceCsvLogger.tick(currentMessageCounters(), receiverRepository.aircraft.value.map { it.icao }.toSet())
            }
        }
        serviceScope.launch {
            while (true) {
                delay(CoverageMetrics.INTERVAL_SEC * 1_000L)
                val lat = currentConfig.observerLatitude
                val lon = currentConfig.observerLongitude
                val positioned = currentPositionedAircraft()
                coverageCsvLogger.tick(lat, lon, positioned)
                recordCoverageSample(positioned)
                checkBestRange()
            }
        }
        // The Receiver coverage card refreshes far faster than the 5-minute CSV
        // row: the CSV is a long-run record, this is a live instrument. Same
        // computation, different cadence — the CSV cadence is not changed.
        serviceScope.launch {
            while (true) {
                delay(COVERAGE_UI_INTERVAL_MS)
                _coverage.value = CoverageMetrics.computeRow(
                    currentConfig.observerLatitude,
                    currentConfig.observerLongitude,
                    currentPositionedAircraft(),
                )
            }
        }
    }

    private fun currentMessageCounters() = MessageCounters(
        total = stats.totalMessages.get(),
        valid = stats.validMessages.get(),
        corrected = stats.correctedMessages.get(),
        recovered = stats.recoveredMessages.get(),
        badCrc = stats.badCrcMessages.get(),
    )

    private fun currentPositionedAircraft() = receiverRepository.aircraft.value.mapNotNull { ac ->
        val dist = ac.distanceNm
        val bearing = ac.bearingDeg
        if (dist == null || bearing == null) null
        else PositionedAircraft(dist, bearing, ac.altitudeFt, ac.signalDbfs)
    }

    /** Persists this tick's non-empty sectors, then refreshes the all-time view from the full history. */
    private suspend fun recordCoverageSample(positioned: List<PositionedAircraft>) {
        val row = CoverageMetrics.computeRow(currentConfig.observerLatitude, currentConfig.observerLongitude, positioned)
            ?: return
        val now = System.currentTimeMillis()
        val samples = row.sectors.filterValues { it.count > 0 }.map { (sector, s) ->
            CoverageSampleEntity(
                timestampMs = now, sector = sector.name,
                count = s.count, maxMi = s.maxMi, medianSignalDbfs = s.medianSignalDbfs,
            )
        }
        if (samples.isEmpty()) return
        runCatching { coverageSampleDao.insertAll(samples) }
        refreshAllTimeCoverage()
    }

    private suspend fun refreshAllTimeCoverage() {
        val totals = runCatching { coverageSampleDao.allTimeBySector() }.getOrNull() ?: return
        val sectorTotals = totals.mapNotNull { agg ->
            runCatching { CompassSector.valueOf(agg.sector) }.getOrNull()?.let { SectorTotal(it, agg.count, agg.maxMi) }
        }
        _allTimeCoverage.value = CoverageMetrics.synthesizeAllTimeRow(
            sectorTotals, currentConfig.observerLatitude, currentConfig.observerLongitude,
        )
    }

    /** Not per-message by design — a personal best doesn't need per-fix precision, and this avoids a DB round-trip on every decoded position. */
    private suspend fun checkBestRange() {
        val best = receiverRepository.aircraft.value
            .filter { it.distanceNm != null && it.bearingDeg != null }
            .maxByOrNull { it.distanceNm!! } ?: return
        val current = runCatching { bestRangeDao.get() }.getOrNull()
        if (!isNewBestRange(best.distanceNm!!, current)) return
        val record = BestRangeRecordEntity(
            icao = best.icao,
            callsign = best.callsign?.trim()?.takeIf { it.isNotEmpty() },
            distanceNm = best.distanceNm!!,
            bearingDeg = best.bearingDeg!!,
            altitudeFt = best.altitudeFt,
            timestampMs = System.currentTimeMillis(),
        )
        runCatching { bestRangeDao.upsert(record) }
        _bestRangeEver.value = record
    }

    /**
     * Full, blocking-safe teardown of everything this service holds: the active
     * pipeline session, GPS updates, the hotplug receiver, every logger, and the
     * three enrichment classes' HTTP clients (previously never explicitly closed
     * — their ktor engines just leaked until the process died). Shared by
     * [onDestroy], the idle-source watchdog, and [exit] so there is exactly one
     * place that has to remember everything that needs releasing.
     */
    private suspend fun releaseResources() {
        runCatching { unregisterReceiver(hotplugReceiver) }
        locationProvider.stopUpdates()
        try {
            // Bounded on its own: NetworkSource.readSamples() wraps a plain blocking
            // Java InputStream.read() — coroutine cancellation cannot interrupt it,
            // only its own 3s socket timeout (RtlSdrDefaults.IQ_READ_TIMEOUT_MS) can.
            // A caller-imposed outer timeout shorter than that (exit() used to give
            // this whole function 2s, onDestroy() only 500ms) could expire while this
            // join is still waiting on a mid-read pipeline, cancelling this function
            // before it ever reached the close below — the finally block is what
            // guarantees that no longer matters.
            withTimeoutOrNull(1_500) { pipelineJob?.cancelAndJoin() }
        } finally {
            // NonCancellable: must run to completion even if this coroutine has
            // already been cancelled by an ambient/caller timeout — otherwise the
            // socket close, and everything after it, can be silently skipped.
            // Closing the driver socket while still able to run suspend calls
            // matters: rtl_tcp keeps the USB interface claimed for as long as a
            // client is attached, which is what left the dongle unopenable until
            // the driver app was force-stopped by hand.
            withContext(NonCancellable) {
                pipelineJob = null
                val src = currentSource
                currentSource = null
                withTimeoutOrNull(500) { runCatching { src?.close() } }
                rawLogger.close()
                performanceCsvLogger.close()
                coverageCsvLogger.close()
                runCatching { routeEnrichment.close() }
                runCatching { aircraftMetaEnrichment.close() }
                runCatching { flightAwareEnrichment.close() }
            }
        }
    }

    /**
     * User-triggered full exit (Settings/Traffic → Exit app). Releases every
     * resource this service holds, same as the idle watchdog, then stops the
     * service. Blocks the caller until [releaseResources] actually finishes
     * (its own internal bounds and NonCancellable guarantee this can't hang
     * indefinitely) so it's safe to unbind and finish the Activity right
     * after this returns, instead of racing the async cleanup.
     */
    fun exit() {
        runBlocking { releaseResources() }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        serviceScope.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        runBlocking { releaseResources() }
        serviceScope.cancel()
    }

    /** Requests one fresh (never cached) high-accuracy fix; applied via [onGpsFix] when it arrives. */
    private fun requestFreshGpsFix() {
        if (currentConfig.observerMode != ObserverMode.FOLLOW_GPS) return
        updateForegroundLocationType()
        lastPeriodicRefixMs = System.currentTimeMillis()
        serviceScope.launch {
            locationProvider.requestFreshFix()?.let { onGpsFix(it) }
        }
    }

    /**
     * One-shot high-accuracy fix that is *persisted* as the saved observer
     * coordinates, independent of Fixed/Follow-GPS mode. Runs at every app start
     * and from the Settings "Update GPS" button — unlike [requestFreshGpsFix],
     * which only feeds the live/transient position while Follow GPS is active.
     */
    fun refreshGpsCoordinates(onResult: (Boolean) -> Unit = {}) {
        if (!locationProvider.hasPermission()) {
            onResult(false)
            return
        }
        // The foreground service must hold the LOCATION type for the duration of
        // any location API call (Android 14+) — normally only promoted in
        // Follow-GPS mode by updateForegroundLocationType(), so promote it here
        // for this one-shot fetch and let that function settle it back after.
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(lastNotificationText),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
        serviceScope.launch {
            val fix = locationProvider.requestFreshFix()
            updateForegroundLocationType()
            if (fix == null) {
                onResult(false)
                return@launch
            }
            val updated = currentConfig.copy(observerLatitude = fix.latitude, observerLongitude = fix.longitude)
            _config.value = updated
            withContext(Dispatchers.IO) { runCatching { configStore.save(updated) } }
            applyObserverPosition()
            onResult(true)
        }
    }

    /**
     * Promotes/demotes the running foreground service's declared type set. Android 14+
     * requires the "location" type to be actively held (via this call, not just the
     * manifest) before any location API use, foreground-service-only - no background
     * location permission needed.
     */
    private fun updateForegroundLocationType() {
        val wantLocation = currentConfig.observerMode == ObserverMode.FOLLOW_GPS && locationProvider.hasPermission()
        val type = if (wantLocation) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(lastNotificationText), type)
    }

    /** Applies an accepted fix (from a fresh-fix request or continuous updates) without a pipeline restart. */
    private fun onGpsFix(location: Location) {
        observerPosition.applyLiveFix(location.latitude, location.longitude)
        applyObserverPosition()
        noteTravel(location.latitude, location.longitude)
        val params = gpsThrottle.onFix(location.latitude, location.longitude)
        if (GpsPolicy.shouldRunContinuousGps(currentConfig.observerMode, _sourceState.value is SourceState.Running)) {
            locationProvider.startUpdates(params, ::onGpsFix)
        }
    }

    /**
     * Records movement outside stored offline coverage.
     *
     * Writes a note only — no tiles are fetched here and the network is never
     * consulted, which is what makes it safe to run while travelling on cellular.
     * Off the main thread because it reads the manifest from disk.
     */
    private fun noteTravel(lat: Double, lon: Double) {
        serviceScope.launch(Dispatchers.IO) {
            runCatching { offlineMapManager.observePosition(lat, lon) }
                .onFailure { ErrorLog.warn("Travel tracking skipped: ${it.message}") }
        }
    }

    /** Pushes the resolved observer position (fixed or live GPS) into decoder/receiverRepository. */
    private fun applyObserverPosition() {
        val (lat, lon) = observerPosition.resolve(
            currentConfig.observerMode, currentConfig.observerLatitude, currentConfig.observerLongitude,
        )
        decoder.observerLat = lat
        decoder.observerLon = lon
        receiverRepository.setObserverPosition(lat, lon)
        _resolvedObserverPosition.value = lat to lon
    }

    fun stopPipeline() {
        serviceScope.launch { sessionLock.withLock { teardownSession() } }
    }

    fun startPipeline() {
        serviceScope.launch {
            sessionLock.withLock {
                if (pipelineJob?.isActive == true) return@withLock
                clearSessionState()
                startPipelineInternal()
            }
        }
    }

    fun reconnect() = restartPipeline()

    fun restartPipeline() {
        serviceScope.launch {
            sessionLock.withLock {
                teardownSession()
                clearSessionState()
                startPipelineInternal()
            }
        }
    }

    /**
     * Ends the current session and does not return until the driver socket is
     * actually closed — see [sessionLock] for why the ordering is load-bearing.
     */
    private suspend fun teardownSession() {
        pipelineJob?.cancelAndJoin()
        pipelineJob = null
        val src = currentSource
        currentSource = null
        withContext(Dispatchers.IO) { runCatching { src?.close() } }
        _sourceState.value = SourceState.Idle
    }

    /**
     * Drops everything scoped to one receiver session, so a reconnect never
     * merges the new session's frames into the old one's state.
     *
     * The stats reset is also what re-arms the accept-rate alarm's warmup: the
     * threshold is only meaningful once enough messages have been counted, and a
     * reconnect starts that count from zero.
     */
    private fun clearSessionState() {
        stats.reset()
        icaoCache.clear()
        lastHistoryInsertMs.clear()
        routeLookupInFlight.clear()
        metaLookupInFlight.clear()
        _sessionMaxRangeNm.value = null
        // Reset runs on the repository's own confined dispatcher and is
        // submitted before startPipeline(), so it always finishes ahead of the
        // first decoded frame from the new session.
        serviceScope.launch { receiverRepository.reset(); receiverRepository.publishNow() }
    }

    /**
     * Opens the source and keeps it open, reconnecting on its own schedule for as
     * long as the job lives.
     *
     * Previously this ran once and a failure restarted it exactly once, two
     * seconds later — so if the dongle had not been plugged back in by then, the
     * receiver settled into Error and stayed there no matter what happened
     * afterwards. Recovery now needs no attach signal at all, which matters
     * because the only attach signal available comes from another app and cannot
     * be relied on.
     */
    private fun startPipelineInternal() {
        // Overwriting `pipelineJob` while the old one is still live orphans it: it
        // keeps looping, un-cancellable, and fires its own `iqsrc://` a couple of
        // seconds behind the one that just succeeded. The driver then tries to open
        // a device it has already opened, fails with LIBUSB_ERROR_BUSY, and tears
        // down the working session on its way out — the dongle goes dark and only a
        // force-stop of the driver app brings it back.
        if (pipelineJob?.isActive == true) return
        applyObserverPosition()
        pipelineJob = serviceScope.launch {
            var attempt = 0
            while (isActive) {
                try {
                    val source = buildSource()
                    currentSource = source
                    _sourceState.value = SourceState.Connecting

                    // Serialised against startPipeline()/restartPipeline() via the same
                    // sessionLock: without this, a hotplug SDR_DEVICE_ATTACHED broadcast
                    // (see hotplugReceiver.onAttached) can call restartPipeline() while
                    // this retry-loop attempt is independently mid-open, sending two
                    // overlapping iqsrc:// requests for the same device — the exact
                    // LIBUSB_ERROR_BUSY scenario described above, except between this
                    // loop's own reconnect and the hotplug accelerator rather than
                    // between two explicit start/stop calls.
                    sessionLock.withLock { openUsbSource(source) }

                    attempt = 0
                    val tuner = _gainOptions.value.let { it as? GainOptions.Available }?.tuner?.displayName
                    _sourceState.value = SourceState.Running(tuner ?: "USB")
                    updateNotification("Running — ${tuner ?: "USB"}")
                    runPipelineLoop(source)

                    // Only reached on EOF; an exception takes a catch below.
                    ErrorLog.warn("IQ stream ended — reconnecting")
                    _sourceState.value = SourceState.Error(NO_DONGLE_MESSAGE)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SdrDriverNotInstalledException) {
                    // The one failure retrying cannot fix.
                    _sourceState.value = SourceState.DriverNotInstalled
                    updateNotification("Install RTL-SDR driver app")
                    return@launch
                } catch (e: SdrDriverFailedException) {
                    ErrorLog.error("[USB] driver could not open the device: ${e.message}", e)
                    // BUSY is not a missing dongle: the driver still holds the device
                    // from an earlier session. Retrying cannot clear it, and the
                    // driver app exposes no way to ask it to let go.
                    val busy = e.message?.contains("BUSY", ignoreCase = true) == true
                    _sourceState.value =
                        SourceState.Error(if (busy) DRIVER_BUSY_MESSAGE else NO_DONGLE_MESSAGE)
                } catch (e: Exception) {
                    ErrorLog.error(describeError(e), e)
                    _sourceState.value = SourceState.Error(NO_DONGLE_MESSAGE)
                }

                withContext(Dispatchers.IO) { runCatching { currentSource?.close() } }
                currentSource = null
                clearSessionState()
                awaitRetry(attempt++)
            }
        }
    }

    /**
     * Backs off, then blocks until the OS reports a dongle attached.
     *
     * The presence gate is load-bearing rather than an optimisation: opening the
     * source launches [SdrSourceActivity], and firing a trampoline Activity on a
     * timer with no dongle present would flash over whatever is on screen and run
     * into Android's background-activity-launch limits. Polling the USB device
     * list costs nothing and is also the only reliable way to notice the dongle
     * came back — the attach broadcast reaches the manifest filter, not this.
     */
    private suspend fun awaitRetry(attempt: Int) {
        delay(ReconnectPolicy.delayForAttempt(attempt))
        var waited = false
        while (currentCoroutineContext().isActive && !UsbPresence.isDongleAttached(this)) {
            if (!waited) {
                ErrorLog.info("Waiting for a USB dongle to be attached")
                updateNotification("Waiting for USB dongle…")
                waited = true
            }
            delay(ReconnectPolicy.PRESENCE_POLL_MS)
        }
        updateNotification("Reconnecting…")
    }

    /** What failed, the likely cause, and what to do next. */
    private fun describeError(e: Exception): String = when (e) {
        is java.net.SocketTimeoutException ->
            "No IQ samples for ${RtlSdrDefaults.IQ_READ_TIMEOUT_MS / 1000}s. " +
            "Likely cause: the dongle was unplugged and the driver is holding the " +
            "connection open with no device behind it. Next: reconnecting automatically."
        else ->
            "USB RTL-SDR failed: ${e.message ?: e.javaClass.simpleName}. " +
            "Likely cause: the dongle was disconnected or the driver app lost the device " +
            "mid-stream. Next: reconnecting automatically."
    }
    /**
     * Write each departed aircraft's final state to the History table.
     *
     * Done here rather than from the message path because this is the last point
     * at which the merged state exists. Keyed by ICAO, so an aircraft that drops
     * out and returns later updates its row instead of appearing twice.
     */
    private fun recordDeparted(departed: List<AircraftState>) {
        serviceScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            departed.forEach { s ->
                runCatching {
                    seenDao.upsert(AircraftSeenEntity(
                        icao          = s.icao,
                        callsign      = s.callsign?.trim()?.takeIf { it.isNotEmpty() },
                        registration  = s.registration,
                        operator      = s.operator,
                        aircraftType  = s.aircraftType,
                        route         = s.route,
                        altitudeFt    = s.altitudeFt,
                        groundSpeedKt = s.groundSpeedKt,
                        trackDeg      = s.trackDeg,
                        latitudeDeg   = s.latitude,
                        longitudeDeg  = s.longitude,
                        distanceNm    = s.distanceNm,
                        squawk        = s.squawk,
                        messageCount  = s.messageCount,
                        firstSeenMs   = s.firstSeenMs,
                        lastSeenMs    = s.lastSeenMs,
                    ))
                }
                // Independent of aircraft_seen above: one row per departure, never
                // replaced, so it survives History's Clear and builds a "times seen"
                // log the Stats screen reads from.
                val isFirstTime = runCatching { visitDao.countByIcao(s.icao) == 0 }.getOrDefault(false)
                runCatching {
                    visitDao.insert(AircraftVisitEntity(
                        icao          = s.icao,
                        registration  = s.registration,
                        operator      = s.operator,
                        aircraftType  = s.aircraftType,
                        isAirline     = s.operatorSource == DataSource.ALGORITHMIC,
                        firstSeenMs   = s.firstSeenMs,
                        lastSeenMs    = s.lastSeenMs,
                        messageCount  = s.messageCount,
                    ))
                }
                if (isFirstTime) {
                    val label = s.callsign?.trim()?.takeIf { it.isNotEmpty() } ?: s.icao
                    postMilestoneNotification("New aircraft spotted", "$label — first time seen")
                }
            }
        }
    }

    private fun maybeInsertHistory(state: AircraftState) {
        if (state.latitude == null || state.longitude == null) return
        val now = System.currentTimeMillis()
        if (now - (lastHistoryInsertMs[state.icao] ?: 0L) < 30_000) return
        lastHistoryInsertMs[state.icao] = now
        serviceScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                historyDao.insert(AircraftHistoryEntity(
                    icao         = state.icao,
                    callsign     = state.callsign,
                    altitudeFt   = state.altitudeFt,
                    latitudeDeg  = state.latitude,
                    longitudeDeg = state.longitude,
                    groundSpeedKt = state.groundSpeedKt,
                    trackDeg     = state.trackDeg,
                    timestampMs  = now,
                ))
            }
        }
    }

    private fun buildSource(): UsbRtlSdrSource = UsbRtlSdrSource()

    private fun buildCaptureConfig() = CaptureConfig(
        gainTenths    = currentConfig.gainTenths,
        ppmCorrection = currentConfig.ppmCorrection,
    )

    private suspend fun openUsbSource(source: UsbRtlSdrSource) {
        if (!UsbHotplugReceiver.isDriverInstalled(this)) throw SdrDriverNotInstalledException()

        val loopbackConfig = buildCaptureConfig().copy(
            networkHost = UsbRtlSdrSource.LOOPBACK_HOST,
            networkPort = UsbRtlSdrSource.LOOPBACK_PORT,
        )

        // Fast path: a driver session from a previous run may still be listening on
        // the loopback port (its foreground service outlives our process being killed).
        // Re-firing the intent in that case fails, since the old session still holds
        // the port - reuse it instead of forcing the user to manually kill the driver
        // app. Safe to attempt unconditionally because tryConnectExisting now requires
        // real sample flow, so a session left over from a yanked dongle is rejected.
        if (source.tryConnectExisting(loopbackConfig)) {
            ErrorLog.info("USB: reused existing driver session on loopback")
            onUsbConnected(source)
            return
        }

        val launchConfig = SdrLaunchConfig(
            port         = UsbRtlSdrSource.LOOPBACK_PORT,
            frequencyHz  = UsbRtlSdrSource.CENTER_FREQ_HZ,
            sampleRateHz = UsbRtlSdrSource.SAMPLE_RATE_HZ,
            // 0 omits -g from the launch URI; auto mode is then established
            // explicitly via SET_GAIN_MODE in onUsbConnected(), because the
            // driver's own no-gain fallback is a fixed 2.4dB, not real AGC.
            gainTenths   = if (currentConfig.autoGain) 0 else currentConfig.gainTenths,
            ppm          = currentConfig.ppmCorrection,
        )

        suspendCancellableCoroutine<Unit> { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    unregisterReceiver(this)
                    val success = intent.getBooleanExtra(SdrSourceActivity.EXTRA_SUCCESS, false)
                    if (success) cont.resume(Unit)
                    else {
                        val msg = intent.getStringExtra(SdrSourceActivity.EXTRA_ERROR_MESSAGE)
                        cont.resumeWithException(SdrDriverFailedException(-1, msg))
                    }
                }
            }
            ContextCompat.registerReceiver(
                this, receiver,
                IntentFilter(SdrSourceActivity.ACTION_DRIVER_RESULT),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            cont.invokeOnCancellation { runCatching { unregisterReceiver(receiver) } }
            startActivity(SdrSourceActivity.createIntent(this, launchConfig))
        }

        source.openNetworkSource(loopbackConfig)
        onUsbConnected(source)
    }

    /**
     * Publishes the tuner the dongle just identified itself as (so Settings can
     * offer that device's real gain steps) and applies the configured gain.
     */
    private suspend fun onUsbConnected(source: UsbRtlSdrSource) {
        val info = source.dongleInfo
        _gainOptions.value = if (info == null) {
            GainOptions.Unavailable("Dongle did not report a tuner type. Use Auto gain.")
        } else {
            RtlTcpGain.gainsFor(info)
        }
        info?.let { ErrorLog.info("USB tuner: ${it.tuner.displayName}, ${it.reportedGainCount} gain steps") }
        applyGainToSource()
        applyBiasTeeToSource()
    }

    private suspend fun runPipelineLoop(source: IqSource) =
        runIqLoop(source, ByteArray(Demodulator.BLOCK_SIZE))

    /**
     * Errors propagate to [startPipeline]'s retry loop rather than being handled
     * here — a self-restart from inside the loop left the reconnect schedule with
     * no single owner and could only ever fire once.
     */
    private suspend fun runIqLoop(source: IqSource, buffer: ByteArray) {
        ErrorLog.info("IQ loop started")
        while (true) {
            val n = withContext(Dispatchers.IO) { source.readSamples(buffer) }
            if (n < 0) { ErrorLog.warn("IQ source returned EOF"); break }
            stats.bytesRead.addAndGet(n.toLong())
            stats.buffersRead.incrementAndGet()
            val frames = demodulator.demodulate(buffer.copyOf(n))
            stats.candidateCount = demodulator.candidateCount
            processFrames(frames)
        }
    }

    /**
     * [frameList] comes straight from `Demodulator.demodulate()`, which already
     * computes a real per-frame signal level (mean of the four preamble peaks —
     * see `Demodulator.detectFrames`). Previously this method took `List<IntArray>`
     * from a `frames.map { it.bytes }` at the call site and rebuilt a bare
     * `RawFrame(bytes)` here, silently dropping that value back to its 0.0
     * default on every frame — the reason the Signal column always read "—".
     * Taking the original `RawFrame` and reusing it directly (CRC correction
     * already preserves it via `RawFrame.copy()`) is the whole fix.
     */
    private fun processFrames(frameList: List<RawFrame>) {
        // Refreshed once per IQ buffer rather than per frame or per merge: cheap
        // (a StateFlow read + map over a short list), and DF16 intruder
        // resolution is already an approximate heuristic — being up to one
        // publish tick (250 ms) stale doesn't meaningfully change its accuracy.
        decoder.knownIcaos = receiverRepository.aircraft.value
            .mapNotNull { it.icao.toIntOrNull(16) }
            .toSet()
        val bufferBaseMs = System.currentTimeMillis()
        val decoded = ArrayList<DecodedMessage>(frameList.size)
        for (frame in frameList) {
            if (currentConfig.rawLoggingEnabled) {
                rawLogger.log(frame.bytes.joinToString("") { "%02X".format(it) })
            }
            val checked = CrcChecker.check(
                frame,
                currentConfig.crcCorrectSingleBit,
                icaoCache,
                currentConfig.crcCorrectTwoBit,
            )
            stats.totalMessages.incrementAndGet()
            when (checked.crcResult) {
                // RECOVERED = parity-address frame whose address matched a
                // previously CRC-confirmed aircraft; as trustworthy as VALID.
                CrcChecker.CrcResult.VALID -> stats.validMessages.incrementAndGet()
                CrcChecker.CrcResult.RECOVERED -> {
                    stats.validMessages.incrementAndGet()
                    stats.recoveredMessages.incrementAndGet()
                }
                CrcChecker.CrcResult.CORRECTED -> stats.correctedMessages.incrementAndGet()
                CrcChecker.CrcResult.INVALID -> {
                    stats.invalidMessages.incrementAndGet()
                    stats.badCrcMessages.incrementAndGet()
                    continue
                }
                CrcChecker.CrcResult.PARITY_ADDRESS -> {
                    stats.invalidMessages.incrementAndGet()
                    stats.unresolvedMessages.incrementAndGet()
                    continue
                }
            }
            stats.incrementDf(frame.downlinkFormat)
            if (frame.signalLevel > 0.0) {
                val dbfs = AircraftManager.toDbfs(frame.signalLevel)
                if (dbfs >= AircraftManager.STRONG_SIGNAL_THRESHOLD_DBFS)
                    stats.strongSignalCount.incrementAndGet()
            }
            val frameMs = bufferBaseMs + frame.sampleOffset * 1000L / Demodulator.REQUIRED_SAMPLE_RATE_HZ
            decoder.decode(checked, frameMs)?.let { decoded += it }
        }
        // One offer per IQ buffer, not per frame: matches the batch granularity
        // the aircraftDispatcher confinement used before this extraction, and
        // means one queue slot per buffer rather than per message.
        receiverRepository.offer(decoded, bufferBaseMs)
    }

    /**
     * Fired by [receiverRepository] for every message it merges into the table
     * (from its own ingest loop, off the IQ read thread). Triggers the two
     * per-aircraft side effects that used to run inline in [processFrames]:
     * the position track log and the async route lookup.
     */
    private fun onAircraftUpdated(state: AircraftState) {
        maybeInsertHistory(state)
        maybeEnrichRoute(state)
        maybeEnrichMeta(state)
        maybeEnrichFa(state)
        state.distanceNm?.let { d -> if (d > (_sessionMaxRangeNm.value ?: -1.0)) _sessionMaxRangeNm.value = d }
    }

    /** Async route lookup, gated by config, cached, at most once in flight per ICAO. */
    private fun maybeEnrichRoute(state: AircraftState) {
        if (!currentConfig.networkEnrichmentAllowed) return
        if (state.route != null) return
        val callsign = state.callsign?.trim()?.takeIf { it.isNotEmpty() } ?: return
        // A registration callsign is the aircraft's own tail number, not a flight
        // number — adsbdb has no route for it, so the lookup would always miss.
        if (Airlines.isRegistrationCallsign(callsign)) return
        if (!routeLookupInFlight.add(state.icao)) return
        serviceScope.launch(Dispatchers.IO) {
            runCatching { routeEnrichment.lookupRoute(callsign) }.getOrNull()?.let { route ->
                // No explicit publish: the repository's 4 Hz ticker picks up the new route.
                receiverRepository.setRoute(state.icao, route)
            }
            routeLookupInFlight.remove(state.icao)
        }
    }

    private fun maybeEnrichMeta(state: AircraftState) {
        if (!currentConfig.networkEnrichmentAllowed) return
        if (!metaLookupInFlight.add(state.icao)) return
        serviceScope.launch(Dispatchers.IO) {
            runCatching { aircraftMetaEnrichment.lookup(state.icao) }.getOrNull()?.let { meta ->
                val typeDisplay = meta.typeDisplay()
                receiverRepository.setAircraftMeta(state.icao, meta.registration, meta.owner, typeDisplay)
            }
            metaLookupInFlight.remove(state.icao)
        }
    }

    private fun maybeEnrichFa(state: AircraftState) {
        if (!currentConfig.networkEnrichmentAllowed) return
        // Bare ICAO as a last resort — mostly relevant for non-US aircraft, since a
        // US aircraft already has its registration from the offline algorithm
        // (Registration.fromIcao) with no network round trip needed. FlightAware
        // usually has no data indexed under a raw Mode S hex, but attempting it
        // costs nothing extra: FlightAwareEnrichment's own caching/throttling
        // (4s initial delay, 30s retry) already governs how often this is tried,
        // same as any other ident that keeps failing.
        val ident = state.callsign?.trim()?.takeIf { it.isNotEmpty() }
            ?: state.registration?.trim()?.takeIf { it.isNotEmpty() }
            ?: state.icao
        flightAwareEnrichment.maybeSchedule(state.icao, ident) { fa ->
            serviceScope.launch {
                val route = when {
                    fa.origin.isNotEmpty() && fa.destination.isNotEmpty() -> "${fa.origin} → ${fa.destination}"
                    fa.origin.isNotEmpty() -> "${fa.origin} →"
                    fa.destination.isNotEmpty() -> "→ ${fa.destination}"
                    else -> null
                }
                val typeDisplay = when {
                    fa.manufacturer.isNotEmpty() && fa.model.isNotEmpty() -> "${fa.manufacturer} ${fa.model}"
                    fa.model.isNotEmpty() -> fa.model
                    fa.typeCode.isNotEmpty() -> fa.typeCode
                    else -> null
                }
                val airlineName = fa.airlineName.takeIf { it.isNotEmpty() }
                receiverRepository.setFaResult(state.icao, route, airlineName, typeDisplay)
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val channelId = "adsb_pipeline"
        val channel = NotificationChannel(channelId, "ADS-B Pipeline", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        return Notification.Builder(this, channelId)
            .setContentTitle("ADS-B Receiver")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        lastNotificationText = text
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    /**
     * Separate channel/id from the ongoing foreground notification above — that one is
     * IMPORTANCE_LOW and non-dismissible by design; milestones need to actually alert.
     */
    private fun postMilestoneNotification(title: String, text: String) {
        val channelId = "adsb_milestones"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channelId, "Aircraft milestones", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, com.laviavi.adsbandroid.ui.MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID_MILESTONE,
            Notification.Builder(this, channelId)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .build(),
        )
    }

    companion object {
        /** Refresh cadence for the Receiver coverage card, independent of the CSV row. */
        private const val COVERAGE_UI_INTERVAL_MS = 10_000L
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_ID_MILESTONE = 2
        const val NO_DONGLE_MESSAGE = "no dongle found or critical error - check and reconnect the dongle to continue"

        /** LIBUSB_ERROR_BUSY: the driver app is holding the dongle and only it can let go. */
        const val DRIVER_BUSY_MESSAGE =
            "the RTL-SDR driver app is still holding the dongle from a previous session - " +
            "force-stop the driver app, or unplug and replug the dongle, then reconnect"
    }
}

sealed class SourceState {
    object Idle : SourceState()
    object Connecting : SourceState()
    data class Running(val sourceName: String) : SourceState()
    object DriverNotInstalled : SourceState()
    data class Error(val message: String) : SourceState()
}
