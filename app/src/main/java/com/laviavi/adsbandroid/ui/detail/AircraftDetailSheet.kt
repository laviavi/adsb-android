package com.laviavi.adsbandroid.ui.detail

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laviavi.adsbandroid.aircraft.AircraftState
import com.laviavi.adsbandroid.aircraft.MessageSummary
import com.laviavi.adsbandroid.crc.CrcChecker
import com.laviavi.adsbandroid.data.AircraftEventLogEntity
import com.laviavi.adsbandroid.ui.components.FreshnessDot
import com.laviavi.adsbandroid.ui.model.AgeTier
import com.laviavi.adsbandroid.ui.theme.AdsbColors
import com.laviavi.adsbandroid.ui.theme.AdsbDimens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun AircraftDetailSheet(
    aircraft: AircraftState,
    onDismiss: () -> Unit,
    onRetryEnrichment: (String) -> Unit = {},
    onLoadEventLog: suspend (String) -> List<AircraftEventLogEntity> = { emptyList() },
) {
    val now = System.currentTimeMillis()
    val scope = rememberCoroutineScope()

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AdsbDimens.ScreenGutter),
        verticalArrangement = Arrangement.spacedBy(AdsbDimens.SpacingMd),
    ) {
        // Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column {
                    Text(
                        aircraft.icao,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.W600,
                        color = AdsbColors.Primary,
                    )
                    aircraft.callsign?.let {
                        Text(it, fontSize = 20.sp, fontWeight = FontWeight.W600, color = AdsbColors.TextPrimary)
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, "Close", tint = AdsbColors.TextSecondary)
                }
            }
        }

        // Identity chips
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                aircraft.registration?.let { InfoChip(it) }
                aircraft.aircraftType?.let { InfoChip(it) }
                aircraft.operator?.let { InfoChip(it) }
            }
        }

        // State grid
        item {
            HorizontalDivider(color = AdsbColors.Surface)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = AdsbDimens.SpacingSm),
                horizontalArrangement = Arrangement.spacedBy(AdsbDimens.SpacingMd),
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    StateField("Baro altitude", aircraft.altitudeFt?.let { if (it >= 18000) "FL${it/100}" else "$it ft" }, fieldAge(now, aircraft.lastSeenMs))
                    StateField("Ground speed", aircraft.groundSpeedKt?.let { "$it kt" }, fieldAge(now, aircraft.lastSeenMs))
                    StateField("Position", aircraft.latitude?.let { lat ->
                        aircraft.longitude?.let { lon -> "%.6f, %.6f".format(lat, lon) }
                    }, fieldAge(now, aircraft.lastPositionMs ?: 0))
                    StateField("Squawk", aircraft.squawk, fieldAge(now, aircraft.lastSeenMs))
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    StateField("Track", aircraft.trackDeg?.let { "$it°" }, fieldAge(now, aircraft.lastSeenMs))
                    StateField("Vertical rate", aircraft.verticalRateFpm?.let { "$it fpm" }, fieldAge(now, aircraft.lastSeenMs))
                    StateField("Range/bearing", aircraft.distanceNm?.let { d ->
                        aircraft.bearingDeg?.let { b -> "%.1f mi / %03d°".format(d * 1.15078, b.toInt()) }
                    }, fieldAge(now, aircraft.lastSeenMs))
                    StateField("Heading", aircraft.headingDeg?.let { "$it°" } ?: aircraft.magneticHeadingDeg?.let { "%.1f°".format(it) },
                        fieldAge(now, aircraft.lastSeenMs))
                }
            }
            HorizontalDivider(color = AdsbColors.Surface)
        }

        // Diagnosis cards for missing fields
        item {
            DiagnosisSection(aircraft)
        }

        // Reception counters
        item {
            SectionHeader("RECEPTION")
            Row(horizontalArrangement = Arrangement.spacedBy(AdsbDimens.SpacingMd)) {
                CounterChip("${aircraft.messageCount} msgs", AdsbColors.TextPrimary)
                CounterChip("${aircraft.validCount} valid", AdsbColors.Success)
                CounterChip("${aircraft.correctedCount} corrected", AdsbColors.Warning)
                CounterChip("${aircraft.badCrcCount} bad", AdsbColors.Error)
            }
        }

        // TCAS section
        if (aircraft.tcasSl != null || aircraft.tcasEventCount > 0) {
            item {
                SectionHeader("TCAS")
                aircraft.tcasSl?.let { Text("SL $it", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = AdsbColors.TextSecondary) }
                if (aircraft.tcasRaActive) {
                    Text("RA: ${aircraft.tcasRaText ?: "active"}", fontSize = 13.sp, color = AdsbColors.Error)
                }
                if (aircraft.tcasEventCount > 0) {
                    Text("${aircraft.tcasEventCount} events", fontSize = 12.sp, color = AdsbColors.TextSecondary)
                }
            }
        }

        // Message timeline (collapsed by default)
        item {
            var expanded by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = AdsbDimens.SpacingSm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionHeader("MESSAGE TIMELINE")
                Spacer(Modifier.weight(1f))
                Text("${aircraft.messageHistory.size} recent", fontSize = 11.sp, color = AdsbColors.TextDisabled)
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "▲" else "▼", fontSize = 12.sp)
                }
            }
            if (expanded) {
                aircraft.messageHistory.takeLast(50).reversed().forEach { msg ->
                    MessageRow(msg)
                }
            }
        }

        // Enrichment log (collapsed by default) — DETECTED/ENRICHMENT_ATTEMPT/MOVED_TO_HISTORY timeline
        item {
            var expanded by remember { mutableStateOf(false) }
            var eventLog by remember(aircraft.icao) { mutableStateOf<List<AircraftEventLogEntity>>(emptyList()) }
            LaunchedEffect(aircraft.icao, expanded) {
                if (expanded) eventLog = onLoadEventLog(aircraft.icao)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = AdsbDimens.SpacingSm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionHeader("ENRICHMENT LOG")
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    onRetryEnrichment(aircraft.icao)
                    scope.launch {
                        delay(2000)
                        eventLog = onLoadEventLog(aircraft.icao)
                    }
                }) { Text("Retry", fontSize = 12.sp) }
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "▲" else "▼", fontSize = 12.sp)
                }
            }
            if (expanded) {
                if (eventLog.isEmpty()) {
                    Text("No events logged yet.", fontSize = 12.sp, color = AdsbColors.TextDisabled)
                } else {
                    eventLog.reversed().forEach { e -> EventLogRow(e) }
                }
            }
        }

        item { Spacer(Modifier.height(AdsbDimens.SpacingXxl)) }
    }
}

