package com.laviavi.adsbandroid.ui.text

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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

/**
 * Top bar (title, Start/Stop, overflow) — the "live start section". Split out
 * of the old monolithic `LiveScreen` so [TrafficScreen] can place the
 * Live/History/Stats tab row underneath it, above the rest of Live's content.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveTopBar(
    sourceState: SourceState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onReconnect: () -> Unit,
    onResetCounters: () -> Unit,
    onExit: () -> Unit,
) {
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showStopConfirm by remember { mutableStateOf(false) }
    var showReconnectConfirm by remember { mutableStateOf(false) }
    var showExitConfirm by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = AdsbDimens.ScreenGutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Dongle identity now lives in the global StatusStrip above this bar
        // (see MainActivity.kt) — showing it twice was the point Avi flagged.
        Text("Live", fontSize = 17.sp, fontWeight = FontWeight.W600, color = AdsbColors.TextPrimary, modifier = Modifier.weight(1f))

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
                DropdownMenuItem(
                    text = { Text("Reconnect source") },
                    onClick = { showOverflowMenu = false; showReconnectConfirm = true },
                )
                DropdownMenuItem(
                    text = { Text("Reset counters") },
                    onClick = { showOverflowMenu = false; onResetCounters() },
                )
                DropdownMenuItem(
                    text = { Text("Exit app") },
                    onClick = { showOverflowMenu = false; showExitConfirm = true },
                )
            }
        }
    }

    if (showStopConfirm) {
        StopConfirmDialog(onConfirm = onStop, onDismiss = { showStopConfirm = false })
    }
    if (showReconnectConfirm) {
        ReconnectConfirmDialog(onConfirm = onReconnect, onDismiss = { showReconnectConfirm = false })
    }
    if (showExitConfirm) {
        ExitConfirmDialog(onConfirm = onExit, onDismiss = { showExitConfirm = false })
    }
}

/**
 * Everything below the top bar: metrics header, filter chips, sort bar, and
 * the aircraft list or non-nominal state. The Live tab's content inside
 * [TrafficScreen]'s pager.
 */
@Composable
fun LiveBody(
    viewModel: MainViewModel,
    onAircraftClick: (String) -> Unit,
    onNavigateToReceiver: () -> Unit,
    onShowOnMap: (String) -> Unit,
    onConfigChange: (AppConfig) -> Unit,
    onStart: () -> Unit,
    sourceState: SourceState,
) {
    val rows by viewModel.aircraftRows.collectAsStateWithLifecycle()
    val trackedCount by viewModel.trackedCount.collectAsStateWithLifecycle()
    val filters by viewModel.liveFilters.collectAsStateWithLifecycle()
    val config by viewModel.config.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().background(AdsbColors.Background)) {
        // Sort header bar — also carries the filter menu, so filtering costs zero
        // space when nothing is filtered instead of a permanent chip row above it.
        if (rows.isNotEmpty() || sourceState is SourceState.Running) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 34.dp)
                    .background(AdsbColors.ListHeaderBg)
                    .padding(horizontal = AdsbDimens.ScreenGutter),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AdsbDimens.SpacingSm),
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
                    modifier = Modifier.weight(1f),
                )
                FilterMenuButton(filters = filters, onChange = viewModel::updateLiveFilters)
                SortDropdown(
                    current = config.sortOrder,
                    onPick = { onConfigChange(config.copy(sortOrder = it)) },
                )
            }
            if (filters.isActive) {
                ActiveFilterChips(filters = filters, onChange = viewModel::updateLiveFilters)
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
}

/**
 * Filter icon for the sort header row — outline and unbadged when nothing is
 * filtered, so filtering costs zero permanent screen space; filled with a
 * count badge once 1+ filters are on. Position/altitude/near-me are no longer
 * exposed here (Avi asked for them gone) but stay on [LiveFilters] itself —
 * only the UI surface for them was removed, not the filtering capability.
 */
@Composable
private fun FilterMenuButton(filters: LiveFilters, onChange: ((LiveFilters) -> LiveFilters) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val activeCount = listOf(filters.airborne, filters.onGround, filters.emergency).count { it }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(28.dp)) {
            Icon(
                Icons.Outlined.FilterAlt,
                contentDescription = if (activeCount > 0) "Filters, $activeCount active" else "Filters",
                tint = if (activeCount > 0) AdsbColors.Primary else AdsbColors.TextDisabled,
                modifier = Modifier.size(16.dp),
            )
        }
        if (activeCount > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(12.dp)
                    .background(AdsbColors.Primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "$activeCount", fontSize = 8.sp, fontWeight = FontWeight.W700,
                    color = AdsbColors.OnPrimary,
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            FilterMenuItem("Airborne", filters.airborne) { onChange { it.copy(airborne = !it.airborne) } }
            FilterMenuItem("On ground", filters.onGround) { onChange { it.copy(onGround = !it.onGround) } }
            FilterMenuItem("Emergency", filters.emergency) { onChange { it.copy(emergency = !it.emergency) } }
        }
    }
}

@Composable
private fun FilterMenuItem(label: String, checked: Boolean, onToggle: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, color = if (checked) AdsbColors.Primary else AdsbColors.TextPrimary) },
        leadingIcon = {
            Icon(
                if (checked) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                contentDescription = null,
                tint = if (checked) AdsbColors.Primary else AdsbColors.TextSecondary,
            )
        },
        onClick = onToggle,
    )
}

/** Removable chips for whichever filters are active — the "what's on" view the icon alone can't give. */
@Composable
private fun ActiveFilterChips(filters: LiveFilters, onChange: ((LiveFilters) -> LiveFilters) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AdsbColors.ListHeaderBg)
            .padding(horizontal = AdsbDimens.ScreenGutter, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (filters.airborne) ActiveChip("Airborne") { onChange { it.copy(airborne = false) } }
        if (filters.onGround) ActiveChip("On ground") { onChange { it.copy(onGround = false) } }
        if (filters.emergency) ActiveChip("Emergency") { onChange { it.copy(emergency = false) } }
    }
}

@Composable
private fun ActiveChip(label: String, onRemove: () -> Unit) {
    Surface(
        modifier = Modifier.clickable(onClick = onRemove),
        color = AdsbColors.Primary,
        shape = RoundedCornerShape(AdsbDimens.PillCornerRadius),
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(label, fontSize = 11.sp, fontWeight = FontWeight.W600, color = AdsbColors.OnPrimary)
            Icon(Icons.Default.Close, contentDescription = "Remove", tint = AdsbColors.OnPrimary, modifier = Modifier.size(12.dp))
        }
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

@Composable
private fun AircraftRowWithMenu(
    row: AircraftRowUi,
    onClick: () -> Unit,
    onShowOnMap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AircraftRow(
        row = row,
        onClick = onClick,
        onLongClick = onShowOnMap,
        modifier = modifier,
    )
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
