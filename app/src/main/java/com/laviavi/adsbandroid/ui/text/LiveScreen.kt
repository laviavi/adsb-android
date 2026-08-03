package com.laviavi.adsbandroid.ui.text

import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.laviavi.adsbandroid.aircraft.AircraftSortOrder
import com.laviavi.adsbandroid.capture.UsbHotplugReceiver
import com.laviavi.adsbandroid.pipeline.AppConfig
import com.laviavi.adsbandroid.pipeline.SourceState
import com.laviavi.adsbandroid.ui.MainViewModel
import com.laviavi.adsbandroid.ui.components.*
import com.laviavi.adsbandroid.ui.model.*
import com.laviavi.adsbandroid.ui.theme.AdsbColors
import com.laviavi.adsbandroid.ui.theme.AdsbDimens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScreen(
    viewModel: MainViewModel,
    onAircraftClick: (String) -> Unit,
    onNavigateToReceiver: () -> Unit,
    onShowOnMap: (String) -> Unit,
    onConfigChange: (AppConfig) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onReconnect: () -> Unit,
    onResetCounters: () -> Unit,
) {
    val rows by viewModel.aircraftRows.collectAsStateWithLifecycle()
    val trackedCount by viewModel.trackedCount.collectAsStateWithLifecycle()
    val metrics by viewModel.liveMetrics.collectAsStateWithLifecycle()
    val sourceState by viewModel.sourceState.collectAsStateWithLifecycle()
    val receiverStatus by viewModel.receiverStatus.collectAsStateWithLifecycle()
    val metricsCollapsed by viewModel.metricsCollapsed.collectAsStateWithLifecycle()
    val filters by viewModel.liveFilters.collectAsStateWithLifecycle()
    val config by viewModel.config.collectAsStateWithLifecycle()

    var showOverflowMenu by remember { mutableStateOf(false) }
    var showStopConfirm by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(AdsbColors.Background)) {
        // App bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = AdsbDimens.ScreenGutter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Tuner chip
            Surface(
                modifier = Modifier.clickable(onClick = onNavigateToReceiver),
                color = AdsbColors.SurfaceElevated,
                shape = RoundedCornerShape(AdsbDimens.PillCornerRadius),
                border = androidx.compose.foundation.BorderStroke(1.dp, AdsbColors.Outline),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(Icons.Outlined.Usb, contentDescription = null, modifier = Modifier.size(14.dp), tint = AdsbColors.Primary)
                    Text(
                        text = receiverStatus.sourceName ?: "NO SDR",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = AdsbColors.Primary,
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            Text("Live", fontSize = 17.sp, fontWeight = FontWeight.W600, color = AdsbColors.TextPrimary)

            Spacer(Modifier.weight(1f))

            // Start/Stop button
            val isRunning = sourceState is SourceState.Running
            Button(
                onClick = { if (isRunning) showStopConfirm = true else onStart() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = AdsbColors.Primary,
                    contentColor = AdsbColors.OnPrimary,
                ),
                shape = RoundedCornerShape(AdsbDimens.PillCornerRadius),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                modifier = Modifier.height(32.dp),
            ) {
                Text(
                    text = if (isRunning) "STOP" else "START",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.W700,
                    letterSpacing = 0.96.sp,
                )
            }

            // Overflow
            Box {
                IconButton(onClick = { showOverflowMenu = true }) {
                    Icon(Icons.Default.MoreVert, "More options", tint = AdsbColors.TextSecondary)
                }
                DropdownMenu(expanded = showOverflowMenu, onDismissRequest = { showOverflowMenu = false }) {
                    DropdownMenuItem(text = { Text("Reconnect source") }, onClick = { showOverflowMenu = false; onReconnect() })
                    DropdownMenuItem(
                        text = { Text("Reset counters") },
                        onClick = { showOverflowMenu = false; onResetCounters() },
                    )
                }
            }
        }

        // Metrics header. The chevron sits outside the collapsing region so it is
        // still reachable once collapsed — a toggle that hides itself is a trap.
        if (sourceState is SourceState.Running) {
            Column {
                AnimatedVisibility(visible = !metricsCollapsed) {
                    Column(modifier = Modifier.padding(horizontal = AdsbDimens.ScreenGutter, vertical = AdsbDimens.SpacingSm)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(AdsbDimens.SpacingSm), modifier = Modifier.fillMaxWidth()) {
                            MetricTile(
                                value = metrics.framesPerSecond, label = "frames/s",
                                modifier = Modifier.weight(1f).clickable(onClick = onNavigateToReceiver),
                            )
                            MetricTile(
                                value = "${metrics.validPercent}%", label = "valid",
                                modifier = Modifier.weight(1f).clickable(onClick = onNavigateToReceiver),
                                valueColor = AdsbColors.Success,
                            )
                            MetricTile(
                                value = metrics.maxRangeMi, label = "max range mi",
                                modifier = Modifier.weight(1f).clickable(onClick = onNavigateToReceiver),
                            )
                        }
                        Sparkline(data = metrics.sparklineData, modifier = Modifier.padding(top = AdsbDimens.SpacingSm))
                        Text(
                            text = "frames/s · 60 s",
                            fontSize = 10.sp,
                            color = AdsbColors.TextDisabled,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                MetricsChevron(
                    collapsed = metricsCollapsed,
                    summary = "${metrics.framesPerSecond} frames/s · ${metrics.validPercent}% valid",
                    onToggle = viewModel::toggleMetricsCollapsed,
                )
            }
        }

        FilterChipRow(
            filters = filters,
            // Distance only exists relative to an observer, and (0,0) is the
            // "never configured" sentinel rather than a location anyone receives from.
            observerKnown = config.observerLatitude != 0.0 || config.observerLongitude != 0.0,
            onChange = viewModel::updateLiveFilters,
        )

        // Sort header bar
        if (rows.isNotEmpty() || sourceState is SourceState.Running) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 30.dp)
                    .background(AdsbColors.ListHeaderBg)
                    .padding(horizontal = AdsbDimens.ScreenGutter),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = when {
                        sourceState !is SourceState.Running -> "STOPPED"
                        // Both numbers, so a chip can never be mistaken for a drop in traffic.
                        filters.isActive -> "${rows.size} OF $trackedCount AIRCRAFTS"
                        else -> "$trackedCount AIRCRAFTS"
                    },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    letterSpacing = 1.sp,
                    color = AdsbColors.TextDisabled,
                )
                Spacer(Modifier.weight(1f))
                SortDropdown(
                    current = config.sortOrder,
                    onPick = { onConfigChange(config.copy(sortOrder = it)) },
                )
            }
        }

        // Aircraft list or non-nominal state
        if (rows.isEmpty()) {
            if (filters.isActive && trackedCount > 0) {
                StatusPanel(
                    icon = { Text("⌗", fontSize = 40.sp, color = AdsbColors.TextDisabled) },
                    headline = "No aircrafts match these filters",
                    body = "$trackedCount tracked, none matching. Clear the filters to see them.",
                    action = {
                        OutlinedButton(onClick = { viewModel.updateLiveFilters { LiveFilters() } }) {
                            Text("Clear filters")
                        }
                    },
                )
            } else {
                NonNominalState(sourceState, onStart, onNavigateToReceiver)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(rows, key = { it.icao }) { row ->
                    AircraftRowWithMenu(
                        row = row,
                        onClick = { onAircraftClick(row.icao) },
                        onShowOnMap = { onShowOnMap(row.icao) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }

    // Stop confirmation
    if (showStopConfirm) {
        AlertDialog(
            onDismissRequest = { showStopConfirm = false },
            title = { Text("Stop receiving?") },
            text = { Text("The session and its counters end.") },
            confirmButton = {
                TextButton(onClick = { showStopConfirm = false; onStop() }) { Text("Stop") }
            },
            dismissButton = {
                TextButton(onClick = { showStopConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

/** Collapse control for the metrics header; carries the numbers when collapsed. */
@Composable
private fun MetricsChevron(collapsed: Boolean, summary: String, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = AdsbDimens.ScreenGutter, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (collapsed) {
            Text(
                summary,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = AdsbColors.TextSecondary,
            )
        }
        Spacer(Modifier.weight(1f))
        Icon(
            imageVector = if (collapsed) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
            contentDescription = if (collapsed) "Expand metrics" else "Collapse metrics",
            tint = AdsbColors.TextDisabled,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * Filter chips. Chips AND together; the row scrolls horizontally rather than
 * wrapping, so the list below never shifts down as chips are added.
 */
@Composable
private fun FilterChipRow(
    filters: LiveFilters,
    observerKnown: Boolean,
    onChange: ((LiveFilters) -> LiveFilters) -> Unit,
) {
    var bandMenuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = AdsbDimens.ScreenGutter, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilterPill("Airborne", filters.airborne) { onChange { it.copy(airborne = !it.airborne) } }
        FilterPill("On ground", filters.onGround) { onChange { it.copy(onGround = !it.onGround) } }
        FilterPill("Position", filters.withPosition) { onChange { it.copy(withPosition = !it.withPosition) } }
        FilterPill("Emergency", filters.emergency) { onChange { it.copy(emergency = !it.emergency) } }
        FilterPill("< 50 mi", filters.within50Mi, enabled = observerKnown) {
            onChange { it.copy(within50Mi = !it.within50Mi) }
        }
        Box {
            FilterPill(filters.altitudeBand.label, filters.altitudeBand != AltitudeBand.ANY) {
                bandMenuOpen = true
            }
            DropdownMenu(expanded = bandMenuOpen, onDismissRequest = { bandMenuOpen = false }) {
                AltitudeBand.entries.forEach { band ->
                    DropdownMenuItem(
                        text = { Text(if (band == AltitudeBand.ANY) "Any altitude" else band.label) },
                        onClick = { bandMenuOpen = false; onChange { it.copy(altitudeBand = band) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterPill(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val textColor = when {
        !enabled -> AdsbColors.TextDisabled
        selected -> AdsbColors.OnPrimary
        else -> AdsbColors.TextSecondary
    }
    val isSelected = selected
    Surface(
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.5f)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { this.selected = isSelected },
        color = if (selected) AdsbColors.Primary else Color.Transparent,
        shape = RoundedCornerShape(AdsbDimens.PillCornerRadius),
        border = if (selected) null else androidx.compose.foundation.BorderStroke(1.dp, AdsbColors.Outline),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.W600 else FontWeight.Normal,
            color = textColor,
            maxLines = 1,
        )
    }
}

/** `SORT BY:` plus a menu. The sort itself is applied in the repository. */
@Composable
private fun SortDropdown(current: AircraftSortOrder, onPick: (AircraftSortOrder) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier.clickable { open = true }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                // An order set outside this menu (the config default is FIRST_SEEN)
                // still needs a readable label, not a raw enum name.
                text = "SORT BY: ${SORT_CHOICES[current] ?: current.label.uppercase()}",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                letterSpacing = 1.sp,
                color = AdsbColors.TextDisabled,
            )
            Icon(
                Icons.Default.ArrowDropDown, contentDescription = "Change sort",
                tint = AdsbColors.TextDisabled, modifier = Modifier.size(16.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SORT_CHOICES.forEach { (order, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = { open = false; onPick(order) },
                )
            }
        }
    }
}

/** The three orders the spec offers on Live, in its wording. */
private val SORT_CHOICES = linkedMapOf(
    AircraftSortOrder.NEAREST to "DISTANCE",
    AircraftSortOrder.ALTITUDE to "ALTITUDE",
    AircraftSortOrder.LAST_SEEN to "MSG AGE",
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AircraftRowWithMenu(
    row: AircraftRowUi,
    onClick: () -> Unit,
    onShowOnMap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    Box(modifier = modifier) {
        AircraftRow(
            row = row,
            onClick = onClick,
            modifier = Modifier.combinedClickable(
                onClick = onClick,
                onLongClick = { menuOpen = true },
            ),
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Show on map") },
                onClick = { menuOpen = false; onShowOnMap() },
            )
            DropdownMenuItem(
                text = { Text("Copy ICAO") },
                onClick = { menuOpen = false; clipboard.setText(AnnotatedString(row.icao)) },
            )
        }
    }
}

@Composable
private fun NonNominalState(
    sourceState: SourceState,
    onStart: () -> Unit,
    onNavigateToReceiver: () -> Unit,
) {
    when (sourceState) {
        is SourceState.Idle -> StatusPanel(
            icon = { Text("○", fontSize = 40.sp, color = AdsbColors.TextDisabled) },
            headline = "Receiver stopped",
            body = "Tap Start to begin receiving ADS-B signals from your SDR dongle.",
            action = {
                Button(onClick = onStart, colors = ButtonDefaults.buttonColors(containerColor = AdsbColors.Primary)) {
                    Text("Start")
                }
            },
        )
        is SourceState.Connecting -> StatusPanel(
            icon = { Text("◐", fontSize = 40.sp, color = AdsbColors.Primary) },
            headline = "Listening…",
            body = "Connecting to the SDR dongle and waiting for ADS-B frames.",
        )
        is SourceState.DriverNotInstalled -> {
            val context = LocalContext.current
            StatusPanel(
                icon = { Text("○", fontSize = 44.sp, color = AdsbColors.TextDisabled) },
                headline = "RTL-SDR driver app not installed",
                body = "Install the RTL-SDR driver app from the Play Store to use your dongle.",
                action = {
                    OutlinedButton(onClick = { UsbHotplugReceiver.openDriverInstallPage(context) }) { Text("Install") }
                },
            )
        }
        is SourceState.Error -> StatusPanel(
            icon = { Text("✕", fontSize = 40.sp, color = AdsbColors.Error) },
            headline = "Receiver error",
            body = sourceState.message,
            action = {
                Row(horizontalArrangement = Arrangement.spacedBy(AdsbDimens.SpacingSm)) {
                    Button(onClick = onStart, colors = ButtonDefaults.buttonColors(containerColor = AdsbColors.Primary)) {
                        Text("Reconnect")
                    }
                    OutlinedButton(onClick = onNavigateToReceiver) { Text("Open Receiver") }
                }
            },
        )
        is SourceState.Running -> StatusPanel(
            icon = { Text("●", fontSize = 40.sp, color = AdsbColors.Success) },
            headline = "Receiving frames, no valid decodes",
            body = "Frames are being found but none contain aircraft data yet. This usually means the receiver just started.",
            action = {
                Button(onClick = onNavigateToReceiver, colors = ButtonDefaults.buttonColors(containerColor = AdsbColors.Primary)) {
                    Text("Open Receiver")
                }
            },
        )
    }
}
