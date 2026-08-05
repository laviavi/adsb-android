package com.laviavi.adsbandroid.ui.logs

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.laviavi.adsbandroid.ui.MainViewModel
import com.laviavi.adsbandroid.ui.model.*
import com.laviavi.adsbandroid.ui.theme.AdsbColors
import com.laviavi.adsbandroid.ui.theme.AdsbDimens
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LogsScreen(viewModel: MainViewModel) {
    val events by viewModel.diagnosticEvents.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().background(AdsbColors.Background)) {
        EventsList(events)
    }
}

@Composable
private fun EventsList(events: List<DiagnosticEvent>) {
    if (events.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No events yet.", fontSize = 13.sp, color = AdsbColors.TextSecondary)
        }
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(events.reversed()) { event ->
                EventRow(event)
            }
        }
    }
}

private val TIME_FMT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

@Composable
private fun EventRow(event: DiagnosticEvent) {
    val severityColor = when (event.severity) {
        Severity.ERROR -> AdsbColors.Error
        Severity.WARNING -> AdsbColors.Warning
        Severity.INFO -> AdsbColors.TextSecondary
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AdsbDimens.ScreenGutter, vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            TIME_FMT.format(Date(event.timestampMs)),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = AdsbColors.TextDisabled,
            modifier = Modifier.width(90.dp),
        )
        Text(
            event.category.name.take(3),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = severityColor,
            fontWeight = FontWeight.W600,
            modifier = Modifier.width(32.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(event.message, fontSize = 12.sp, color = AdsbColors.TextPrimary)
            event.detail?.let {
                Text(it, fontSize = 11.sp, color = AdsbColors.TextSecondary)
            }
        }
    }
    HorizontalDivider(color = AdsbColors.Surface, modifier = Modifier.padding(horizontal = AdsbDimens.ScreenGutter))
}
