package com.laviavi.adsbandroid.ui.receiver

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.laviavi.adsbandroid.aircraft.AircraftManager
import com.laviavi.adsbandroid.capture.GainOptions
import com.laviavi.adsbandroid.capture.RtlTcpGain
import com.laviavi.adsbandroid.data.BestRangeRecordEntity
import com.laviavi.adsbandroid.observability.AltitudeBand
import com.laviavi.adsbandroid.observability.CompassSector
import com.laviavi.adsbandroid.observability.CoverageMetricsRow
import com.laviavi.adsbandroid.pipeline.AppConfig
import com.laviavi.adsbandroid.pipeline.PipelineStats
import com.laviavi.adsbandroid.pipeline.SourceState
import com.laviavi.adsbandroid.ui.MainViewModel
import com.laviavi.adsbandroid.ui.components.ReconnectConfirmDialog
import com.laviavi.adsbandroid.ui.components.StopConfirmDialog
import com.laviavi.adsbandroid.ui.model.CoverageMode
import com.laviavi.adsbandroid.ui.model.CoverageWindow
import com.laviavi.adsbandroid.ui.theme.AdsbColors
import com.laviavi.adsbandroid.ui.theme.AdsbDimens
import com.laviavi.adsbandroid.units.DistanceUnit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Statute miles per nautical mile — `CoverageMetrics` reports its ranges in miles. */
private const val NM_TO_MI = 1.15078

/**
 * Receiver dashboard: "is the receiver working, and if not, what should I change?"
 *
 * The demod knobs live here rather than only in Settings because these are the
 * controls reached for *while watching the counters* — the accept rate directly
 * below each slider is the whole point, and it restarts from zero on every change
 * so the reading is never carried over from the previous tuning.
 */
@Composable
fun ReceiverScreen(
    viewModel: MainViewModel,
    onConfigChange: (AppConfig) -> Unit,
    onStart: () -> Unit,
    onReconnect: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sourceState by viewModel.sourceState.collectAsStateWithLifecycle()
    val config by viewModel.config.collectAsStateWithLifecycle()
    val gainOptions by viewModel.gainOptions.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val droppedBatches by viewModel.droppedBatches.collectAsStateWithLifecycle()
    val coverage by viewModel.coverage.collectAsStateWithLifecycle()
    val allTimeCoverage by viewModel.allTimeCoverage.collectAsStateWithLifecycle()
    val bestRangeEver by viewModel.bestRangeEver.collectAsStateWithLifecycle()
    val coverageMode by viewModel.coverageMode.collectAsStateWithLifecycle()
    val coverageWindow by viewModel.coverageWindow.collectAsStateWithLifecycle()
    val aircraftCount by viewModel.aircraftRows.collectAsStateWithLifecycle()
    val delta by viewModel.tableDelta.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AdsbColors.Background),
    ) {
        ReceiverAppBar(
            isRunning = sourceState is SourceState.Running,
            onStart = onStart,
            onReconnect = onReconnect,
            onStop = onStop,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AdsbDimens.ScreenGutter)
                .padding(bottom = AdsbDimens.SpacingXxl),
            verticalArrangement = Arrangement.spacedBy(AdsbDimens.SpacingMd),
        ) {
            StatusCard(sourceState, gainOptions, config)
            DemodTuningCard(config, stats, onConfigChange)
            PipelineCard(stats, droppedBatches, aircraftCount.size, delta)
            CoverageCard(
                liveRow = coverage,
                allTimeRow = allTimeCoverage,
                window = coverageWindow,
                onWindowChange = viewModel::setCoverageWindow,
                mode = coverageMode,
                unit = config.distanceUnit,
                onModeChange = viewModel::setCoverageMode,
                bestRange = bestRangeEver,
            )
        }
    }
}

