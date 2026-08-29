package com.laviavi.adsbandroid.ui.history

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laviavi.adsbandroid.aircraft.routeDestination
import com.laviavi.adsbandroid.aircraft.routeOrigin
import com.laviavi.adsbandroid.data.AircraftSeenEntity
import com.laviavi.adsbandroid.ui.theme.AdsbColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class HistorySortOrder(val label: String) {
    LAST_SEEN("Last seen"),
    FIRST_SEEN("First seen"),
    DURATION("Duration"),
    DISTANCE("Distance"),
    MESSAGES("Messages"),
    CALLSIGN("Callsign"),
}

enum class HistoryGroupBy(val label: String) {
    NONE("None"),
    AIRLINE("Airline"),
    WEEKDAY("Weekday"),
    DAY("Day"),
    HOUR("Hour"),
    DESTINATION("Destination"),
    ORIGIN("Origin"),
}

// route may come from either adsbdb ("ORIGIN-DEST") or FlightAware ("ORIGIN → DEST")
// — routeOrigin()/routeDestination() (core/receiver) handle both formats.
private fun AircraftSeenEntity.origin(): String = routeOrigin(route) ?: "Unknown"

private fun AircraftSeenEntity.destination(): String = routeDestination(route) ?: "Unknown"

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(
    entries: List<AircraftSeenEntity>,
    onClear: () -> Unit,
    onShare: () -> Unit,
    onShareEventLog: () -> Unit,
    onShareHistoryDebug: () -> Unit, // TEMP DEBUG: history investigation — delete with the rest
    onCheckGlobalDb: (onResult: (String) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    var filter by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(HistorySortOrder.LAST_SEEN) }
    var groupBy by remember { mutableStateOf(HistoryGroupBy.NONE) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showGroupMenu by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var globalDbStatus by remember { mutableStateOf<String?>(null) }
    // Keyed on groupBy: switching how it's grouped makes the old collapsed labels
    // meaningless (e.g. an "Airline" label collapsed, then Group switches to "Day").
    var collapsedGroups by remember(groupBy) { mutableStateOf(setOf<String>()) }

    val filtered = remember(entries, filter) {
        if (filter.isBlank()) entries
        else {
            val q = filter.trim().lowercase()
            entries.filter {
                it.icao.lowercase().contains(q) ||
                    it.callsign?.lowercase()?.contains(q) == true ||
                    it.registration?.lowercase()?.contains(q) == true ||
                    it.operator?.lowercase()?.contains(q) == true
            }
        }
    }

    val sorted = remember(filtered, sort) {
        when (sort) {
            HistorySortOrder.LAST_SEEN  -> filtered.sortedByDescending { it.lastSeenMs }
            HistorySortOrder.FIRST_SEEN -> filtered.sortedByDescending { it.firstSeenMs }
            HistorySortOrder.DURATION   -> filtered.sortedByDescending { it.lastSeenMs - it.firstSeenMs }
            HistorySortOrder.DISTANCE   -> filtered.sortedByDescending { it.distanceNm ?: -1.0 }
            HistorySortOrder.MESSAGES   -> filtered.sortedByDescending { it.messageCount }
            HistorySortOrder.CALLSIGN   -> filtered.sortedBy { it.callsign ?: it.icao }
        }
    }

    // Hoisted above the list so the fold-all control (in the row below) and the
    // LazyColumn both work off the same grouping — computed once either way.
    val grouped = remember(sorted, groupBy) {
        when (groupBy) {
            HistoryGroupBy.NONE -> emptyList()
            HistoryGroupBy.AIRLINE -> sorted
                .groupBy { it.operator?.takeIf { o -> o.isNotBlank() } ?: "Unknown" }
                .entries.sortedBy { it.key }
            HistoryGroupBy.WEEKDAY -> {
                val weekdayFmt = SimpleDateFormat("EEEE", Locale.getDefault())
                val order = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
                sorted.groupBy { weekdayFmt.format(Date(it.lastSeenMs)) }
                    .entries.sortedBy { order.indexOf(it.key) }
            }
            HistoryGroupBy.DAY -> {
                // Sorted by each group's own most-recent timestamp, not by the label
                // string — the label starts with a weekday name ("Wed, Aug 19"),
                // and alphabetical order ("Wed" > "Thu") isn't calendar order, so a
                // string sort silently put yesterday above today whenever the two
                // weekday names happened to compare that way.
                val dayFmt = SimpleDateFormat("EEE, MMM d", Locale.getDefault())
                sorted.groupBy { dayFmt.format(Date(it.lastSeenMs)) }
                    .entries.sortedByDescending { entry -> entry.value.maxOf { it.lastSeenMs } }
            }
            HistoryGroupBy.HOUR -> {
                val hourFmt = SimpleDateFormat("HH:00", Locale.getDefault())
                sorted.groupBy { hourFmt.format(Date(it.lastSeenMs)) }.entries.sortedByDescending { it.key }
            }
            HistoryGroupBy.DESTINATION -> sorted.groupBy { it.destination() }.entries.sortedBy { it.key }
            HistoryGroupBy.ORIGIN -> sorted.groupBy { it.origin() }.entries.sortedBy { it.key }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val countLabel = if (filter.isNotBlank() && filtered.size != entries.size)
                "${filtered.size}/${entries.size} aircraft seen"
            else
                "${entries.size} aircraft seen"
            Text(
                countLabel,
                style = MaterialTheme.typography.labelLarge,
                color = AdsbColors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            Box {
                IconButton(onClick = { showOverflowMenu = true }) {
                    Icon(Icons.Default.MoreVert, "More options", tint = AdsbColors.TextSecondary)
                }
                DropdownMenu(expanded = showOverflowMenu, onDismissRequest = { showOverflowMenu = false }) {
                    // Event log covers every icao ever logged, independent of aircraft_seen
                    // (this screen's own data) — always offered, not gated on `entries` being
                    // non-empty, since it's the only way to retrieve a departed aircraft's log.
                    DropdownMenuItem(
                        text = { Text("Share log") },
                        onClick = { showOverflowMenu = false; onShareEventLog() },
                    )
                    // TEMP DEBUG: history investigation — delete this item with the rest.
                    DropdownMenuItem(
                        text = { Text("Share debug") },
                        onClick = { showOverflowMenu = false; onShareHistoryDebug() },
                    )
                    DropdownMenuItem(
                        text = { Text("Check DB") },
                        onClick = { showOverflowMenu = false; onCheckGlobalDb { text -> globalDbStatus = text } },
                    )
                    if (entries.isNotEmpty()) {
                        DropdownMenuItem(
                            text = { Text("Share") },
                            onClick = { showOverflowMenu = false; onShare() },
                        )
                        DropdownMenuItem(
                            text = { Text("Clear") },
                            onClick = { showOverflowMenu = false; onClear() },
                        )
                    }
                }
            }
        }

        globalDbStatus?.let { text ->
            AlertDialog(
                onDismissRequest = { globalDbStatus = null },
                confirmButton = { TextButton(onClick = { globalDbStatus = null }) { Text("OK") } },
                title = { Text("Global Aircraft DB") },
                text = { Text(text, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 13.sp) },
            )
        }

        // Filter + sort + group row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                placeholder = { Text("Search ICAO, callsign, airline…", style = MaterialTheme.typography.bodySmall) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
            )

            Box {
                TextButton(onClick = { showSortMenu = true }) {
                    val label = if (sort == HistorySortOrder.LAST_SEEN) "Sort by ▾" else "${sort.label} ▾"
                    Text(label, style = MaterialTheme.typography.labelSmall)
                }
                DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                    HistorySortOrder.entries.forEach { order ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    order.label,
                                    color = if (sort == order) AdsbColors.Primary else MaterialTheme.colorScheme.onSurface,
                                )
                            },
                            onClick = { sort = order; showSortMenu = false },
                        )
                    }
                }
            }

            Box {
                TextButton(onClick = { showGroupMenu = true }) {
                    Text("Group: ${groupBy.label} ▾", style = MaterialTheme.typography.labelSmall)
                }
                DropdownMenu(expanded = showGroupMenu, onDismissRequest = { showGroupMenu = false }) {
                    HistoryGroupBy.entries.forEach { g ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    g.label,
                                    color = if (groupBy == g) AdsbColors.Primary else MaterialTheme.colorScheme.onSurface,
                                )
                            },
                            onClick = { groupBy = g; showGroupMenu = false },
                        )
                    }
                }
            }

            // Only meaningful once there's more than one group to fold — a long
            // group list (e.g. Day or Origin over weeks of history) is exactly
            // where folding one header at a time stops being practical.
            if (groupBy != HistoryGroupBy.NONE && grouped.size > 1) {
                val allCollapsed = grouped.isNotEmpty() && grouped.all { it.key in collapsedGroups }
                TextButton(
                    onClick = {
                        collapsedGroups = if (allCollapsed) emptySet() else grouped.map { it.key }.toSet()
                    },
                ) {
                    Text(if (allCollapsed) "Expand all" else "Collapse all", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        HorizontalDivider(color = AdsbColors.Outline)

        if (sorted.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (filter.isNotBlank()) "No matches." else "Aircraft appear here once they stop transmitting.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AdsbColors.TextSecondary,
                )
            }
        } else {
            if (groupBy == HistoryGroupBy.NONE) {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(sorted, key = { it.icao }) { HistoryRow(it) }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    grouped.forEach { (label, group) ->
                        stickyHeader(key = "hdr_$label") {
                            GroupHeader(
                                label = label,
                                count = group.size,
                                collapsed = label in collapsedGroups,
                                onClick = {
                                    collapsedGroups = if (label in collapsedGroups) collapsedGroups - label else collapsedGroups + label
                                },
                            )
                        }
                        if (label !in collapsedGroups) {
                            items(group, key = { it.icao }) { HistoryRow(it) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(label: String, count: Int, collapsed: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (collapsed) "▸" else "▾",
            style = MaterialTheme.typography.labelMedium,
            color = AdsbColors.Primary,
            modifier = Modifier.padding(end = 6.dp),
        )
        Text(
            "$label ($count)",
            style = MaterialTheme.typography.labelMedium,
            color = AdsbColors.Primary,
        )
    }
}

// Date + time, no seconds — matches CsvExporter's own documented convention
// (timestampFormat()'s comment references this format), which the on-screen
// row had drifted from (time-only, with seconds). Needed now that grouping
// can span many days — a bare time-of-day is ambiguous without a date.
private val TIME_FORMAT = SimpleDateFormat("MM/dd/yyyy HH:mm", Locale.getDefault())

@Composable
private fun HistoryRow(e: AircraftSeenEntity) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 3.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(8.dp)) {
            Row {
                Text(
                    e.icao,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(
                    e.callsign ?: "—",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(
                    e.operator ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(e.registration ?: "", style = MaterialTheme.typography.bodySmall)
            }
            e.route?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp, color = AdsbColors.Primary)
            }
            Text(
                buildString {
                    append("Last: ${TIME_FORMAT.format(Date(e.lastSeenMs))}  ")
                    append("Alt: ${e.altitudeFt?.let { "%,d ft".format(it) } ?: "—"}  ")
                    append("Dist: ${e.distanceNm?.let { "%.1f nm".format(it) } ?: "—"}  ")
                    append("Msgs: ${e.messageCount}")
                },
                style = MaterialTheme.typography.bodySmall,
                fontSize = 11.sp,
                color = AdsbColors.TextSecondary,
            )
            Text(
                "Tracked for ${formatDuration(e.lastSeenMs - e.firstSeenMs)}",
                style = MaterialTheme.typography.bodySmall,
                fontSize = 11.sp,
                color = AdsbColors.TextSecondary,
            )
        }
    }
}

internal fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${s}s"
        else -> "${s}s"
    }
}
