package com.laviavi.adsbandroid.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laviavi.adsbandroid.aircraft.AircraftState
import com.laviavi.adsbandroid.capture.GainOptions
import com.laviavi.adsbandroid.observability.CoverageMetricsRow
import com.laviavi.adsbandroid.data.AircraftSeenEntity
import com.laviavi.adsbandroid.pipeline.AppConfig
import com.laviavi.adsbandroid.pipeline.PipelineStats
import com.laviavi.adsbandroid.pipeline.SourceState
import com.laviavi.adsbandroid.ui.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor() : ViewModel() {

    // --- Raw state from service ---

    private val _aircraft = MutableStateFlow<List<AircraftState>>(emptyList())
    val aircraft: StateFlow<List<AircraftState>> = _aircraft.asStateFlow()

    private val _history = MutableStateFlow<List<AircraftSeenEntity>>(emptyList())
    val history: StateFlow<List<AircraftSeenEntity>> = _history.asStateFlow()

    private val _stats = MutableStateFlow(PipelineStats.Snapshot())
    val stats: StateFlow<PipelineStats.Snapshot> = _stats.asStateFlow()

    private val _serviceConnected = MutableStateFlow(false)
    val serviceConnected: StateFlow<Boolean> = _serviceConnected.asStateFlow()

    private val _sourceState = MutableStateFlow<SourceState>(SourceState.Idle)
    val sourceState: StateFlow<SourceState> = _sourceState.asStateFlow()

    private val _config = MutableStateFlow(AppConfig())
    val config: StateFlow<AppConfig> = _config.asStateFlow()

    private val _gainOptions = MutableStateFlow<GainOptions>(
        GainOptions.Unavailable("Connect a USB dongle to read its supported gain levels.")
    )
    val gainOptions: StateFlow<GainOptions> = _gainOptions.asStateFlow()

    private val _droppedBatches = MutableStateFlow(0L)
    val droppedBatches: StateFlow<Long> = _droppedBatches.asStateFlow()

    private val _coverage = MutableStateFlow<CoverageMetricsRow?>(null)
    val coverage: StateFlow<CoverageMetricsRow?> = _coverage.asStateFlow()
    fun onCoverage(row: CoverageMetricsRow?) { _coverage.value = row }

    /** Which metric the coverage polar plots. Presentation-only, not persisted. */
    private val _coverageMode = MutableStateFlow(CoverageMode.RANGE)
    val coverageMode: StateFlow<CoverageMode> = _coverageMode.asStateFlow()
    fun setCoverageMode(mode: CoverageMode) { _coverageMode.value = mode }

    /**
     * Aircraft added and removed on the most recent publish — the `+3 · −1` on the
     * Receiver pipeline card. Computed here rather than in the repository because
     * it describes what the *display* just changed by.
     */
    private val _tableDelta = MutableStateFlow(0 to 0)
    val tableDelta: StateFlow<Pair<Int, Int>> = _tableDelta.asStateFlow()
    private var previousIcaos: Set<String> = emptySet()

    // --- Derived UI models ---

    // Declared ahead of `aircraftRows` because that flow reads it during
    // construction; a later declaration would be null at that point.
    private val _liveFilters = MutableStateFlow(LiveFilters())
    val liveFilters: StateFlow<LiveFilters> = _liveFilters.asStateFlow()

    fun updateLiveFilters(transform: (LiveFilters) -> LiveFilters) {
        _liveFilters.value = transform(_liveFilters.value)
    }

    private val _sparklineBuffer = ArrayDeque<Float>(60)

    /**
     * Filtering happens before mapping, so a chip that hides most of the list also
     * saves the formatting work for those rows — the mapper is the expensive half.
     * The map is deliberately not filtered: the chips belong to the Live list.
     */
    val aircraftRows: StateFlow<List<AircraftRowUi>> =
        combine(_aircraft, _config, _liveFilters) { list, cfg, filters ->
            val now = System.currentTimeMillis()
            list.asSequence()
                .filter { LiveFilters.matches(it, filters) }
                .map { UiMapper.mapRow(it, now, cfg.distanceUnit) }
                .toList()
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Tracked aircraft before filtering — the chips must not make the count lie. */
    val trackedCount: StateFlow<Int> = _aircraft
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** 2 Hz per the spec's decoupling requirement — the map never sees the 4 Hz list rate. */
    val mapMarkers: StateFlow<List<MapMarker>> = combine(_aircraft, _config) { list, cfg ->
        val now = System.currentTimeMillis()
        list.mapNotNull { UiMapper.mapMarker(it, now, cfg.distanceUnit, cfg.mapTrailLength) }
    }.flowOn(Dispatchers.Default)
        .sample(500)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val liveMetrics: StateFlow<LiveMetrics> = combine(_aircraft, _stats, _config) { acList, snapshot, cfg ->
        val sparkline = synchronized(_sparklineBuffer) { _sparklineBuffer.toList() }
        UiMapper.mapMetrics(acList, snapshot, sparkline.map { it }, cfg)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LiveMetrics())

    val receiverStatus: StateFlow<ReceiverStatusUi> = combine(_sourceState, _stats) { state, snapshot ->
        UiMapper.mapReceiverStatus(state, snapshot)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ReceiverStatusUi())

    // --- Diagnostics ---

    private val _diagnosticBuffer = DiagnosticEventBuffer()
    private val _diagnosticEvents = MutableStateFlow<List<DiagnosticEvent>>(emptyList())
    val diagnosticEvents: StateFlow<List<DiagnosticEvent>> = _diagnosticEvents.asStateFlow()
    val unreadErrorCount: StateFlow<Int> = _diagnosticEvents.map { events ->
        events.count { it.severity == Severity.ERROR }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun addDiagnosticEvent(category: EventCategory, severity: Severity, message: String, detail: String? = null) {
        _diagnosticBuffer.add(category, severity, message, detail)
        _diagnosticEvents.value = _diagnosticBuffer.snapshot()
    }

    // --- Live filters & sort ---

    private val _selectedDetail = MutableStateFlow<String?>(null)
    val selectedDetail: StateFlow<String?> = _selectedDetail.asStateFlow()
    fun selectAircraft(icao: String?) { _selectedDetail.value = icao }

    val selectedAircraft: StateFlow<AircraftState?> = combine(_aircraft, _selectedDetail) { list, icao ->
        icao?.let { id -> list.find { it.icao == id } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _metricsCollapsed = MutableStateFlow(false)
    val metricsCollapsed: StateFlow<Boolean> = _metricsCollapsed.asStateFlow()
    fun toggleMetricsCollapsed() { _metricsCollapsed.value = !_metricsCollapsed.value }

    // --- Service callbacks ---

    fun onHistoryUpdate(list: List<AircraftSeenEntity>) { _history.value = list }
    fun onDroppedBatches(count: Long) { _droppedBatches.value = count }
    fun onGainOptions(options: GainOptions) { _gainOptions.value = options }
    fun onAircraftUpdate(list: List<AircraftState>) {
        val icaos = list.mapTo(HashSet()) { it.icao }
        _tableDelta.value = icaos.count { it !in previousIcaos } to
            previousIcaos.count { it !in icaos }
        previousIcaos = icaos
        _aircraft.value = list
    }
    fun onStatsUpdate(snapshot: PipelineStats.Snapshot) {
        _stats.value = snapshot
        synchronized(_sparklineBuffer) {
            _sparklineBuffer.addLast(snapshot.messagesPerSecond.toFloat())
            while (_sparklineBuffer.size > 60) _sparklineBuffer.removeFirst()
        }
    }
    fun onServiceConnected(connected: Boolean) { _serviceConnected.value = connected }
    fun onSourceState(state: SourceState) {
        val prev = _sourceState.value
        _sourceState.value = state
        // Emit diagnostic events on state transitions
        when {
            state is SourceState.Running && prev !is SourceState.Running ->
                addDiagnosticEvent(EventCategory.SOURCE, Severity.INFO, "Receiver started", state.sourceName)
            state is SourceState.Error ->
                addDiagnosticEvent(EventCategory.SOURCE, Severity.ERROR, "Receiver error", state.message)
            state is SourceState.Idle && prev is SourceState.Running ->
                addDiagnosticEvent(EventCategory.OPERATIONAL, Severity.INFO, "Receiver stopped")
        }
    }
    fun onConfigUpdate(config: AppConfig) { _config.value = config }
}