@Composable
private fun ReceiverAppBar(
    isRunning: Boolean,
    onStart: () -> Unit,
    onReconnect: () -> Unit,
    onStop: () -> Unit,
) {
    var confirmStop by remember { mutableStateOf(false) }
    var confirmReconnect by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = AdsbDimens.ScreenGutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Receiver", fontSize = 17.sp, fontWeight = FontWeight.W600, color = AdsbColors.TextPrimary)
        Spacer(Modifier.weight(1f))
        OutlinedButton(
            onClick = { confirmReconnect = true },
            shape = RoundedCornerShape(AdsbDimens.PillCornerRadius),
            border = BorderStroke(1.dp, AdsbColors.Outline),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = AdsbColors.TextPrimary),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.height(36.dp),
        ) {
            Text("RECONNECT", fontSize = 12.sp, fontWeight = FontWeight.W700, letterSpacing = 0.96.sp)
        }
        Spacer(Modifier.width(AdsbDimens.SpacingSm))
        // Same dynamic label/action as Traffic's Start/Stop button — previously
        // hardcoded "STOP" here regardless of state, which called onStop() again
        // (a no-op) when already idle instead of offering any way to start.
        Button(
            onClick = { if (isRunning) confirmStop = true else onStart() },
            shape = RoundedCornerShape(AdsbDimens.PillCornerRadius),
            colors = ButtonDefaults.buttonColors(
                containerColor = AdsbColors.Primary,
                contentColor = AdsbColors.OnPrimary,
            ),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.height(36.dp),
        ) {
            Text(if (isRunning) "STOP" else "START", fontSize = 12.sp, fontWeight = FontWeight.W700, letterSpacing = 0.96.sp)
        }
    }

    if (confirmStop) {
        StopConfirmDialog(onConfirm = onStop, onDismiss = { confirmStop = false })
    }
    if (confirmReconnect) {
        ReconnectConfirmDialog(onConfirm = onReconnect, onDismiss = { confirmReconnect = false })
    }
}

@Composable
private fun Card(header: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AdsbDimens.CardCornerRadius))
            .background(AdsbColors.Surface)
            .border(1.dp, AdsbColors.SurfaceElevated, RoundedCornerShape(AdsbDimens.CardCornerRadius))
            .padding(AdsbDimens.CardPadding),
        verticalArrangement = Arrangement.spacedBy(AdsbDimens.SpacingSm),
    ) {
        SectionHeader(header)
        content()
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        fontWeight = FontWeight.W600,
        letterSpacing = 1.4.sp,
        color = AdsbColors.Primary,
    )
}

// --- Status ------------------------------------------------------------------

/**
 * Uptime is deliberately absent: the status strip owns it, and the spec states it
 * appears there and nowhere else.
 */
@Composable
private fun StatusCard(
    sourceState: SourceState,
    gainOptions: GainOptions,
    config: AppConfig,
) {
    val (glyph, label, colour) = when (sourceState) {
        is SourceState.Running -> Triple("●", "RUNNING", AdsbColors.Success)
        is SourceState.Connecting -> Triple("◐", "STARTING", AdsbColors.Primary)
        is SourceState.DriverNotInstalled -> Triple("○", "NO SDR", AdsbColors.TextDisabled)
        is SourceState.Error -> Triple("✕", "ERROR", AdsbColors.Error)
        SourceState.Idle -> Triple("○", "STOPPED", AdsbColors.TextDisabled)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AdsbDimens.CardCornerRadius))
            .background(AdsbColors.Surface)
            .border(1.dp, AdsbColors.SurfaceElevated, RoundedCornerShape(AdsbDimens.CardCornerRadius))
            .padding(AdsbDimens.CardPadding),
        verticalArrangement = Arrangement.spacedBy(AdsbDimens.SpacingSm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(glyph, fontSize = 15.sp, color = colour)
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.W600, color = colour)
            if (sourceState is SourceState.Error) {
                Text(
                    sourceState.message,
                    fontSize = 12.sp,
                    color = AdsbColors.TextSecondary,
                    maxLines = 2,
                )
            }
        }

        val tuner = (gainOptions as? GainOptions.Available)
        val left = listOf(
            tuner?.let { "${it.tuner.displayName} · ${it.gainsTenths.size} steps" } ?: "No tuner",
            "Gain " + if (config.autoGain) "auto" else
                config.gainTenths.takeIf { it != AppConfig.GAIN_UNSET }
                    ?.let { "${RtlTcpGain.formatGain(it)} manual" } ?: "unset",
            "PPM ${if (config.ppmCorrection >= 0) "+" else ""}${config.ppmCorrection}",
        )
        val right = listOf(
            "2.0 Msps",
            "1090.000 MHz",
            "Bias-tee ${if (config.biasTee) "on" else "off"}",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(AdsbDimens.SpacingMd)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                left.forEach { MonoLine(it) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                right.forEach { MonoLine(it) }
            }
        }
    }
}

@Composable
private fun MonoLine(text: String) {
    Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = AdsbColors.TextSecondary)
}

// --- Demod tuning ------------------------------------------------------------