@Composable
private fun InfoChip(text: String) {
    Surface(
        color = AdsbColors.SurfaceElevated,
        shape = RoundedCornerShape(AdsbDimens.PillCornerRadius),
        border = androidx.compose.foundation.BorderStroke(1.dp, AdsbColors.Outline),
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = AdsbColors.TextPrimary,
        )
    }
}

@Composable
private fun StateField(label: String, value: String?, ageTier: AgeTier) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FreshnessDot(ageTier)
        Column {
            Text(value ?: "—", fontFamily = FontFamily.Monospace, fontSize = 15.sp, color = AdsbColors.TextPrimary)
            Text(label, fontSize = 10.sp, color = AdsbColors.TextDisabled)
        }
    }
}

@Composable
private fun CounterChip(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = color)
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
        modifier = Modifier.padding(vertical = AdsbDimens.SpacingSm),
    )
}

@Composable
private fun DiagnosisSection(aircraft: AircraftState) {
    if (aircraft.callsign == null) {
        DiagnosisCard("Callsign missing", "No TC 1-4 messages received yet.")
    }
    if (aircraft.latitude == null || aircraft.longitude == null) {
        DiagnosisCard("Position missing", "Not enough CPR frame pairs for position decode.")
    }
    if (aircraft.altitudeFt == null && !aircraft.onGround) {
        DiagnosisCard("Altitude missing", "No altitude messages received yet.")
    }
    if (aircraft.headingDeg == null && aircraft.magneticHeadingDeg == null) {
        DiagnosisCard("Heading missing", "No TC 19 velocity messages carrying magnetic heading.")
    }
}

@Composable
private fun DiagnosisCard(title: String, body: String) {
    Surface(
        color = AdsbColors.WarningFill,
        shape = RoundedCornerShape(AdsbDimens.CardCornerRadius),
        border = androidx.compose.foundation.BorderStroke(1.dp, AdsbColors.Warning.copy(alpha = 0.25f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AdsbDimens.SpacingXs),
    ) {
        Column(modifier = Modifier.padding(AdsbDimens.CardPadding)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.W600, color = AdsbColors.Warning)
            Text(body, fontSize = 12.sp, color = AdsbColors.TextSecondary)
        }
    }
}

@Composable
private fun MessageRow(msg: MessageSummary) {
    val timeStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(msg.timestampMs))
    val dfLabel = "DF${msg.downlinkFormat}" + (msg.typecode?.let { " TC$it" } ?: "")
    val (crcLabel, crcColor) = when (msg.crcResult) {
        CrcChecker.CrcResult.VALID -> "VAL" to AdsbColors.Success
        CrcChecker.CrcResult.CORRECTED -> "COR" to AdsbColors.Warning
        CrcChecker.CrcResult.RECOVERED -> "REC" to AdsbColors.Primary
        else -> "BAD" to AdsbColors.Error
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(timeStr, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = AdsbColors.TextSecondary,
            modifier = Modifier.width(68.dp))
        Text(dfLabel, fontSize = 12.sp, color = AdsbColors.TextPrimary, modifier = Modifier.weight(1f))
        Text(crcLabel, fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.W600, color = crcColor)
    }
}

@Composable
private fun EventLogRow(e: AircraftEventLogEntity) {
    val timeStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(e.timestampMs))
    val label = when (e.eventType) {
        "DETECTED" -> "DETECTED"
        "MOVED_TO_HISTORY" -> "MOVED TO HISTORY"
        else -> listOfNotNull(
            e.source ?: "cache",
            if (e.servedFromCache == true) "cached" else if (e.servedFromCache == false) "fresh" else null,
        ).joinToString(" ")
    }
    val statusColor = when (e.success) {
        true -> AdsbColors.Success
        false -> AdsbColors.Error
        null -> AdsbColors.TextSecondary
    }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(timeStr, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = AdsbColors.TextSecondary,
                modifier = Modifier.width(60.dp))
            Text(label, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.W600, color = statusColor)
        }
        e.resultSummary?.let {
            Text(it, fontSize = 11.sp, color = AdsbColors.TextDisabled, modifier = Modifier.padding(start = 66.dp))
        }
    }
}

private fun fieldAge(now: Long, lastMs: Long): AgeTier {
    val age = now - lastMs
    return when {
        age <= 5000 -> AgeTier.FRESH
        age <= 15000 -> AgeTier.AGEING
        else -> AgeTier.STALE
    }
}
