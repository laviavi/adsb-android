package com.laviavi.adsbandroid.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.FileProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.laviavi.adsbandroid.capture.UsbHotplugReceiver
import com.laviavi.adsbandroid.pipeline.PipelineService
import com.laviavi.adsbandroid.ui.components.StatusStrip
import com.laviavi.adsbandroid.ui.detail.AircraftDetailSheet
import com.laviavi.adsbandroid.ui.logs.LogsScreen
import com.laviavi.adsbandroid.ui.map.MapScreen
import com.laviavi.adsbandroid.ui.navigation.AdsbDestination
import com.laviavi.adsbandroid.ui.offline.OfflineMapsScreen
import com.laviavi.adsbandroid.ui.receiver.ReceiverScreen
import com.laviavi.adsbandroid.ui.settings.SettingsScreen
import com.laviavi.adsbandroid.ui.text.TrafficScreen
import com.laviavi.adsbandroid.ui.theme.AdsbColors
import com.laviavi.adsbandroid.ui.theme.AdsbTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private var pipelineService: PipelineService? = null
    private var isBound = false

    private val connection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val service = (binder as PipelineService.LocalBinder).getService()
            pipelineService = service
            isBound = true
            viewModel.onServiceConnected(true)
            // stopSelf() alone doesn't destroy a service still bound to this Activity
            // (bound for the Activity's whole lifetime, not just while foregrounded) —
            // see PipelineService.shutdownRequested's doc comment. Without unbinding
            // here, the idle-source watchdog's stop would leave the service resident
            // but inert instead of actually going away.
            lifecycleScope.launch {
                service.shutdownRequested.collect { requested ->
                    if (requested && isBound) {
                        unbindService(connection)
                        isBound = false
                        pipelineService = null
                        viewModel.onServiceConnected(false)
                    }
                }
            }
            lifecycleScope.launch { service.aircraft.collect { viewModel.onAircraftUpdate(it) } }
            lifecycleScope.launch { service.stats.stats.collect { viewModel.onStatsUpdate(it) } }
            lifecycleScope.launch { service.sourceState.collect { viewModel.onSourceState(it) } }
            lifecycleScope.launch { service.config.collect { viewModel.onConfigUpdate(it) } }
            lifecycleScope.launch { service.resolvedObserverPosition.collect { viewModel.onObserverPosition(it) } }
            lifecycleScope.launch { service.gainOptions.collect { viewModel.onGainOptions(it) } }
            lifecycleScope.launch { service.history.collect { viewModel.onHistoryUpdate(it) } }
            lifecycleScope.launch { service.visits.collect { viewModel.onVisitsUpdate(it) } }
            lifecycleScope.launch { service.droppedBatches.collect { viewModel.onDroppedBatches(it) } }
            lifecycleScope.launch { service.coverage.collect { viewModel.onCoverage(it) } }
            lifecycleScope.launch { service.allTimeCoverage.collect { viewModel.onAllTimeCoverage(it) } }
            lifecycleScope.launch { service.bestRangeEver.collect { viewModel.onBestRangeEver(it) } }
            lifecycleScope.launch { service.sessionMaxRangeNm.collect { viewModel.onSessionMaxRange(it) } }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            pipelineService = null
            isBound = false
            viewModel.onServiceConnected(false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = Intent(this, PipelineService::class.java)
        startForegroundService(intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)

        setContent {
            AdsbTheme {
                AdsbScaffold(
                    viewModel = viewModel,
                    onConfigChange = { newConfig ->
                        pipelineService?.updateConfig(newConfig)
                        viewModel.onConfigUpdate(newConfig)
                    },
                    onStart = { pipelineService?.startPipeline() },
                    onStop = { pipelineService?.stopPipeline() },
                    onReconnect = { pipelineService?.reconnect() },
                    onClearHistory = { pipelineService?.clearHistory() },
                    onShareHistory = {
                        pipelineService?.exportHistoryCsv { file ->
                            if (file != null) shareCsv(this@MainActivity, file)
                        }
                    },
                    onShareEventLog = {
                        pipelineService?.exportEventLogCsv { file ->
                            if (file != null) shareCsv(this@MainActivity, file, "Share enrichment log")
                        }
                    },
                    onResetCounters = { pipelineService?.resetStatsCounters() },
                    onUpdateGps = { onResult -> pipelineService?.refreshGpsCoordinates(onResult) ?: onResult(false) },
                    onRetryEnrichment = { icao -> pipelineService?.retryEnrichment(icao) },
                    onLoadEventLog = { icao -> pipelineService?.eventLogFor(icao) ?: emptyList() },
                    driverInstalled = UsbHotplugReceiver.isDriverInstalled(this@MainActivity),
                    onExit = {
                        // exit() blocks (bounded) until the service has actually released
                        // its sockets/clients, so it's safe to unbind and finish right
                        // after it returns rather than racing the async cleanup.
                        pipelineService?.exit()
                        if (isBound) {
                            unbindService(connection)
                            isBound = false
                        }
                        pipelineService = null
                        finishAffinity()
                        android.os.Process.killProcess(android.os.Process.myPid())
                    },
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }
}

/** Sub-screen route, reached from Settings rather than the navigation bar. */
private const val ROUTE_OFFLINE_MAPS = "offline_maps"

/** Opens the system share sheet for a CSV written under external app storage. */
private fun shareCsv(context: android.content.Context, file: java.io.File, chooserTitle: String = "Share history") {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, chooserTitle))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdsbScaffold(
    viewModel: MainViewModel,
    onConfigChange: (com.laviavi.adsbandroid.pipeline.AppConfig) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onReconnect: () -> Unit,
    onClearHistory: () -> Unit,
    onShareHistory: () -> Unit,
    onShareEventLog: () -> Unit,
    onResetCounters: () -> Unit,
    onUpdateGps: (onResult: (Boolean) -> Unit) -> Unit,
    onRetryEnrichment: (String) -> Unit,
    onLoadEventLog: suspend (String) -> List<com.laviavi.adsbandroid.data.AircraftEventLogEntity>,
    driverInstalled: Boolean,
    onExit: () -> Unit,
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val receiverStatus by viewModel.receiverStatus.collectAsStateWithLifecycle()
    val selectedIcao by viewModel.selectedDetail.collectAsStateWithLifecycle()
    val selectedAircraft by viewModel.selectedAircraft.collectAsStateWithLifecycle()

    // Detail sheet state
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    // Requests ACCESS_FINE_LOCATION automatically on every app start, then triggers
    // a fresh GPS fix once granted. Previously this was only ever requested from
    // Settings' "Follow GPS" toggle — a user who stayed on Fixed mode (the default)
    // never saw the prompt, so PipelineService's own "fresh GPS fix on every app
    // start, independent of Fixed/Follow-GPS mode" logic (refreshGpsCoordinates)
    // silently no-opped forever for lack of permission. Re-requesting an
    // already-granted permission is a no-op that fires the callback immediately
    // with no dialog shown, so this is safe to run on every launch.
    val locationPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) onUpdateGps {}
        }
    LaunchedEffect(Unit) {
        locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    // POST_NOTIFICATIONS is declared in the manifest but, on API 33+, is a runtime
    // permission like the location one above — without requesting it, every
    // notification this app posts (the ongoing receiver status one included) is
    // silently dropped with no visible sign to the user that it even tried.
    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            AdsbDestination.entries.forEach { dest ->
                item(
                    icon = { Icon(dest.icon, contentDescription = dest.label) },
                    label = { Text(dest.label) },
                    selected = currentRoute == dest.route,
                    onClick = {
                        navController.navigate(dest.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        },
        containerColor = AdsbColors.NavBar,
        contentColor = AdsbColors.TextPrimary,
        navigationSuiteColors = NavigationSuiteDefaults.colors(
            navigationBarContainerColor = AdsbColors.Surface,
            navigationBarContentColor = AdsbColors.TextSecondary,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(AdsbColors.Background)
                .statusBarsPadding(),
        ) {
            StatusStrip(
                status = receiverStatus,
                onNavigateToReceiver = {
                    navController.navigate(AdsbDestination.RECEIVER.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
            )

            NavHost(
                navController = navController,
                startDestination = AdsbDestination.LIVE.route,
                modifier = Modifier.weight(1f),
            ) {
                composable(AdsbDestination.LIVE.route) {
                    TrafficScreen(
                        viewModel = viewModel,
                        onAircraftClick = { icao ->
                            viewModel.selectAircraft(icao)
                        },
                        onNavigateToReceiver = {
                            navController.navigate(AdsbDestination.RECEIVER.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        onShowOnMap = { icao ->
                            viewModel.selectAircraft(icao)
                            navController.navigate(AdsbDestination.MAP.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        onConfigChange = onConfigChange,
                        onStart = onStart,
                        onStop = onStop,
                        onReconnect = onReconnect,
                        onResetCounters = onResetCounters,
                        onClearHistory = onClearHistory,
                        onShareHistory = onShareHistory,
                        onShareEventLog = onShareEventLog,
                        onExit = onExit,
                    )
                }
                composable(AdsbDestination.MAP.route) {
                    MapScreen(
                        viewModel = viewModel,
                        onAircraftClick = { icao -> viewModel.selectAircraft(icao) },
                        onConfigChange = onConfigChange,
                    )
                }
                composable(AdsbDestination.RECEIVER.route) {
                    ReceiverScreen(
                        viewModel = viewModel,
                        onConfigChange = onConfigChange,
                        onStart = onStart,
                        onReconnect = onReconnect,
                        onStop = onStop,
                    )
                }
                composable(AdsbDestination.LOGS.route) {
                    LogsScreen(viewModel = viewModel)
                }
                composable(AdsbDestination.SETTINGS.route) {
                    SettingsScreen(
                        viewModel = viewModel,
                        driverInstalled = driverInstalled,
                        onConfigChange = onConfigChange,
                        onOpenOfflineMaps = { navController.navigate(ROUTE_OFFLINE_MAPS) },
                        onUpdateGps = onUpdateGps,
                        onRequestLocationPermission = {
                            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                        },
                        onBack = {
                            navController.navigate(AdsbDestination.LIVE.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        onExit = onExit,
                    )
                }
                // A sub-screen of Settings, deliberately not an AdsbDestination entry —
                // those drive the navigation bar, and offline maps is not a top-level
                // place the operator switches to while watching traffic.
                composable(ROUTE_OFFLINE_MAPS) {
                    val config by viewModel.config.collectAsState()
                    OfflineMapsScreen(
                        observerLat = config.observerLatitude,
                        observerLon = config.observerLongitude,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }

        // Detail bottom sheet
        if (selectedIcao != null && selectedAircraft != null) {
            ModalBottomSheet(
                onDismissRequest = { viewModel.selectAircraft(null) },
                sheetState = sheetState,
                containerColor = AdsbColors.Surface,
                contentColor = AdsbColors.TextPrimary,
                dragHandle = {
                    Box(
                        modifier = Modifier
                            .padding(vertical = 8.dp)
                            .size(width = 34.dp, height = 4.dp)
                            .background(
                                AdsbColors.Outline,
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(2.dp),
                            ),
                    )
                },
            ) {
                AircraftDetailSheet(
                    aircraft = selectedAircraft!!,
                    onDismiss = { viewModel.selectAircraft(null) },
                    onRetryEnrichment = onRetryEnrichment,
                    onLoadEventLog = onLoadEventLog,
                )
            }
        }
    }
}