@Composable
private fun DemodTuningCard(
    config: AppConfig,
    stats: PipelineStats.Snapshot,
    onChange: (AppConfig) -> Unit,
) {
    Card("DEMOD TUNING — LIVE") {
        TuningSlider(
            label = "Gap divisor",
            value = config.preambleGapDivisor.toFloat(),
            range = AppConfig.GAP_DIVISOR_MIN.toFloat()..AppConfig.GAP_DIVISOR_MAX.toFloat(),
            steps = AppConfig.GAP_DIVISOR_MAX - AppConfig.GAP_DIVISOR_MIN - 1,
            display = config.preambleGapDivisor.toString(),
            onChange = { onChange(config.copy(preambleGapDivisor = it.roundToInt())) },
        )
        TuningSlider(
            label = "Delta floor",
            value = config.deltaFloor.toFloat(),
            range = AppConfig.DELTA_FLOOR_MIN.toFloat()..AppConfig.DELTA_FLOOR_MAX.toFloat(),
            steps = (AppConfig.DELTA_FLOOR_MAX - AppConfig.DELTA_FLOOR_MIN) / AppConfig.DELTA_FLOOR_STEP - 1,
            display = config.deltaFloor.toString(),
            onChange = {
                val snapped = (it / AppConfig.DELTA_FLOOR_STEP).roundToInt() * AppConfig.DELTA_FLOOR_STEP
                onChange(config.copy(deltaFloor = snapped.coerceIn(AppConfig.DELTA_FLOOR_MIN, AppConfig.DELTA_FLOOR_MAX)))
            },
        )
        TuningSlider(
            label = "Window",
            value = config.acceptRateWindowSeconds.toFloat(),
            range = AppConfig.ACCEPT_WINDOW_MIN.toFloat()..AppConfig.ACCEPT_WINDOW_MAX.toFloat(),
            steps = (AppConfig.ACCEPT_WINDOW_MAX - AppConfig.ACCEPT_WINDOW_MIN) / AppConfig.ACCEPT_WINDOW_STEP - 1,
            display = "${config.acceptRateWindowSeconds} s",
            onChange = {
                val snapped = (it / AppConfig.ACCEPT_WINDOW_STEP).roundToInt() * AppConfig.ACCEPT_WINDOW_STEP
                onChange(config.copy(acceptRateWindowSeconds = snapped.coerceIn(AppConfig.ACCEPT_WINDOW_MIN, AppConfig.ACCEPT_WINDOW_MAX)))
            },
        )

        HorizontalDivider(color = AdsbColors.SurfaceElevated, modifier = Modifier.padding(top = 4.dp))

        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(AdsbDimens.SpacingMd)) {
            Text(
                "%.1f%%".format(stats.windowAcceptRatePercent),
                fontFamily = FontFamily.Monospace,
                fontSize = 28.sp,
                fontWeight = FontWeight.W600,
                color = acceptRateColour(stats),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    "${stats.windowTested} tested",
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = AdsbColors.TextSecondary,
                )
                Text(
                    "${stats.windowAccepted} accepted · ${stats.windowRejected} rejected",
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = AdsbColors.TextSecondary,
                )
            }
        }
        TextButton(
            onClick = {
                onChange(
                    config.copy(
                        preambleGapDivisor = AppConfig.DEFAULT_PREAMBLE_GAP_DIVISOR,
                        deltaFloor = AppConfig.DEFAULT_DELTA_FLOOR,
                    )
                )
            },
            contentPadding = PaddingValues(0.dp),
        ) {
            Text("Reset to defaults", fontSize = 12.sp, color = AdsbColors.Primary)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TuningSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    display: String,
    onChange: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            fontSize = 12.sp,
            color = AdsbColors.TextSecondary,
            modifier = Modifier.width(104.dp),
        )
        val sliderColors = SliderDefaults.colors(
            thumbColor = AdsbColors.Primary,
            activeTrackColor = AdsbColors.Primary,
            inactiveTrackColor = AdsbColors.SurfaceElevated,
            activeTickColor = Color.Transparent,
            inactiveTickColor = Color.Transparent,
        )
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps.coerceAtLeast(0),
            colors = sliderColors,
            modifier = Modifier.weight(1f),
            // M3's default track is tall and carries a stop indicator dot; the
            // spec's control is a plain 4 dp track with a round 18 dp thumb.
            track = { state ->
                SliderDefaults.Track(
                    sliderState = state,
                    colors = sliderColors,
                    drawStopIndicator = null,
                    drawTick = { _, _ -> },
                    thumbTrackGapSize = 0.dp,
                    trackInsideCornerSize = 0.dp,
                    modifier = Modifier.height(4.dp),
                )
            },
            thumb = {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .background(AdsbColors.Primary, androidx.compose.foundation.shape.CircleShape)
                )
            },
        )
        Text(
            display,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            color = AdsbColors.TextPrimary,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.width(52.dp),
        )
    }
}

