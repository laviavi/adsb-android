package com.laviavi.adsbandroid.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laviavi.adsbandroid.aircraft.AircraftState
import com.laviavi.adsbandroid.aircraft.MessageSummary
import com.laviavi.adsbandroid.crc.CrcChecker
import com.laviavi.adsbandroid.enrich.DataSource
import com.laviavi.adsbandroid.ui.theme.AdsbColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AircraftDetailScreen(state: AircraftState, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            state.icao,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        state.callsign?.also {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 10.dp),
        ) {
            item { IdentityCard(state) }
            item { DiagnosisCard(state) }
            item { SignalCard(state) }
            if (state.messageHistory.isNotEmpty()) {
                item {
                    SectionHeader("MESSAGE HISTORY (${state.messageHistory.size})")
                }
                items(state.messageHistory.reversed()) { msg ->
                    MessageRow(msg)
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

// ── Cards ─────────────────────────────────────────────────────────────────────

@Composable
private fun DetailCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(AdsbColors.Surface)
            .border(1.dp, AdsbColors.Outline, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = AdsbColors.Primary,
        )
        content()
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = AdsbColors.Primary,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
    )
}

@Composable
private fun FieldRow(label: String, value: String, valueColor: Color = AdsbColors.TextPrimary) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = AdsbColors.TextSecondary,
            modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, color = valueColor,
            fontWeight = FontWeight.Medium)
    }
}

// ── Identity ──────────────────────────────────────────────────────────────────

@Composable
private fun IdentityCard(state: AircraftState) {
    DetailCard("Identity") {
        FieldRow("ICAO", state.icao)
        FieldRow("Callsign", state.callsign?.let { "$it${sourceGlyph(state.routeSource)}" } ?: "—",
            if (state.callsign != null) AdsbColors.TextPrimary else AdsbColors.TextSecondary)
        FieldRow("Registration",
            state.registration?.let { "$it${sourceGlyph(state.registrationSource)}" } ?: "—")
        FieldRow("Operator",
            state.operator?.let { "$it${sourceGlyph(state.operatorSource)}" } ?: "—")
        state.country?.let { FieldRow("Country", it) }
        state.aircraftType?.let { FieldRow("Type", it) }
        state.route?.let { FieldRow("Route", it) }
        state.squawk?.let { FieldRow("Squawk", it) }
        if (state.onGround) FieldRow("On ground", "Yes", AdsbColors.Warning)
    }
}

private fun sourceGlyph(src: DataSource?) = when (src) {
    null, DataSource.DECODED -> ""
    DataSource.ALGORITHMIC -> " *"
    DataSource.DATABASE -> " •"
    DataSource.NETWORK -> " ~"
}

// ── Diagnosis ─────────────────────────────────────────────────────────────────

@Composable
private fun DiagnosisCard(state: AircraftState) {
    val positionFrames = state.messageHistory.count { it.typecode in 5..22 }
    val altFrames = state.messageHistory.count {
        it.downlinkFormat in listOf(4, 20) || it.typecode in 9..18
    }
    val altBaro = state.altitudeFt
    val altGnss = state.altitudeGnssFt
    val lat = state.latitude
    val lon = state.longitude
    val gs = state.groundSpeedKt
    val ias = state.airspeedKt

    DetailCard("Diagnosis") {
        // Callsign
        val cs = state.callsign
        if (cs != null) {
            DiagRow("Callsign", cs, AdsbColors.Success)
        } else {
            DiagRow("Callsign", "No callsign — only DF17/18 TC1-4 carry it", AdsbColors.TextSecondary)
        }

        // Altitude
        when {
            altBaro != null -> {
                val gnss = altGnss?.let { " / GNSS ${"%,d".format(it)} ft" } ?: ""
                DiagRow("Altitude", "${"%,d".format(altBaro)} ft baro$gnss", AdsbColors.Success)
            }
            altGnss != null ->
                DiagRow("Altitude", "No baro — GNSS ${"%,d".format(altGnss)} ft only", AdsbColors.Warning)
            altFrames > 0 ->
                DiagRow("Altitude", "No altitude decoded yet ($altFrames altitude frames seen)", AdsbColors.Warning)
            else ->
                DiagRow("Altitude", "No altitude (no DF4/20 or TC9-18 received)", AdsbColors.TextSecondary)
        }

        // Position
        when {
            lat != null && lon != null -> {
                val age = state.lastPositionMs?.let { (System.currentTimeMillis() - it) / 1000 }
                val ageSuffix = age?.let { if (it < 60) " (${it}s ago)" else " (${it/60}m ago)" } ?: ""
                DiagRow("Position",
                    "${"%.4f".format(lat)}, ${"%.4f".format(lon)}$ageSuffix",
                    AdsbColors.Success)
            }
            positionFrames > 0 ->
                DiagRow("Position",
                    "No fix — CPR needs even+odd pair ($positionFrames frames buffered)",
                    AdsbColors.Warning)
            else ->
                DiagRow("Position", "No position (no TC5-22 received)", AdsbColors.TextSecondary)
        }

        // Speed
        when {
            gs != null -> {
                val trk = state.trackDeg?.let { " trk ${it}°" } ?: ""
                val vs  = state.verticalRateFpm?.let { " VS ${it} fpm" } ?: ""
                DiagRow("Speed", "$gs kt GS$trk$vs", AdsbColors.Success)
            }
            ias != null -> {
                val type = state.speedType?.replace("airspeed_", "") ?: "?"
                val hdg  = state.headingDeg?.let { " hdg ${it}°" } ?: ""
                DiagRow("Speed", "$ias kt $type$hdg", AdsbColors.Success)
            }
            else ->
                DiagRow("Speed", "No speed (no TC19 received)", AdsbColors.TextSecondary)
        }

        // Autopilot / target state
        if (state.autoPilotEngaged) {
            val selAlt = state.selectedAltitudeFt?.let { " sel alt ${"%,d".format(it)} ft" } ?: ""
            val selHdg = state.selectedHeadingDeg?.let { " sel hdg ${it}°" } ?: ""
            DiagRow("Autopilot", "Engaged$selAlt$selHdg", AdsbColors.TextPrimary)
        }

        // TCAS
        when {
            state.tcasRaActive ->
                DiagRow("TCAS", "RA ACTIVE: ${state.tcasRaText ?: "advisory"}", AdsbColors.Error)
            state.tcasRaTerminated ->
                DiagRow("TCAS", "RA terminated (${state.tcasEventCount} events)", AdsbColors.Warning)
            state.tcasOperational ->
                DiagRow("TCAS", "Operational (SL ${state.tcasSl ?: "?"})", AdsbColors.Success)
            state.tcasSl != null ->
                DiagRow("TCAS", "SL ${state.tcasSl}", AdsbColors.TextPrimary)
            else ->
                DiagRow("TCAS", "No TCAS data", AdsbColors.TextSecondary)
        }

        // Accuracy (NACp/NACv)
        if (state.nacP != null || state.nacV != null) {
            val nac = buildString {
                state.nacP?.let { append("NACp=$it ") }
                state.nacV?.let { append("NACv=$it ") }
                state.sil?.let  { append("SIL=$it ") }
                state.gva?.let  { append("GVA=$it") }
            }.trim()
            DiagRow("Accuracy", nac, AdsbColors.TextPrimary)
        }
    }
}

