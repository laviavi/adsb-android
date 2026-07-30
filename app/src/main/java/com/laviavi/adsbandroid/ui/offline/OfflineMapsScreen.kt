package com.laviavi.adsbandroid.ui.offline

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.laviavi.adsbandroid.offline.*
import com.laviavi.adsbandroid.ui.theme.AdsbColors
import com.laviavi.adsbandroid.ui.theme.AdsbDimens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dateFormat = SimpleDateFormat("d MMM yyyy", Locale.getDefault())

/**
 * Offline Maps.
 *
 * Pure presentation over [OfflineMapsViewModel]; no download, naming or deletion
 * rule is decided here. Wording avoids jargon deliberately — "areas" rather than
 * "tiles", plain sentences for the Wi-Fi restriction.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineMapsScreen(
    observerLat: Double,
    observerLon: Double,
    onBack: () -> Unit,
    viewModel: OfflineMapsViewModel = androidx.hilt.navigation.compose.hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showDownloadSheet by remember { mutableStateOf(false) }
    var deleteMode by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(
        containerColor = AdsbColors.Background,
        topBar = {
            TopAppBar(
                title = { Text("Offline maps") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.segments.isNotEmpty()) {
                        TextButton(onClick = {
                            deleteMode = !deleteMode
                            if (!deleteMode) viewModel.clearDeletionSelection()
                        }) { Text(if (deleteMode) "Done" else "Select") }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = AdsbDimens.ScreenGutter),
            verticalArrangement = Arrangement.spacedBy(AdsbDimens.SpacingMd),
            contentPadding = PaddingValues(vertical = AdsbDimens.SpacingMd),
        ) {
            item { WifiBanner(state.networkState) }
            item { StorageCard(state.usage) }

            state.activeProgress?.let { p ->
                item { ProgressCard(p, onCancel = viewModel::cancelDownload) }
            }

            if (state.pendingSuggestions.isNotEmpty()) {
                item { SectionLabel("TRAVEL COVERAGE AVAILABLE") }
                items(state.pendingSuggestions, key = { it.id }) { rec ->
                    SuggestionCard(
                        record = rec,
                        wifiReady = state.wifiReady,
                        onAppend = {
                            when (val t = viewModel.appendTarget(rec.id)) {
                                is AppendTarget.Segment -> viewModel.appendCoverage(rec.id, t.segmentId)
                                is AppendTarget.AmbiguousChoice, is AppendTarget.CreateNew, null -> Unit
                            }
                        },
                        onNotNow = { viewModel.deferSuggestion(rec.id) },
                        onDismiss = { viewModel.dismissSuggestion(rec.id) },
                        targetLabel = when (val t = viewModel.appendTarget(rec.id)) {
                            is AppendTarget.Segment ->
                                state.segments.find { it.id == t.segmentId }?.displayName
                            is AppendTarget.AmbiguousChoice -> null
                            is AppendTarget.CreateNew, null -> null
                        },
                    )
                }
            }

            if (state.resumable.isNotEmpty()) {
                item { SectionLabel("UNFINISHED DOWNLOADS") }
                items(state.resumable, key = { it.second.id }) { (seg, cov) ->
                    ResumeCard(seg, cov, state.wifiReady) { viewModel.resume(seg.id, cov.id) }
                }
            }

            item { SectionLabel("SAVED MAPS (${state.segments.size})") }
            if (state.segments.isEmpty()) {
                item {
                    Text(
                        "No offline maps yet. Download one to use the map without a connection.",
                        color = AdsbColors.TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            items(state.segments, key = { it.id }) { seg ->
                SegmentCard(
                    segment = seg,
                    selectable = deleteMode,
                    selected = seg.id in state.selectedForDeletion,
                    onToggle = { viewModel.toggleForDeletion(seg.id) },
                )
            }

            item {
                Button(
                    onClick = { showDownloadSheet = true },
                    enabled = !state.isDownloading,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Add an offline map") }
            }

            if (deleteMode && state.selectedForDeletion.isNotEmpty()) {
                item {
                    OutlinedButton(
                        onClick = { viewModel.prepareDeletion() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AdsbColors.Error),
                    ) { Text("Delete ${state.selectedForDeletion.size} selected") }
                }
            }
        }
    }

    if (showDownloadSheet) {
        DownloadSheet(
            state = state,
            onSelectRadius = { viewModel.selectRadius(it, observerLat, observerLon) },
            onSelectDetail = { viewModel.selectDetail(it, observerLat, observerLon) },
            onStart = {
                viewModel.startDownload(observerLat, observerLon)
                showDownloadSheet = false
            },
            onImport = {
                viewModel.importFromCache(observerLat, observerLon)
                showDownloadSheet = false
            },
            onDismiss = { showDownloadSheet = false; viewModel.clearRadius() },
        )
    }

    state.deletionPreview?.let { preview ->
        DeleteConfirmDialog(
            preview = preview,
            onConfirm = { viewModel.confirmDeletion(); deleteMode = false },
            onDismiss = { viewModel.clearDeletionSelection() },
        )
    }

    state.message?.let { msg ->
        AlertDialog(
            onDismissRequest = viewModel::clearMessage,
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = viewModel::clearMessage) { Text("OK") } },
        )
    }
}

@Composable
private fun WifiBanner(state: NetworkState) {
    val ready = OfflineDownloadPolicy.isDownloadAllowed(state)
    Surface(
        color = if (ready) AdsbColors.SuccessFill else AdsbColors.WarningFill,
        shape = RoundedCornerShape(AdsbDimens.CardCornerRadius),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = if (ready) "Connected to Wi-Fi — downloads available."
            else (OfflineDownloadPolicy.evaluate(state) as EligibilityResult.Ineligible).reason,
            modifier = Modifier.padding(AdsbDimens.CardPadding),
            style = MaterialTheme.typography.bodySmall,
            color = if (ready) AdsbColors.Success else AdsbColors.Warning,
        )
    }
}

@Composable
private fun StorageCard(usage: StorageUsage) {
    Card(colors = CardDefaults.cardColors(containerColor = AdsbColors.Surface)) {
        Column(Modifier.padding(AdsbDimens.CardPadding)) {
            Text("Storage used", color = AdsbColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
            Text(
                TileGeometry.formatBytes(usage.totalBytes),
                fontFamily = FontFamily.Monospace, fontSize = 20.sp,
                fontWeight = FontWeight.W600, color = AdsbColors.TextPrimary,
            )
            Text(
                "${usage.segmentCount} map(s) · ${usage.distinctTiles} areas" +
                    if (usage.sharedTiles > 0) " · ${usage.sharedTiles} shared between maps" else "",
                color = AdsbColors.TextSecondary, style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun ProgressCard(p: DownloadProgress, onCancel: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = AdsbColors.SurfaceElevated)) {
        Column(Modifier.padding(AdsbDimens.CardPadding)) {
            Text("Downloading — ${p.percent}%", color = AdsbColors.TextPrimary, fontWeight = FontWeight.W600)
            LinearProgressIndicator(
                progress = { p.percent / 100f },
                modifier = Modifier.fillMaxWidth().padding(vertical = AdsbDimens.SpacingSm),
            )
            Text(
                "${TileGeometry.formatBytes(p.bytesStored)} of about " +
                    "${TileGeometry.formatBytes(p.estimatedTotalBytes)} · ${p.remainingTiles} areas left",
                color = AdsbColors.TextSecondary, style = MaterialTheme.typography.labelSmall,
            )
            Text(
                if (OfflineDownloadPolicy.isDownloadAllowed(p.networkState)) "Downloading over Wi-Fi"
                else "Waiting for Wi-Fi",
                color = AdsbColors.TextSecondary, style = MaterialTheme.typography.labelSmall,
            )
            TextButton(onClick = onCancel) { Text("Stop (keeps what's downloaded)") }
        }
    }
}

@Composable
private fun SuggestionCard(
    record: TravelRecord,
    wifiReady: Boolean,
    targetLabel: String?,
    onAppend: () -> Unit,
    onNotNow: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = AdsbColors.Surface)) {
        Column(Modifier.padding(AdsbDimens.CardPadding)) {
            Text(
                "You travelled outside your saved maps" + (record.destinationName?.let { " to $it" } ?: "") + ".",
                color = AdsbColors.TextPrimary, style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                targetLabel?.let { "Coverage can be added to \"$it\"." }
                    ?: "Choose which saved map should receive this coverage.",
                color = AdsbColors.TextSecondary, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 2.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(AdsbDimens.SpacingSm)) {
                TextButton(onClick = onAppend, enabled = wifiReady && targetLabel != null) {
                    Text("Add coverage")
                }
                TextButton(onClick = onNotNow) { Text("Not now") }
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
            if (!wifiReady) {
                Text(
                    "Connect to Wi-Fi to add this coverage.",
                    color = AdsbColors.Warning, style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun ResumeCard(seg: OfflineSegment, cov: CoverageEntry, wifiReady: Boolean, onResume: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = AdsbColors.Surface)) {
        Column(Modifier.padding(AdsbDimens.CardPadding)) {
            Text("${seg.displayName} — ${cov.progressPercent}% downloaded", color = AdsbColors.TextPrimary)
            Text(
                "${cov.remainingTileKeys.size} areas remaining",
                color = AdsbColors.TextSecondary, style = MaterialTheme.typography.labelSmall,
            )
            TextButton(onClick = onResume, enabled = wifiReady) {
                Text(if (wifiReady) "Resume" else "Resume (needs Wi-Fi)")
            }
        }
    }
}

@Composable
private fun SegmentCard(
    segment: OfflineSegment,
    selectable: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AdsbColors.Surface),
        modifier = Modifier.fillMaxWidth().then(if (selectable) Modifier.clickable(onClick = onToggle) else Modifier),
    ) {
        Row(Modifier.padding(AdsbDimens.CardPadding), verticalAlignment = Alignment.CenterVertically) {
            if (selectable) {
                Icon(
                    if (selected) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                    contentDescription = null,
                    tint = if (selected) AdsbColors.Primary else AdsbColors.TextDisabled,
                    modifier = Modifier.padding(end = AdsbDimens.SpacingSm),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(segment.displayName, color = AdsbColors.TextPrimary, fontWeight = FontWeight.W600)
                Text(
                    buildString {
                        segment.requestedRadiusNm?.let { append("$it NM · ") }
                        append(TileGeometry.formatBytes(segment.bytesStored))
                        append(" · ")
                        append(dateFormat.format(Date(segment.createdAtMs)))
                    },
                    color = AdsbColors.TextSecondary, style = MaterialTheme.typography.labelSmall,
                )
                // Requirement 29: the three states are visually distinct.
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
                    if (segment.hasAppendedCoverage) Chip("travel coverage added", AdsbColors.Primary)
                    when (segment.state) {
                        DownloadState.COMPLETE -> Unit
                        DownloadState.PAUSED -> Chip("paused ${segment.progressPercent}%", AdsbColors.Warning)
                        DownloadState.INCOMPLETE -> Chip("incomplete ${segment.progressPercent}%", AdsbColors.Warning)
                        DownloadState.FAILED -> Chip("failed", AdsbColors.Error)
                    }
                }
            }
        }
    }
}

@Composable
private fun Chip(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        fontSize = 10.sp,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(AdsbDimens.PillCornerRadius))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(AdsbDimens.PillCornerRadius))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.W600,
        letterSpacing = 1.4.sp, color = AdsbColors.Primary,
        modifier = Modifier.padding(top = AdsbDimens.SpacingSm),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadSheet(
    state: OfflineMapsUiState,
    onSelectRadius: (OfflineRadius) -> Unit,
    onSelectDetail: (MapDetail) -> Unit,
    onStart: () -> Unit,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = AdsbColors.Surface) {
        Column(Modifier.padding(AdsbDimens.ScreenGutter).padding(bottom = AdsbDimens.SpacingXl)) {
            Text("Choose a radius", color = AdsbColors.TextPrimary, fontWeight = FontWeight.W600)
            Text(
                "Coverage is centred on your current position.",
                color = AdsbColors.TextSecondary, style = MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.height(AdsbDimens.SpacingMd))
            Row(horizontalArrangement = Arrangement.spacedBy(AdsbDimens.SpacingSm)) {
                OfflineRadius.entries.forEach { r ->
                    val selected = state.selectedRadius == r
                    OutlinedButton(
                        onClick = { onSelectRadius(r) },
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (selected) AdsbColors.PrimaryFill else androidx.compose.ui.graphics.Color.Transparent,
                            contentColor = if (selected) AdsbColors.Primary else AdsbColors.TextSecondary,
                        ),
                    ) { Text(r.label) }
                }
            }

            Spacer(Modifier.height(AdsbDimens.SpacingMd))
            Text("Detail", color = AdsbColors.TextPrimary, fontWeight = FontWeight.W600)
            Row(horizontalArrangement = Arrangement.spacedBy(AdsbDimens.SpacingSm)) {
                MapDetail.entries.forEach { d ->
                    val selected = state.selectedDetail == d
                    OutlinedButton(
                        onClick = { onSelectDetail(d) },
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (selected) AdsbColors.PrimaryFill else androidx.compose.ui.graphics.Color.Transparent,
                            contentColor = if (selected) AdsbColors.Primary else AdsbColors.TextSecondary,
                        ),
                    ) { Text(d.label) }
                }
            }

            // Requirement 27: the estimate is shown before anything downloads.
            state.estimate?.let { e ->
                Spacer(Modifier.height(AdsbDimens.SpacingMd))
                Surface(color = AdsbColors.SurfaceElevated, shape = RoundedCornerShape(AdsbDimens.CardCornerRadius)) {
                    Column(Modifier.padding(AdsbDimens.CardPadding)) {
                        Text(
                            "About ${e.rangeLabel}",
                            color = AdsbColors.TextPrimary, fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            "${e.newTiles} new areas at ${e.radius.label}, ${e.detail.label.lowercase()} detail" +
                                if (e.alreadyStoredTiles > 0) " · ${e.alreadyStoredTiles} already saved" else "",
                            color = AdsbColors.TextSecondary, style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }

            // Import is the path that always works: the tiles are already here.
            state.importEstimate?.let { imp ->
                Spacer(Modifier.height(AdsbDimens.SpacingMd))
                Surface(color = AdsbColors.SuccessFill, shape = RoundedCornerShape(AdsbDimens.CardCornerRadius)) {
                    Column(Modifier.padding(AdsbDimens.CardPadding)) {
                        Text(
                            if (imp.newTiles > 0)
                                "${imp.newTiles} areas already viewed can be saved now"
                            else "No areas for this radius have been viewed yet",
                            color = AdsbColors.Success, style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            if (imp.newTiles > 0)
                                "About ${imp.rangeLabel}. Saved from the map's own cache — no " +
                                    "internet needed, and they stop being cleared automatically."
                            else "Pan around this area on the map first, then come back.",
                            color = AdsbColors.TextSecondary, style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }

            Spacer(Modifier.height(AdsbDimens.SpacingMd))
            Button(
                onClick = onImport,
                enabled = state.canImport,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when {
                        state.selectedRadius == null -> "Choose a radius to continue"
                        (state.importEstimate?.newTiles ?: 0) > 0 -> "Save viewed areas"
                        else -> "Nothing viewed to save yet"
                    },
                )
            }

            Spacer(Modifier.height(AdsbDimens.SpacingSm))
            if (!state.downloadConfigured) {
                Text(
                    "Downloading new areas needs a map source, which isn't set up. " +
                        "Add one under Settings › Offline maps to enable it.",
                    color = AdsbColors.TextSecondary, style = MaterialTheme.typography.labelSmall,
                )
            } else if (!state.wifiReady) {
                Text(
                    "Offline maps can only download over Wi-Fi. Connect to Wi-Fi and try again.",
                    color = AdsbColors.Warning, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = AdsbDimens.SpacingSm),
                )
            }
            OutlinedButton(
                onClick = onStart,
                enabled = state.canStartDownload,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Download missing areas") }
        }
    }
}

@Composable
private fun DeleteConfirmDialog(preview: DeletionPreview, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete ${preview.segments.size} offline map(s)?") },
        text = {
            Column {
                preview.segments.forEach { s ->
                    Text(s.name, fontWeight = FontWeight.W600, color = AdsbColors.TextPrimary)
                    Text(
                        "${s.location} · ${TileGeometry.formatBytes(s.bytes)} · " +
                            "saved ${dateFormat.format(Date(s.createdAtMs))}" +
                            if (s.updatedAtMs != s.createdAtMs) ", updated ${dateFormat.format(Date(s.updatedAtMs))}" else "",
                        style = MaterialTheme.typography.labelSmall, color = AdsbColors.TextSecondary,
                    )
                    if (s.hasAppendedCoverage) {
                        Text(
                            "Includes coverage added from your travels.",
                            style = MaterialTheme.typography.labelSmall, color = AdsbColors.Warning,
                        )
                    }
                    Spacer(Modifier.height(AdsbDimens.SpacingSm))
                }
                Text(
                    "This removes ${TileGeometry.formatBytes(preview.bytesFreed)} of downloaded map data from this " +
                        "device only. Nothing else on your device or account is affected." +
                        if (preview.tilesRetainedShared > 0)
                            " ${preview.tilesRetainedShared} areas are shared with another saved map and will be kept."
                        else "",
                    style = MaterialTheme.typography.bodySmall, color = AdsbColors.TextSecondary,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Delete", color = AdsbColors.Error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