private fun acceptRateColour(stats: PipelineStats.Snapshot) = when {
    stats.windowTested == 0L -> AdsbColors.TextSecondary
    stats.windowAccepted == 0L -> AdsbColors.Error
    stats.windowAcceptRatePercent < 5.0 -> AdsbColors.Warning
    else -> AdsbColors.Success
}

// --- Pipeline ----------------------------------------------------------------

@Composable
private fun PipelineCard(
    stats: PipelineStats.Snapshot,
    droppedBatches: Long,
    aircraftCount: Int,
    delta: Pair<Int, Int>,
) {
    Card("PIPELINE") {
        PipelineRow("USB read") {
            Text(
                "%.1f MB/s · %.0f buf/s".format(stats.bytesPerSecond / 1_000_000.0, stats.buffersPerSecond),
                fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = AdsbColors.TextPrimary,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${stats.overruns} overrun",
                fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                color = if (stats.overruns > 0) AdsbColors.Warning else AdsbColors.Success,
                maxLines = 1, softWrap = false,
            )
        }
        PipelineRow("Demod") {
            Text(
                "%.0f cand/s".format(stats.candidatesPerSecond),
                fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = AdsbColors.TextPrimary,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "%.0f fr/s".format(stats.messagesPerSecond),
                fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = AdsbColors.TextPrimary,
            )
        }
        // The three-way CRC split the CLI shows and the UI must not collapse:
        // valid counts valid+corrected+recovered, "unresolved" is parity-address
        // alone, and "bad" is strictly CRC-invalid.
        PipelineRow("CRC") {
            Text("${stats.validMessages} val", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = AdsbColors.Success)
            Spacer(Modifier.weight(1f))
            Text("${stats.correctedMessages} cor", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = AdsbColors.Warning)
            Spacer(Modifier.width(8.dp))
            Text("${stats.recoveredMessages} rec", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = AdsbColors.Primary)
        }
        PipelineRow("") {
            Text("${stats.unresolvedMessages} unresolved", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = AdsbColors.TextSecondary)
            Spacer(Modifier.weight(1f))
            Text("${stats.badCrcMessages} bad", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = AdsbColors.Error)
        }
        PipelineRow("State") {
            Text(
                "$aircraftCount aircrafts · +${delta.first} · −${delta.second}",
                fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = AdsbColors.TextPrimary,
                maxLines = 1, softWrap = false,
            )
            Spacer(Modifier.weight(1f))
            // Backpressure drops are always visible, never silent.
            Text(
                "$droppedBatches dropped",
                fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                color = if (droppedBatches > 0) AdsbColors.Warning else AdsbColors.Success,
                maxLines = 1, softWrap = false,
            )
        }

        Row(verticalAlignment = Alignment.Bottom) {
            RateChart(samples = stats.rateHistory, modifier = Modifier.weight(1f))
            Text(
                "60 s",
                fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = AdsbColors.TextDisabled,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

@Composable
private fun PipelineRow(label: String, content: @Composable RowScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = AdsbColors.TextDisabled,
            modifier = Modifier.width(88.dp),
        )
        content()
    }
}

// --- Coverage ----------------------------------------------------------------

private val bestRangeDateFormat = SimpleDateFormat("MM/dd/yyyy HH:mm", Locale.US)

@Composable
private fun CoverageCard(
    liveRow: CoverageMetricsRow?,
    allTimeRow: CoverageMetricsRow?,
    window: CoverageWindow,
    onWindowChange: (CoverageWindow) -> Unit,
    mode: CoverageMode,
    unit: DistanceUnit,
    onModeChange: (CoverageMode) -> Unit,
    bestRange: BestRangeRecordEntity?,
) {
    val row = if (window == CoverageWindow.LIVE) liveRow else allTimeRow

    Card("COVERAGE") {
        WindowToggle(window, onWindowChange)

        if (row == null) {
            Text(
                if (window == CoverageWindow.LIVE)
                    "No positioned aircrafts yet — coverage needs decoded positions."
                else
                    "No coverage history yet — it accumulates every 5 minutes while running.",
                fontSize = 12.sp,
                color = AdsbColors.TextSecondary,
                modifier = Modifier.padding(top = 8.dp),
            )
            return@Card
        }

        Row(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(AdsbDimens.SpacingSm),
        ) {
            CoveragePolar(
                row = row,
                mode = mode,
                unitLabel = if (mode == CoverageMode.RANGE) unit.label else "ac",
                valueForSector = { sector ->
                    val s = row.sectors[sector]
                    when {
                        s == null -> 0.0
                        mode == CoverageMode.RANGE -> unit.fromNm(s.maxMi / NM_TO_MI)
                        else -> s.count.toDouble()
                    }
                },
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "${row.symmetryScore}",
                        fontFamily = FontFamily.Monospace, fontSize = 22.sp, fontWeight = FontWeight.W600,
                        color = AdsbColors.TextPrimary,
                    )
                    Text(
                        "/100",
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = AdsbColors.TextDisabled,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
                Text("symmetry score", fontSize = 12.sp, color = AdsbColors.TextSecondary)

                ModeToggle(mode, onModeChange)

                Text(
                    if (mode == CoverageMode.RANGE)
                        "Furthest decode per sector."
                    else
                        "Aircrafts seen per sector.",
                    fontSize = 11.sp,
                    color = AdsbColors.TextDisabled,
                )

                row.bestSector?.let { best ->
                    val v = row.sectors[best]?.maxMi ?: 0.0
                    Text(
                        "best ${best.name} ${unit.formatWhole(v / NM_TO_MI)}",
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = AdsbColors.Success,
                    )
                }
                row.worstSector?.let { worst ->
                    val v = row.sectors[worst]?.maxMi ?: 0.0
                    Text(
                        "worst ${worst.name} ${unit.formatWhole(v / NM_TO_MI)}",
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = AdsbColors.Warning,
                    )
                }

                bestRange?.let {
                    Text(
                        "best ever ${unit.formatWhole(it.distanceNm)} · ${it.callsign ?: it.icao} · " +
                            bestRangeDateFormat.format(Date(it.timestampMs)),
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = AdsbColors.Primary,
                    )
                }

                AltitudeHistogram(row)
            }
        }
    }
}

@Composable
private fun ModeToggle(mode: CoverageMode, onChange: (CoverageMode) -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(AdsbDimens.PillCornerRadius))
            .border(1.dp, AdsbColors.Outline, RoundedCornerShape(AdsbDimens.PillCornerRadius)),
    ) {
        CoverageMode.entries.forEach { m ->
            val selected = m == mode
            Text(
                m.label,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = if (selected) FontWeight.W700 else FontWeight.W400,
                color = if (selected) AdsbColors.OnPrimary else AdsbColors.TextSecondary,
                modifier = Modifier
                    .background(if (selected) AdsbColors.Primary else Color.Transparent)
                    .clickable { onChange(m) }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun WindowToggle(window: CoverageWindow, onChange: (CoverageWindow) -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(AdsbDimens.PillCornerRadius))
            .border(1.dp, AdsbColors.Outline, RoundedCornerShape(AdsbDimens.PillCornerRadius)),
    ) {
        CoverageWindow.entries.forEach { w ->
            val selected = w == window
            Text(
                w.label,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = if (selected) FontWeight.W700 else FontWeight.W400,
                color = if (selected) AdsbColors.OnPrimary else AdsbColors.TextSecondary,
                modifier = Modifier
                    .background(if (selected) AdsbColors.Primary else Color.Transparent)
                    .clickable { onChange(w) }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun AltitudeHistogram(row: CoverageMetricsRow) {
    val counts = AltitudeBand.entries.map { row.altitudeCounts[it] ?: 0 }
    val peak = (counts.maxOrNull() ?: 0).coerceAtLeast(1)

    Column(modifier = Modifier.padding(top = 4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            counts.forEach { c ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(fraction = (c.toFloat() / peak).coerceAtLeast(0.08f))
                        .background(
                            AdsbColors.Primary.copy(alpha = 0.35f + 0.65f * (c.toFloat() / peak)),
                            RoundedCornerShape(2.dp),
                        ),
                )
            }
        }
        Text(
            "<3k · 3–10k · 10–30k · >30k ft",
            fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = AdsbColors.TextDisabled,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
