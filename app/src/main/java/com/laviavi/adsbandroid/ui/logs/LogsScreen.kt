package com.laviavi.adsbandroid.ui.logs

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.laviavi.adsbandroid.pipeline.ErrorLog
import com.laviavi.adsbandroid.ui.MainViewModel
import com.laviavi.adsbandroid.ui.model.*
import com.laviavi.adsbandroid.ui.theme.AdsbColors
import com.laviavi.adsbandroid.ui.theme.AdsbDimens
import java.text.SimpleDateFormat
import java.util.*

/** One rendered row, whichever of the two independent sources it came from. */
private data class LogRow(val timestampMs: Long, val tag: String, val severity: Severity, val message: String, val detail: String?)

private fun DiagnosticEvent.toLogRow() = LogRow(timestampMs, category.name.take(3), severity, message, detail)

private fun ErrorLog.Entry.toLogRow() = LogRow(
    timestampMs = timestamp,
    tag = level.take(3),
    severity = when (level) {
        "ERROR" -> Severity.ERROR
        "WARN" -> Severity.WARNING
        else -> Severity.INFO
    },
    message = message,
    detail = null,
)

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(viewModel: MainViewModel, onBack: () -> Unit = {}) {
    val events by viewModel.diagnosticEvents.collectAsStateWithLifecycle()
    val errorLogEntries by ErrorLog.entries.collectAsStateWithLifecycle()
    // Off by default and not persisted — a debug aid the screen shouldn't be
    // showing all the time, per Avi's own wording; ErrorLog itself keeps
    // capturing regardless, this only gates whether it's displayed here.
    var showErrorLog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = AdsbColors.Background,
        topBar = {
            TopAppBar(
                title = { Text("Logs") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = AdsbColors.TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AdsbColors.Background, titleContentColor = AdsbColors.TextPrimary),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).background(AdsbColors.Background)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AdsbDimens.ScreenGutter, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Pipeline error log",
                    fontSize = 12.sp,
                    color = AdsbColors.TextSecondary,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = showErrorLog, onCheckedChange = { showErrorLog = it })
            }
            HorizontalDivider(color = AdsbColors.Surface)

            val rows = remember(events, errorLogEntries, showErrorLog) {
                val merged = events.map { it.toLogRow() } + if (showErrorLog) errorLogEntries.map { it.toLogRow() } else emptyList()
                merged.sortedByDescending { it.timestampMs }
            }
            EventsList(rows)
        }
    }
}

@Composable
private fun EventsList(rows: List<LogRow>) {
    if (rows.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No events yet.", fontSize = 13.sp, color = AdsbColors.TextSecondary)
        }
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(rows) { row ->
                EventRow(row)
            }
        }
    }
}

private val TIME_FMT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

@Composable
private fun EventRow(row: LogRow) {
    val severityColor = when (row.severity) {
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
            TIME_FMT.format(Date(row.timestampMs)),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = AdsbColors.TextDisabled,
            modifier = Modifier.width(90.dp),
        )
        Text(
            row.tag,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = severityColor,
            fontWeight = FontWeight.W600,
            modifier = Modifier.width(32.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(row.message, fontSize = 12.sp, color = AdsbColors.TextPrimary)
            row.detail?.let {
                Text(it, fontSize = 11.sp, color = AdsbColors.TextSecondary)
            }
        }
    }
    HorizontalDivider(color = AdsbColors.Surface, modifier = Modifier.padding(horizontal = AdsbDimens.ScreenGutter))
}
