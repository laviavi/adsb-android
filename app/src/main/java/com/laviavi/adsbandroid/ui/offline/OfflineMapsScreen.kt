package com.laviavi.adsbandroid.ui.offline

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
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
import com.laviavi.adsbandroid.offline.NetworkState
import com.laviavi.adsbandroid.offline.SavedRegion
import com.laviavi.adsbandroid.ui.theme.AdsbColors
import com.laviavi.adsbandroid.ui.theme.AdsbDimens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dateFormat = SimpleDateFormat("d MMM yyyy", Locale.getDefault())

/**
 * Offline Maps, v2.0: download a fixed-radius area (`OfflineMapsViewModel.RADIUS_NM`)
 * around the current position for offline use via MapLibre's native offline regions.
 * Simpler than v1's radius/detail picker + append/import flow — see
 * `MapLibreOfflineRepository`'s doc comment for why that couldn't carry over onto
 * vector tiles.
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

    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(
        containerColor = AdsbColors.Background,
        topBar = {
            TopAppBar(
                title = { Text("Offline maps") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = AdsbColors.TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AdsbColors.Background, titleContentColor = AdsbColors.TextPrimary),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(AdsbDimens.ScreenGutter)) {
            if (!state.wifiReady) {
                NetworkBanner(state.networkState)
                Spacer(Modifier.height(12.dp))
            }

            Surface(
                color = AdsbColors.Surface,
                shape = RoundedCornerShape(AdsbDimens.CardCornerRadius),
                border = BorderStroke(1.dp, AdsbColors.Outline),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(AdsbDimens.CardPadding)) {
                    Text(
                        "Download area around current position",
                        style = MaterialTheme.typography.bodyLarge, color = AdsbColors.TextPrimary,
                    )
                    Text(
                        "${OfflineMapsViewModel.RADIUS_NM.toInt()} nm radius, using the currently selected base map. " +
                            "Downloads only over unmetered Wi-Fi.",
                        style = MaterialTheme.typography.labelSmall, color = AdsbColors.TextSecondary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    if (state.isDownloading) {
                        val p = state.downloadProgress
                        val fraction = if (p != null && p.required > 0) (p.completed.toFloat() / p.required) else 0f
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth(),
                            color = AdsbColors.Primary,
                            trackColor = AdsbColors.SurfaceElevated,
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "${p?.completed ?: 0} / ${p?.required ?: 0} resources",
                                style = MaterialTheme.typography.labelSmall, color = AdsbColors.TextSecondary,
                            )
                            TextButton(onClick = { viewModel.cancelDownload() }) {
                                Text("Cancel", color = AdsbColors.Error)
                            }
                        }
                    } else {
                        Button(
                            onClick = { viewModel.download(observerLat, observerLon) },
                            enabled = state.wifiReady,
                            colors = ButtonDefaults.buttonColors(containerColor = AdsbColors.Primary, contentColor = AdsbColors.OnPrimary),
                        ) { Text("Download") }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "DOWNLOADED AREAS",
                fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.W600,
                letterSpacing = 1.2.sp, color = AdsbColors.TextSecondary,
            )
            Spacer(Modifier.height(8.dp))

            if (state.regions.isEmpty()) {
                Text(
                    "No offline areas downloaded yet.",
                    style = MaterialTheme.typography.bodyMedium, color = AdsbColors.TextSecondary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.regions, key = { it.id }) { region ->
                        RegionRow(region, onDelete = { viewModel.delete(region.id) })
                    }
                }
            }
        }
    }

    state.message?.let { msg ->
        AlertDialog(
            onDismissRequest = viewModel::clearMessage,
            title = { Text("Offline maps") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = viewModel::clearMessage) { Text("OK") } },
        )
    }
}

@Composable
private fun NetworkBanner(state: NetworkState) {
    Surface(
        color = AdsbColors.WarningFill,
        shape = RoundedCornerShape(AdsbDimens.CardCornerRadius),
        border = BorderStroke(1.dp, AdsbColors.Warning.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            when (state) {
                NetworkState.CELLULAR -> "Connect to unmetered Wi-Fi to download offline maps."
                NetworkState.WIFI_METERED -> "This Wi-Fi is metered — offline downloads need unmetered Wi-Fi."
                NetworkState.DISCONNECTED -> "No network connection."
                else -> "Offline downloads need unmetered Wi-Fi."
            },
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall, color = AdsbColors.Warning,
        )
    }
}

@Composable
private fun RegionRow(region: SavedRegion, onDelete: () -> Unit) {
    Surface(
        color = AdsbColors.Surface,
        shape = RoundedCornerShape(AdsbDimens.CardCornerRadius),
        border = BorderStroke(1.dp, AdsbColors.Outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(region.meta.name, style = MaterialTheme.typography.bodyLarge, color = AdsbColors.TextPrimary)
                Text(
                    dateFormat.format(Date(region.meta.createdAtMs)),
                    style = MaterialTheme.typography.labelSmall, color = AdsbColors.TextSecondary,
                )
            }
            Icon(
                Icons.Filled.Delete,
                contentDescription = "Delete ${region.meta.name}",
                tint = AdsbColors.TextSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onDelete)
                    .padding(6.dp),
            )
        }
    }
}