@Composable
private fun DiagRow(label: String, value: String, color: Color) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = AdsbColors.TextSecondary,
            fontSize = 10.sp)
        Text(value, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

// ── Signal ────────────────────────────────────────────────────────────────────

@Composable
private fun SignalCard(state: AircraftState) {
    DetailCard("Signal & Reception") {
        FieldRow("Last signal",
            state.signalDbfs?.let { "%.1f dBFS".format(it) } ?: "—")
        FieldRow("Avg signal (${state.signalHistory.size} samples)",
            state.avgSignalDbfs?.let { "%.1f dBFS".format(it) } ?: "—")
        Spacer(Modifier.height(2.dp))
        FieldRow("Messages", "%,d".format(state.messageCount))
        FieldRow("Valid", "%,d".format(state.validCount), AdsbColors.Success)
        if (state.correctedCount > 0)
            FieldRow("Corrected (1-bit)", "%,d".format(state.correctedCount), AdsbColors.Warning)
        if (state.badCrcCount > 0)
            FieldRow("Bad CRC", "%,d".format(state.badCrcCount), AdsbColors.Error)
        if (state.distanceNm != null || state.bearingDeg != null) {
            Spacer(Modifier.height(2.dp))
            val dist = state.distanceNm?.let { "%.1f nm".format(it) } ?: "—"
            val brg  = state.bearingDeg?.let { "%03.0f°".format(it) } ?: "—"
            FieldRow("Distance / bearing", "$dist  $brg")
        }
    }
}

// ── Message history ───────────────────────────────────────────────────────────

private val tsFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

@Composable
private fun MessageRow(msg: MessageSummary) {
    val crcColor = when (msg.crcResult) {
        CrcChecker.CrcResult.VALID, CrcChecker.CrcResult.RECOVERED -> AdsbColors.Success
        CrcChecker.CrcResult.CORRECTED -> AdsbColors.Warning
        else -> AdsbColors.Error
    }
    val crcMark = when (msg.crcResult) {
        CrcChecker.CrcResult.VALID     -> "OK"
        CrcChecker.CrcResult.RECOVERED -> "REC"
        CrcChecker.CrcResult.CORRECTED -> "COR"
        else                           -> "BAD"
    }
    val sig = if (msg.signalLevel > 0.0)
        "%.0f dBFS".format((20.0 * kotlin.math.log10(msg.signalLevel)).coerceAtLeast(-40.0))
    else "—"
    val tc = msg.typecode?.let { " TC$it" } ?: ""

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            tsFormat.format(Date(msg.timestampMs)),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = AdsbColors.TextSecondary,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.width(68.dp),
        )
        Text(
            "DF${msg.downlinkFormat}$tc",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = AdsbColors.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        Text(
            sig,
            style = MaterialTheme.typography.bodySmall,
            fontSize = 11.sp,
            color = AdsbColors.TextSecondary,
            modifier = Modifier.width(60.dp),
        )
        Text(
            crcMark,
            style = MaterialTheme.typography.bodySmall,
            fontSize = 11.sp,
            color = crcColor,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(32.dp),
        )
    }
}
