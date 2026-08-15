package com.laviavi.adsbandroid.ui.history

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    DAY("Day"),
    HOUR("Hour"),
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(
    entries: List<AircraftSeenEntity>,
    onClear: () -> Unit,
    onShare: () -> Unit,
    onShareEventLog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var filter by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(HistorySortOrder.LAST_SEEN) }
    var groupBy by remember { mutableStateOf(HistoryGroupBy.NONE) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showGroupMenu by remember { mutableStateOf(false) }

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
            // Event log covers every icao ever logged, independent of aircraft_seen (this
            // screen's own data) — always offered, not gated on `entries` being non-empty,
            // since it's the only way to retrieve a departed aircraft's enrichment log.
            TextButton(onClick = onShareEventLog) { Text("Share log", color = AdsbColors.Primary) }
            if (entries.isNotEmpty()) {
                TextButton(onClick = onShare) { Text("Share", color = AdsbColors.Primary) }
                TextButton(onClick = onClear) { Text("Clear", color = AdsbColors.Primary) }
            }
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
            when (groupBy) {
                HistoryGroupBy.NONE -> LazyColumn(Modifier.fillMaxSize()) {
                    items(sorted, key = { it.icao }) { HistoryRow(it) }
                }
                HistoryGroupBy.AIRLINE -> {
                    val grouped = sorted
                        .groupBy { it.operator?.takeIf { o -> o.isNotBlank() } ?: "Unknown" }
                        .entries.sortedBy { it.key }
                    LazyColumn(Modifier.fillMaxSize()) {
                        grouped.forEach { (airline, group) ->
                            stickyHeader(key = "hdr_$airline") { GroupHeader(airline) }
                            items(group, key = { it.icao }) { HistoryRow(it) }
                        }
                    }
                }
                HistoryGroupBy.DAY -> {
                    val dayFmt = SimpleDateFormat("EEE, MMM d", Locale.getDefault())
                    val grouped = sorted
                        .groupBy { dayFmt.format(Date(it.lastSeenMs)) }
                        .entries.sortedByDescending { it.key }
                    LazyColumn(Modifier.fillMaxSize()) {
                        grouped.forEach { (day, group) ->
                            stickyHeader(key = "hdr_$day") { GroupHeader(day) }
                            items(group, key = { it.icao }) { HistoryRow(it) }
                        }
                    }
                }
                HistoryGroupBy.HOUR -> {
                    val hourFmt = SimpleDateFormat("HH:00", Locale.getDefault())
                    val grouped = sorted
                        .groupBy { hourFmt.format(Date(it.lastSeenMs)) }
                        .entries.sortedByDescending { it.key }
                    LazyColumn(Modifier.fillMaxSize()) {
                        grouped.forEach { (hour, group) ->
                            stickyHeader(key = "hdr_$hour") { GroupHeader(hour) }
                            items(group, key = { it.icao }) { HistoryRow(it) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = AdsbColors.Primary,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

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
