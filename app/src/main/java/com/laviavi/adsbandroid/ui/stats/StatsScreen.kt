package com.laviavi.adsbandroid.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laviavi.adsbandroid.data.AircraftVisitEntity
import com.laviavi.adsbandroid.ui.history.formatDuration
import com.laviavi.adsbandroid.ui.theme.AdsbColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class StatsTab(val label: String) { AIRLINE("By airline"), PRIVATE("Private aircraft") }

private val dateFormat = SimpleDateFormat("MM/dd/yyyy HH:mm", Locale.US)

/**
 * Log book of every aircraft the receiver has ever tracked, independent of the
 * History screen's session-based list and its Clear button — backed by
 * [AircraftVisitEntity], one row per departure, never overwritten.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    visits: List<AircraftVisitEntity>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val summaries = remember(visits) { summarizeVisits(visits) }
    var tab by remember { mutableStateOf(StatsTab.AIRLINE) }
    var selected by remember { mutableStateOf<AircraftSummary?>(null) }

    Scaffold(
        modifier = modifier,
        containerColor = AdsbColors.Background,
        topBar = {
            TopAppBar(
                title = { Text("Aircraft stats") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AdsbColors.Background),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(
                selectedTabIndex = tab.ordinal,
                containerColor = AdsbColors.Surface,
                contentColor = AdsbColors.Primary,
            ) {
                StatsTab.entries.forEach { t ->
                    Tab(selected = tab == t, onClick = { tab = t }, text = { Text(t.label, fontSize = 12.sp) })
                }
            }

            val shown = when (tab) {
                StatsTab.AIRLINE -> summaries.filter { it.isAirline }
                StatsTab.PRIVATE -> summaries.filterNot { it.isAirline }
            }

            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (tab == StatsTab.AIRLINE) "Airline aircraft appear here once they've departed."
                        else "Private aircraft appear here once they've departed.",
                        color = AdsbColors.TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else when (tab) {
                StatsTab.AIRLINE -> {
                    val grouped = shown
                        .groupBy { it.operator?.takeIf { o -> o.isNotBlank() } ?: "Unknown airline" }
                        .entries.sortedBy { it.key }
                    LazyColumn(Modifier.fillMaxSize()) {
                        grouped.forEach { (airline, group) ->
                            item(key = "hdr_$airline") { AirlineHeader(airline, group) }
                            items(group.sortedByDescending { it.timesSeen }, key = { it.icao }) {
                                StatsRow(it, onClick = { selected = it })
                            }
                        }
                    }
                }
                StatsTab.PRIVATE -> {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(shown.sortedByDescending { it.timesSeen }, key = { it.icao }) {
                            StatsRow(it, onClick = { selected = it })
                        }
                    }
                }
            }
        }
    }

    selected?.let { summary ->
        ModalBottomSheet(
            onDismissRequest = { selected = null },
            containerColor = AdsbColors.Surface,
            contentColor = AdsbColors.TextPrimary,
        ) {
            VisitHistory(summary, visits.filter { it.icao == summary.icao })
        }
    }
}

@Composable
private fun AirlineHeader(airline: String, aircraft: List<AircraftSummary>) {
    Text(
        "$airline — ${aircraft.size} aircraft, ${aircraft.sumOf { it.timesSeen }} sightings",
        style = MaterialTheme.typography.labelMedium,
        color = AdsbColors.Primary,
        modifier = Modifier
            .fillMaxWidth()
            .background(AdsbColors.Background)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun StatsRow(summary: AircraftSummary, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    summary.icao,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(
                    summary.registration ?: "—",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(
                    summary.aircraftType ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "Seen ${summary.timesSeen}×",
                    style = MaterialTheme.typography.labelLarge,
                    color = AdsbColors.Primary,
                )
            }
            Text(
                "First: ${dateFormat.format(Date(summary.firstSeenEverMs))}   " +
                    "Last: ${dateFormat.format(Date(summary.lastSeenEverMs))}",
                style = MaterialTheme.typography.bodySmall,
                fontSize = 11.sp,
                color = AdsbColors.TextSecondary,
            )
        }
    }
}

@Composable
private fun VisitHistory(summary: AircraftSummary, visits: List<AircraftVisitEntity>) {
    Column(Modifier.padding(16.dp)) {
        Text(
            "${summary.icao}  ${summary.registration ?: ""}",
            style = MaterialTheme.typography.titleMedium,
            color = AdsbColors.TextPrimary,
        )
        Text(
            "${summary.operator ?: "Unknown operator"} · seen ${summary.timesSeen} time" +
                if (summary.timesSeen == 1) "" else "s",
            style = MaterialTheme.typography.bodySmall,
            color = AdsbColors.TextSecondary,
        )
        Spacer(Modifier.height(12.dp))
        LazyColumn(
            modifier = Modifier.heightIn(max = 400.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(visits.sortedByDescending { it.firstSeenMs }, key = { it.id }) { v ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        dateFormat.format(Date(v.firstSeenMs)),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = AdsbColors.TextPrimary,
                    )
                    Text(
                        formatDuration(v.lastSeenMs - v.firstSeenMs),
                        fontSize = 12.sp,
                        color = AdsbColors.TextSecondary,
                    )
                }
            }
        }
    }
}
