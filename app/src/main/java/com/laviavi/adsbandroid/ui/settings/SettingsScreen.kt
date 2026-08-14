package com.laviavi.adsbandroid.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.laviavi.adsbandroid.aircraft.AircraftSortOrder
import com.laviavi.adsbandroid.capture.GainOptions
import com.laviavi.adsbandroid.capture.RtlTcpGain
import com.laviavi.adsbandroid.location.ObserverMode
import com.laviavi.adsbandroid.pipeline.AppConfig
import com.laviavi.adsbandroid.pipeline.roundToGpsPrecision
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.laviavi.adsbandroid.ui.MainViewModel
import com.laviavi.adsbandroid.ui.components.ExitConfirmDialog
import com.laviavi.adsbandroid.ui.theme.AdsbColors


/** Which settings surface is showing. Tuner options live on their own page. */
private enum class SettingsPage { ROOT, TUNER }

/**
 * Dedicated full-screen settings surface with an explicit close action.
 *
 * Opening and closing this screen does not touch the receiver. With the USB
 * dongle as the only source, PPM is the single setting that forces a reconnect —
 * everything else, gain and demodulator tuning included, is applied live.
 */
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    driverInstalled: Boolean,
    onConfigChange: (AppConfig) -> Unit,
    modifier: Modifier = Modifier,
    onOpenOfflineMaps: () -> Unit = {},
    onUpdateGps: (onResult: (Boolean) -> Unit) -> Unit = { it(false) },
    onRequestLocationPermission: () -> Unit = {},
    onBack: () -> Unit = {},
    onExit: () -> Unit = {},
) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val gainOptions by viewModel.gainOptions.collectAsStateWithLifecycle()
    SettingsScreenContent(
        config, driverInstalled, gainOptions, onConfigChange,
        onOpenOfflineMaps = onOpenOfflineMaps, onUpdateGps = onUpdateGps,
        onRequestLocationPermission = onRequestLocationPermission, onBack = onBack,
        onExit = onExit, modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreenContent(
    config: AppConfig,
    driverInstalled: Boolean,
    gainOptions: GainOptions,
    onConfigChange: (AppConfig) -> Unit,
    onRequestLocationPermission: () -> Unit = {},
    onBack: () -> Unit = {},
    onOpenOfflineMaps: () -> Unit = {},
    onUpdateGps: (onResult: (Boolean) -> Unit) -> Unit = { it(false) },
    onExit: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var page by rememberSaveable { mutableStateOf(SettingsPage.ROOT) }

    // Back from a subpage returns to the settings root, not out of Settings.
    BackHandler(enabled = page != SettingsPage.ROOT) { page = SettingsPage.ROOT }

    Scaffold(
        modifier = modifier,
        containerColor = AdsbColors.Background,
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            when (page) {
                                SettingsPage.ROOT -> "Settings"
                                SettingsPage.TUNER -> "Tuner"
                            },
                            color = AdsbColors.TextPrimary,
                        )
                    },
                    navigationIcon = {
                        if (page == SettingsPage.ROOT) {
                            IconButton(onClick = onBack) {
                                Icon(Icons.Default.Close, contentDescription = "Close settings",
                                    tint = AdsbColors.TextPrimary)
                            }
                        } else {
                            IconButton(onClick = { page = SettingsPage.ROOT }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back to settings",
                                    tint = AdsbColors.TextPrimary)
                            }
                        }
                    },
                    actions = {
                        if (page == SettingsPage.ROOT) {
                            TextButton(onClick = onBack) {
                                Text("Done", color = AdsbColors.Primary,
                                    style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = AdsbColors.Surface),
                )
                HorizontalDivider(color = AdsbColors.Outline)
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(AdsbColors.Background)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            when (page) {
                SettingsPage.ROOT -> {
                    ReceiverSection(config, driverInstalled, gainOptions, { page = SettingsPage.TUNER }, onConfigChange)
                    SortSection(config, onConfigChange)
                    ObserverSection(config, onConfigChange, onRequestLocationPermission, onUpdateGps)
                    PowerSection(config, onConfigChange)
                    OfflineMapsSection(onOpenOfflineMaps)
                    DataSection(config, onConfigChange)
                    AboutSection()
                    ExitSection(onExit)
                }
                SettingsPage.TUNER -> TunerPage(config, gainOptions, onConfigChange)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ReceiverSection(
    config: AppConfig,
    driverInstalled: Boolean,
    gainOptions: GainOptions,
    onOpenTuner: () -> Unit,
    onChange: (AppConfig) -> Unit,
) {
    SettingsSection("Receiver", "USB RTL-SDR dongle over OTG.") {
        if (!driverInstalled) {
            InfoBanner("RTL-SDR driver app not installed — the dongle cannot be opened.",
                BannerTone.ERROR)
            Spacer(Modifier.height(8.dp))
        }
        NavigationRow(
            label = "Tuner",
            value = gainSummary(config),
            description = "Gain, frequency correction, demodulator thresholds",
            onClick = onOpenTuner,
        )
        Spacer(Modifier.height(8.dp))
        EditableStepperRow(
            label = "Accept rate window",
            value = config.acceptRateWindowSeconds,
            min = 5,
            max = 60,
            step = 5,
            unit = "s",
            description = "Seconds of history used to calculate the accept rate. Changing this resets the counters.",
            onValueChange = { onChange(config.copy(acceptRateWindowSeconds = it)) },
        )
        EditableStepperRow(
            label = "Low accept rate alert",
            value = config.lowAcceptRateAlertPct,
            min = 5,
            max = 50,
            step = 5,
            unit = "%",
            description = "Blink the receiver badge red when accept rate drops below this threshold.",
            onValueChange = { onChange(config.copy(lowAcceptRateAlertPct = it)) },
        )
    }
}

/**
 * Live-list sort order. First-seen is the only order that never reshuffles a
 * row while someone is reading it — every other key changes continuously in
 * flight — so it is the default and is called out explicitly here rather than
 * left to blend in with the other options.
 *
 * Lives in Settings for now, not on the Live/History screens directly (Avi's
 * decision, 2026-07-26) — a per-screen sort control can be added later without
 * changing where the order itself is stored ([AppConfig.sortOrder]).
 */
@Composable
private fun SortSection(config: AppConfig, onChange: (AppConfig) -> Unit) {
    SettingsSection(
        "Sort aircraft by",
        "First seen keeps rows in place while you're reading them — every other " +
            "order changes continuously in flight.",
    ) {
        AircraftSortOrder.entries.forEach { order ->
            OptionRow(
                label = order.label,
                description = if (order == AircraftSortOrder.FIRST_SEEN) "Default — stable order" else null,
                selected = config.sortOrder == order,
                onClick = { onChange(config.copy(sortOrder = order)) },
            )
        }
    }
}

private fun gainSummary(config: AppConfig): String = when {
    config.autoGain -> "Auto gain"
    config.gainTenths == AppConfig.GAIN_UNSET -> "Manual — unset"
    else -> RtlTcpGain.formatGain(config.gainTenths)
}

/**
 * Tuner subpage: everything that changes what the radio hears.
 *
 * Grouped away from the rest of Settings because these are the controls reached
 * for while watching the message counters, and because two of them can silence
 * reception entirely.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TunerPage(
    config: AppConfig,
    gainOptions: GainOptions,
    onChange: (AppConfig) -> Unit,
) {
    SettingsSection("Gain", "Applied immediately — no reconnect.") {
        OptionRow(
            label = "Auto gain",
            description = "Tuner AGC selects the level continuously.",
            selected = config.autoGain,
            onClick = { onChange(config.copy(autoGain = true)) },
        )
        OptionRow(
            label = "Manual gain",
            description = "Pick a level from the levels this dongle reports.",
            selected = !config.autoGain,
            onClick = { onChange(config.copy(autoGain = false)) },
        )

        if (!config.autoGain) {
            Spacer(Modifier.height(4.dp))
            when (gainOptions) {
                is GainOptions.Unavailable -> InfoBanner(gainOptions.reason, BannerTone.ERROR)
                is GainOptions.Available -> {
                    var expanded by remember { mutableStateOf(false) }
                    val selected = config.gainTenths
                        .takeIf { it != AppConfig.GAIN_UNSET && it in gainOptions.gainsTenths }
                    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                        OutlinedTextField(
                            value = selected?.let { RtlTcpGain.formatGain(it) } ?: "Select a gain level",
                            onValueChange = {},
                            readOnly = true,
                            label = {
                                Text("${gainOptions.tuner.displayName} gain",
                                    color = AdsbColors.TextSecondary)
                            },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = AdsbColors.TextPrimary,
                                unfocusedTextColor = AdsbColors.TextPrimary,
                                focusedBorderColor = AdsbColors.Primary,
                                unfocusedBorderColor = AdsbColors.Outline,
                                focusedContainerColor = AdsbColors.SurfaceElevated,
                                unfocusedContainerColor = AdsbColors.SurfaceElevated,
                            ),
                        )
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false },
                            containerColor = AdsbColors.SurfaceElevated,
                        ) {
                            gainOptions.gainsTenths.forEach { tenths ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            RtlTcpGain.formatGain(tenths),
                                            color = if (tenths == selected) AdsbColors.Primary
                                            else AdsbColors.TextPrimary,
                                        )
                                    },
                                    onClick = {
                                        expanded = false
                                        onChange(config.copy(gainTenths = tenths, autoGain = false))
                                    },
                                )
                            }
                        }
                    }
                    if (selected == null) {
                        Spacer(Modifier.height(6.dp))
                        InfoBanner(
                            "No level selected yet — the tuner keeps its current gain until you pick one.",
                            BannerTone.WARNING,
                        )
                    }
                }
            }
        }
    }

    SettingsSection("Bias tee", "Powers an inline LNA over the antenna cable. Applied immediately — no reconnect.") {
        SwitchRow(
            label = "Bias tee",
            description = "Only enable if your antenna setup requires powered LNA feed — can damage unpowered antennas.",
            checked = config.biasTee,
            onCheckedChange = { onChange(config.copy(biasTee = it)) },
        )
    }

    SettingsSection("Frequency correction") {
        // Short label: a two-line label overlaps the field's value box.
        SettingsField(config.ppmCorrection.toString(), "Correction (PPM)") {
            it.toIntOrNull()?.let { p -> onChange(config.copy(ppmCorrection = p)) }
        }
        Spacer(Modifier.height(4.dp))
        InfoBanner("Changing PPM reconnects the receiver.", BannerTone.WARNING)
    }

    SettingsSection(
        "Demodulator thresholds",
        "Applied live. These decide which candidate signals become frames — " +
            "watch the message counters while changing them.",
    ) {
        StepperRow(
            label = "Preamble gap divisor",
            value = config.preambleGapDivisor,
            min = AppConfig.GAP_DIVISOR_MIN,
            max = AppConfig.GAP_DIVISOR_MAX,
            default = AppConfig.DEFAULT_PREAMBLE_GAP_DIVISOR,
            description = "Higher accepts weaker preambles; lower is stricter.",
            onValueChange = { onChange(config.copy(preambleGapDivisor = it)) },
        )
        StepperRow(
            label = "Delta floor",
            value = config.deltaFloor,
            min = AppConfig.DELTA_FLOOR_MIN,
            max = AppConfig.DELTA_FLOOR_MAX,
            step = AppConfig.DELTA_FLOOR_STEP,
            default = AppConfig.DEFAULT_DELTA_FLOOR,
            description = "Minimum signal contrast a frame must show. Too low floods " +
                "the decoder with noise; too high silences it.",
            onValueChange = { onChange(config.copy(deltaFloor = it)) },
        )
        if (config.preambleGapDivisor != AppConfig.DEFAULT_PREAMBLE_GAP_DIVISOR ||
            config.deltaFloor != AppConfig.DEFAULT_DELTA_FLOOR
        ) {
            InfoBanner(
                "Thresholds differ from the defaults the demodulator was calibrated at.",
                BannerTone.WARNING,
            )
        }
    }
}

@Composable
private fun ObserverSection(
    config: AppConfig,
    onChange: (AppConfig) -> Unit,
    onRequestLocationPermission: () -> Unit,
    onUpdateGps: (onResult: (Boolean) -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val locationGranted = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    SettingsSection("Observer position", "Used for range, bearing and CPR position decoding.") {
        OptionRow(
            label = "Fixed location",
            description = "Use the coordinates below only.",
            selected = config.observerMode == ObserverMode.FIXED,
            onClick = { onChange(config.copy(observerMode = ObserverMode.FIXED)) },
        )
        OptionRow(
            label = "Follow GPS",
            description = "Tracks the vehicle while receiving. Throttles when stationary.",
            selected = config.observerMode == ObserverMode.FOLLOW_GPS,
            onClick = {
                onChange(config.copy(observerMode = ObserverMode.FOLLOW_GPS))
                onRequestLocationPermission()
            },
        )

        if (config.observerMode == ObserverMode.FOLLOW_GPS) {
            if (!locationGranted) {
                InfoBanner(
                    "Location permission not granted. Position stays on the fixed coordinates below.",
                    BannerTone.WARNING,
                )
            }
            Spacer(Modifier.height(4.dp))
            EditableStepperRow(
                label = "GPS refresh interval",
                value = config.gpsRefreshIntervalMinutes,
                min = 15,
                max = 360,
                step = 15,
                unit = "min",
                onValueChange = { onChange(config.copy(gpsRefreshIntervalMinutes = it)) },
            )
        }

        Spacer(Modifier.height(4.dp))
        SettingsField(
            value = "%.6f, %.6f".format(config.observerLatitude, config.observerLongitude),
            label = if (config.observerMode == ObserverMode.FOLLOW_GPS) "My location" else "Coordinates",
            placeholder = "latitude, longitude",
        ) { text ->
            val parts = text.split(",").map { it.trim() }
            val lat = parts.getOrNull(0)?.toDoubleOrNull()
            val lon = parts.getOrNull(1)?.toDoubleOrNull()
            if (lat != null && lon != null) {
                onChange(config.copy(
                    observerLatitude = lat.roundToGpsPrecision(),
                    observerLongitude = lon.roundToGpsPrecision(),
                ))
            }
        }

        Spacer(Modifier.height(4.dp))
        var updatingGps by remember { mutableStateOf(false) }
        var lastGpsResult by remember { mutableStateOf<Boolean?>(null) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                enabled = !updatingGps,
                onClick = {
                    if (!locationGranted) {
                        onRequestLocationPermission()
                        return@OutlinedButton
                    }
                    updatingGps = true
                    lastGpsResult = null
                    onUpdateGps { ok -> updatingGps = false; lastGpsResult = ok }
                },
            ) { Text(if (updatingGps) "Updating…" else "Update GPS") }
            when {
                updatingGps -> Unit
                !locationGranted -> {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Tap to grant location permission",
                        style = MaterialTheme.typography.labelSmall,
                        color = AdsbColors.Warning,
                    )
                }
                lastGpsResult != null -> {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (lastGpsResult == true) "Updated" else "No fix — check GPS signal",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (lastGpsResult == true) AdsbColors.Success else AdsbColors.Warning,
                    )
                }
            }
        }
    }
}

@Composable
private fun PowerSection(config: AppConfig, onChange: (AppConfig) -> Unit) {
    SettingsSection("Auto-stop", "Stops the receiver if the dongle is absent, to save battery.") {
        EditableStepperRow(
            label = "Auto-stop after",
            value = config.sourceWatchdogTimeoutMinutes,
            min = 1,
            max = 60,
            step = 1,
            unit = "min",
            onValueChange = { onChange(config.copy(sourceWatchdogTimeoutMinutes = it)) },
        )
    }
}

@Composable
private fun OfflineMapsSection(onOpen: () -> Unit) {
    SettingsSection("Offline maps", "Save map areas for use without a connection.") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, AdsbColors.Outline, RoundedCornerShape(8.dp))
                .clickable(onClick = onOpen)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Manage offline maps",
                style = MaterialTheme.typography.bodyMedium,
                color = AdsbColors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            Text("›", style = MaterialTheme.typography.titleMedium, color = AdsbColors.TextSecondary)
        }
    }
}

@Composable
private fun AboutSection() {
    SettingsSection("About") {
        Text(
            "ADS-B Receiver v${com.laviavi.adsbandroid.BuildConfig.VERSION_NAME} " +
                "(${com.laviavi.adsbandroid.BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.bodySmall,
            color = AdsbColors.TextSecondary,
        )
    }
}

/**
 * Fully releases the receiver (USB source, enrichment HTTP clients, GPS
 * updates, loggers) and closes the app — a stronger stop than Stop/Reconnect,
 * which leave the app running and ready to restart.
 */
@Composable
private fun ExitSection(onExit: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    SettingsSection("Exit", "Fully stops the receiver and closes the app.") {
        Button(
            onClick = { confirm = true },
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = AdsbColors.Error,
                contentColor = AdsbColors.OnPrimary,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Exit app")
        }
    }
    if (confirm) {
        ExitConfirmDialog(onConfirm = onExit, onDismiss = { confirm = false })
    }
}

@Composable
private fun DataSection(config: AppConfig, onChange: (AppConfig) -> Unit) {
    SettingsSection("Data") {
        SwitchRow(
            label = "Single-bit CRC correction",
            description = "Repairs one-bit errors in DF17/18 frames.",
            checked = config.crcCorrectSingleBit,
            onCheckedChange = { onChange(config.copy(crcCorrectSingleBit = it)) },
        )
        SwitchRow(
            label = "Two-bit CRC correction",
            description = "Also repairs two-bit errors. Independent of single-bit; " +
                "higher chance of accepting a wrongly \"corrected\" frame.",
            checked = config.crcCorrectTwoBit,
            onCheckedChange = { onChange(config.copy(crcCorrectTwoBit = it)) },
        )
        SwitchRow(
            label = "Raw message log",
            description = "Appends every frame to a daily file.",
            checked = config.rawLoggingEnabled,
            onCheckedChange = { onChange(config.copy(rawLoggingEnabled = it)) },
        )
        SwitchRow(
            label = "Offline mode",
            description = "Stops all internet use. Enrichment is suspended and the map shows " +
                "only downloaded offline areas. Decoding is unaffected — the dongle needs no network.",
            checked = config.offlineMode,
            onCheckedChange = { onChange(config.copy(offlineMode = it)) },
        )
        SwitchRow(
            label = "Network enrichment",
            description = if (config.offlineMode)
                "Suspended by Offline mode. Your choice here is kept and resumes when Offline mode is turned off."
            else
                "Fetches aircraft type, airline and route from hexdb.io, OpenSky, adsbdb and FlightAware. Requires network.",
            checked = config.enrichmentEnabled,
            enabled = !config.offlineMode,
            onCheckedChange = { onChange(config.copy(enrichmentEnabled = it)) },
        )
        SettingsField(config.aircraftExpirySeconds.toString(), "Drop aircraft after (seconds)") {
            it.toIntOrNull()?.let { v -> onChange(config.copy(aircraftExpirySeconds = v)) }
        }
    }
}
