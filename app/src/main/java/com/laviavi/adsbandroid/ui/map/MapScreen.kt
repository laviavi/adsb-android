package com.laviavi.adsbandroid.ui.map

import android.view.MotionEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.laviavi.adsbandroid.map.BaseMap
import com.laviavi.adsbandroid.pipeline.AppConfig
import com.laviavi.adsbandroid.ui.MainViewModel
import com.laviavi.adsbandroid.ui.model.MapMarker
import com.laviavi.adsbandroid.ui.settings.OptionRow
import com.laviavi.adsbandroid.ui.settings.SettingsField
import com.laviavi.adsbandroid.ui.theme.AdsbColors
import com.laviavi.adsbandroid.ui.theme.AdsbDimens
import com.laviavi.adsbandroid.units.DistanceUnit
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

/**
 * Builds a live tile source from a `{z}/{x}/{y}`-style template — the same
 * placeholder convention `offline/OsmTileDownloader.kt` uses for downloads,
 * adapted here to osmdroid's own tile-loading interface since the base map can
 * change while the screen is open (unlike `TileSourceFactory.MAPNIK`, a fixed
 * singleton with no template to swap).
 */
private fun buildTileSource(name: String, urlTemplate: String): ITileSource =
    object : OnlineTileSourceBase(name, 0, 19, 256, "", arrayOf(urlTemplate)) {
        override fun getTileURLString(pMapTileIndex: Long): String = urlTemplate
            .replace("{z}", MapTileIndex.getZoom(pMapTileIndex).toString())
            .replace("{x}", MapTileIndex.getX(pMapTileIndex).toString())
            .replace("{y}", MapTileIndex.getY(pMapTileIndex).toString())
    }

private fun buildTileSource(baseMap: BaseMap): ITileSource = buildTileSource(baseMap.name, baseMap.urlTemplate)

/** The transparent labels/boundaries overlay for [BaseMap.labelUrlTemplate], or null when the base map has none. */
private fun buildLabelOverlay(context: android.content.Context, baseMap: BaseMap): org.osmdroid.views.overlay.TilesOverlay? {
    val template = baseMap.labelUrlTemplate ?: return null
    val source = buildTileSource("${baseMap.name}Labels", template)
    val provider = org.osmdroid.tileprovider.MapTileProviderBasic(context, source)
    return org.osmdroid.views.overlay.TilesOverlay(provider, context).apply {
        loadingBackgroundColor = android.graphics.Color.TRANSPARENT
        loadingLineColor = android.graphics.Color.TRANSPARENT
    }
}

/**
 * Zoom steps a named range scale rather than free zoom levels: the operator thinks
 * in "how far can I see", not in tile zoom. Each step names the two rings drawn.
 */
enum class RangeStep(val innerMi: Double, val outerMi: Double) {
    R3_6(3.0, 6.0),
    R6_12(6.0, 12.0),
    R12_25(12.0, 25.0),
    R25_50(25.0, 50.0),
    R50_100(50.0, 100.0),
    R100_250(100.0, 250.0);

    /** The scale is named in statute miles; the overlay works in nautical miles. */
    val innerNm: Double get() = DistanceUnit.MILES.toNm(innerMi)
    val outerNm: Double get() = DistanceUnit.MILES.toNm(outerMi)

    companion object {
        val DEFAULT = R25_50
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    viewModel: MainViewModel,
    onAircraftClick: (String) -> Unit,
    onConfigChange: (AppConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val markers by viewModel.mapMarkers.collectAsStateWithLifecycle()
    val config by viewModel.config.collectAsStateWithLifecycle()
    val observerPosition by viewModel.observerPosition.collectAsStateWithLifecycle()

    val configuration = LocalConfiguration.current
    val minDimPx = minOf(configuration.screenWidthDp, configuration.screenHeightDp) * density.toDouble()

    var rangeStep by rememberSaveable { mutableStateOf(RangeStep.DEFAULT) }
    var followObserver by rememberSaveable { mutableStateOf(true) }
    var layersOpen by remember { mutableStateOf(false) }
    var selectedIcao by rememberSaveable { mutableStateOf<String?>(null) }
    var shownOfTotal by remember { mutableStateOf(0 to 0) }

    val observer = remember(observerPosition) {
        GeoPoint(observerPosition.first, observerPosition.second)
    }
    val selected = remember(markers, selectedIcao) {
        selectedIcao?.let { id -> markers.find { it.icao == id } }
    }

    // One MapView for the lifetime of the destination; osmdroid is configured once
    // with an app-private tile cache so no storage permission is ever needed.
    val mapView = remember {
        // load() must run before anything touches the tile provider — without it
        // osmdroid has no cache path and no user agent, and every tile request is
        // dropped, leaving a blank map with no error.
        Configuration.getInstance().apply {
            load(context, context.getSharedPreferences("osmdroid", android.content.Context.MODE_PRIVATE))
            userAgentValue = context.packageName
            osmdroidBasePath = context.filesDir
            osmdroidTileCache = java.io.File(context.filesDir, "osmdroid/tiles").apply { mkdirs() }
        }
        MapView(context).apply {
            setTileSource(buildTileSource(config.mapBaseMap))
            setMultiTouchControls(true)
            // Real value applied by the offlineMode effect below; this only sets the
            // state before the first draw.
            setUseDataConnection(!config.offlineMode)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(computeZoom(RangeStep.DEFAULT.outerNm, minDimPx, observer.latitude))
            controller.setCenter(observer)
            // The console is a dark instrument; OSM's vector style ships light, so
            // inverting and desaturating keeps roads/coastlines legible without
            // competing with the marker palette. Esri's imagery is real satellite
            // photography — inverting it would turn real-world colors to nonsense,
            // so only OSM gets the filter.
            overlayManager.tilesOverlay.setColorFilter(tileFilterFor(config.mapBaseMap))
        }
    }
    val overlay = remember { AircraftOverlay(density) }
    var labelOverlay by remember { mutableStateOf<org.osmdroid.views.overlay.TilesOverlay?>(null) }

    DisposableEffect(Unit) {
        onDispose { mapView.onDetach() }
    }

    // Offline mode stops tile downloads; already-cached tiles still render, so the
    // map degrades to whatever has been visited rather than going blank. Applied in
    // its own effect because the MapView is remembered for the destination's whole
    // lifetime — the constructor above runs once and would never see a later toggle.
    LaunchedEffect(config.offlineMode) {
        mapView.setUseDataConnection(!config.offlineMode)
        mapView.invalidate()
    }

    // Base map can change in Settings while this screen is open — same "remembered
    // for the destination's whole lifetime" reasoning as the offlineMode effect above.
    LaunchedEffect(config.mapBaseMap) {
        mapView.setTileSource(buildTileSource(config.mapBaseMap))
        mapView.overlayManager.tilesOverlay.setColorFilter(tileFilterFor(config.mapBaseMap))
        // Esri's imagery carries no place names — its labels overlay is a second,
        // transparent tile layer drawn on top; inserted at index 0 so it always sits
        // below the aircraft markers, added later in a separate effect below.
        labelOverlay?.let { mapView.overlays.remove(it) }
        labelOverlay = buildLabelOverlay(context, config.mapBaseMap)?.also { mapView.overlays.add(0, it) }
        mapView.invalidate()
    }

    // Touch overlay: a tap selects the nearest marker, a drag disables following.
    DisposableEffect(mapView, overlay) {
        val touch = object : Overlay() {
            override fun onSingleTapConfirmed(e: MotionEvent, m: MapView): Boolean {
                val hit = overlay.hitTest(m, e.x, e.y)
                // Re-tapping the selected marker is a no-op, so panning with a
                // selection active can never deselect by accident.
                if (hit != null) {
                    if (hit.icao != selectedIcao) selectedIcao = hit.icao
                } else {
                    selectedIcao = null
                }
                m.invalidate()
                return true
            }

            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float, m: MapView,
            ): Boolean {
                if (followObserver) followObserver = false
                return false
            }
        }
        mapView.overlays.add(overlay)
        mapView.overlays.add(touch)
        onDispose {
            mapView.overlays.remove(touch)
            mapView.overlays.remove(overlay)
        }
    }

    // Marker updates are a field assignment plus invalidate — never an overlay rebuild.
    LaunchedEffect(markers, selectedIcao, config, rangeStep, observer) {
        overlay.markers = markers
        overlay.observer = observer
        overlay.selectedIcao = selectedIcao
        overlay.showRangeRings = config.mapShowRangeRings
        overlay.showLabels = config.mapShowLabels
        overlay.showGroundTraffic = config.mapShowGroundTraffic
        val ringsNm = config.mapRingRadiiMi.sorted().map { DistanceUnit.MILES.toNm(it.toDouble()) }
        overlay.ringRadiiNm = ringsNm
        overlay.ringLabels = ringsNm.map { config.distanceUnit.formatWhole(it) }
        overlay.ringColorArgb = config.mapRingColor.color.toArgb()
        overlay.ringWidthDp = config.mapRingWidth.dp
        overlay.ringLineStyle = config.mapRingLineStyle
        overlay.onDecimated = { shown, total -> shownOfTotal = shown to total }
        mapView.invalidate()
    }

    LaunchedEffect(followObserver, observer, rangeStep) {
        if (followObserver) mapView.controller.animateTo(observer)
    }
    LaunchedEffect(rangeStep) {
        mapView.controller.zoomTo(computeZoom(rangeStep.outerNm, minDimPx, observer.latitude), 300L)
    }

    Box(modifier = modifier.fillMaxSize().background(AdsbColors.Background)) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        MarkerLegend(modifier = Modifier.align(Alignment.TopStart).padding(AdsbDimens.SpacingMd))

        // The decimation cap is never silent.
        if (shownOfTotal.first < shownOfTotal.second) {
            DecimationChip(
                shown = shownOfTotal.first,
                total = shownOfTotal.second,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 56.dp),
            )
        }

        MapControls(
            followActive = followObserver,
            layersActive = layersOpen,
            canZoomIn = rangeStep.ordinal > 0,
            canZoomOut = rangeStep.ordinal < RangeStep.entries.lastIndex,
            onFollow = {
                followObserver = true
                mapView.controller.animateTo(observer)
                // "Show my location" zooms to fit the largest configured ring, not
                // whatever the +/- stepper happens to be on — the rings can go out
                // to 250 mi, well past the stepper's fixed range steps.
                config.mapRingRadiiMi.maxOrNull()?.let { largestMi ->
                    val nm = DistanceUnit.MILES.toNm(largestMi.toDouble())
                    mapView.controller.zoomTo(computeZoom(nm, minDimPx, observer.latitude), 300L)
                }
            },
            onLayers = { layersOpen = !layersOpen },
            onZoomIn = { if (rangeStep.ordinal > 0) rangeStep = RangeStep.entries[rangeStep.ordinal - 1] },
            onZoomOut = {
                if (rangeStep.ordinal < RangeStep.entries.lastIndex)
                    rangeStep = RangeStep.entries[rangeStep.ordinal + 1]
            },
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = AdsbDimens.SpacingMd, bottom = 96.dp),
        )

        if (layersOpen) {
            LayersPanel(
                config = config,
                rangeLabel = config.distanceUnit.formatWhole(rangeStep.innerNm),
                onConfigChange = onConfigChange,
                onClose = { layersOpen = false },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 68.dp, bottom = 96.dp),
            )
        }

        selected?.let { m ->
            SelectionSheet(
                marker = m,
                onExpand = { onAircraftClick(m.icao) },
                onDismiss = { selectedIcao = null; mapView.invalidate() },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/**
 * Inverts tile luminance and drops saturation, turning the light Mapnik raster
 * into a dark basemap that sits behind the marker palette instead of fighting it.
 */
private fun darkTileFilter(): android.graphics.ColorMatrixColorFilter {
    val invert = android.graphics.ColorMatrix(
        floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        )
    )
    // Desaturate *before* inverting. The other order inverts full-strength hues
    // first, which turns the blue ocean brown and the parkland magenta; flattening
    // to near-grey first means inversion only moves luminance.
    val desaturate = android.graphics.ColorMatrix().apply { setSaturation(0f) }
    desaturate.postConcat(invert)
    return android.graphics.ColorMatrixColorFilter(desaturate)
}

/** Identity matrix — a real, non-null filter so a switch away from OSM definitely clears the invert rather than leaving it in place. */
private val identityTileFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix())

private fun tileFilterFor(baseMap: BaseMap): android.graphics.ColorMatrixColorFilter =
    if (baseMap == BaseMap.OSM) darkTileFilter() else identityTileFilter

/**
 * Tile zoom at which the outer ring spans ~80 % of the shorter screen edge.
 *
 * Works in physical pixels, not dp: osmdroid's ground resolution is metres per
 * *pixel*, so feeding it dp on a 2.75x-density screen zooms out by more than a
 * full level and the rings shrink to a dot.
 */
private fun computeZoom(outerNm: Double, minDimPx: Double, latitude: Double): Double {
    val targetPx = minDimPx * 0.8
    val metres = outerNm * 1852.0 * 2
    val metresPerPixel = metres / targetPx
    val equatorial = 156543.03392 * kotlin.math.cos(Math.toRadians(latitude))
    return (Math.log(equatorial / metresPerPixel) / Math.log(2.0)).coerceIn(3.0, 18.0)
}

@Composable
private fun MarkerLegend(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = AdsbColors.Surface.copy(alpha = 0.9f),
        shape = RoundedCornerShape(AdsbDimens.PillCornerRadius),
        border = BorderStroke(1.dp, AdsbColors.Outline),
    ) {
        Text(
            "✈ airborne · ■ ground · ● no track",
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            fontSize = 11.sp,
            color = AdsbColors.TextSecondary,
            maxLines = 1,
        )
    }
}

@Composable
private fun DecimationChip(shown: Int, total: Int, modifier: Modifier = Modifier) {
    var explain by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier.clickable { explain = true },
        color = AdsbColors.WarningFill,
        shape = RoundedCornerShape(AdsbDimens.PillCornerRadius),
        border = BorderStroke(1.dp, AdsbColors.Warning.copy(alpha = 0.5f)),
    ) {
        Text(
            "showing $shown of $total",
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = AdsbColors.Warning,
        )
    }
    if (explain) {
        AlertDialog(
            onDismissRequest = { explain = false },
            title = { Text("Showing $shown of $total aircrafts") },
            text = {
                Text(
                    "Above ${AircraftOverlay.MAX_DRAWN_MARKERS} markers the map draws an evenly " +
                        "spaced subset so panning stays smooth. Every aircraft is still tracked and " +
                        "listed on the Live screen — only the drawing is reduced. Zoom in, or turn " +
                        "off ground traffic, to see individual markers again."
                )
            },
            confirmButton = { TextButton(onClick = { explain = false }) { Text("OK") } },
        )
    }
}

@Composable
private fun MapControls(
    followActive: Boolean,
    layersActive: Boolean,
    canZoomIn: Boolean,
    canZoomOut: Boolean,
    onFollow: () -> Unit,
    onLayers: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Width is pinned to the button size: without it the Column stretches to the
    // full parent width and the stack lands against the opposite edge.
    Column(
        modifier = modifier.width(44.dp),
        verticalArrangement = Arrangement.spacedBy(AdsbDimens.SpacingSm),
    ) {
        ControlButton(Icons.Outlined.MyLocation, "Follow observer", followActive, true, onFollow)
        ControlButton(Icons.Outlined.Layers, "Layers", layersActive, true, onLayers)
        // Joined pair: each end disables at its range limit.
        Column(
            modifier = Modifier
                .width(44.dp)
                .clip(RoundedCornerShape(AdsbDimens.CardCornerRadius))
                .border(1.dp, AdsbColors.Outline, RoundedCornerShape(AdsbDimens.CardCornerRadius)),
        ) {
            ControlButton(Icons.Outlined.Add, "Zoom in", false, canZoomIn, onZoomIn, bordered = false)
            HorizontalDivider(color = AdsbColors.Outline)
            ControlButton(Icons.Outlined.Remove, "Zoom out", false, canZoomOut, onZoomOut, bordered = false)
        }
    }
}

@Composable
private fun ControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    bordered: Boolean = true,
) {
    val shape = RoundedCornerShape(AdsbDimens.CardCornerRadius)
    Box(
        modifier = Modifier
            .size(44.dp)
            .then(if (bordered) Modifier.clip(shape).border(1.dp, AdsbColors.Outline, shape) else Modifier)
            .background(AdsbColors.Surface.copy(alpha = 0.95f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = when {
                !enabled -> AdsbColors.TextDisabled
                active -> AdsbColors.Primary
                else -> AdsbColors.TextSecondary
            },
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun LayersPanel(
    config: AppConfig,
    rangeLabel: String,
    onConfigChange: (AppConfig) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.width(230.dp).heightIn(max = 420.dp),
        color = AdsbColors.Surface.copy(alpha = 0.97f),
        shape = RoundedCornerShape(AdsbDimens.CardCornerRadius),
        border = BorderStroke(1.dp, AdsbColors.Outline),
    ) {
        Column(
            modifier = Modifier
                .padding(AdsbDimens.CardPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "LAYERS",
                    fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.W600,
                    letterSpacing = 1.4.sp, color = AdsbColors.Primary,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close layers",
                    tint = AdsbColors.TextSecondary,
                    modifier = Modifier.size(16.dp).clickable(onClick = onClose),
                )
            }
            Spacer(Modifier.height(AdsbDimens.SpacingSm))
            CheckRow("Range rings", config.mapShowRangeRings) {
                onConfigChange(config.copy(mapShowRangeRings = it))
            }
            CheckRow("Callsign labels", config.mapShowLabels) {
                onConfigChange(config.copy(mapShowLabels = it))
            }
            CheckRow("Ground traffic", config.mapShowGroundTraffic) {
                onConfigChange(config.copy(mapShowGroundTraffic = it))
            }

            Spacer(Modifier.height(AdsbDimens.SpacingSm))
            SectionLabel("TRAILS")
            PillRow(
                options = AppConfig.TRAIL_LENGTHS,
                selected = config.mapTrailLength,
                labelFor = { if (it == 0) "off" else "$it" },
                onSelect = { onConfigChange(config.copy(mapTrailLength = it)) },
            )

            HorizontalDivider(color = AdsbColors.SurfaceElevated, modifier = Modifier.padding(vertical = 8.dp))
            Text(
                "Range $rangeLabel",
                fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = AdsbColors.TextDisabled,
            )

            HorizontalDivider(color = AdsbColors.SurfaceElevated, modifier = Modifier.padding(vertical = 8.dp))
            SectionLabel("RANGE RINGS (MI)")
            config.mapRingRadiiMi.forEachIndexed { index, mi ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    SettingsField(
                        value = mi.toString(),
                        label = "Ring ${index + 1}",
                        modifier = Modifier.weight(1f),
                    ) { text ->
                        text.toIntOrNull()?.coerceIn(1, AppConfig.MAX_MAP_RING_MI)?.let { v ->
                            onConfigChange(config.copy(mapRingRadiiMi = config.mapRingRadiiMi.toMutableList().apply { set(index, v) }))
                        }
                    }
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Remove ring ${index + 1}",
                        tint = AdsbColors.TextSecondary,
                        modifier = Modifier.size(16.dp).clickable {
                            onConfigChange(config.copy(mapRingRadiiMi = config.mapRingRadiiMi.toMutableList().apply { removeAt(index) }))
                        },
                    )
                }
            }
            if (config.mapRingRadiiMi.size < AppConfig.MAX_MAP_RINGS) {
                TextButton(onClick = {
                    val next = ((config.mapRingRadiiMi.maxOrNull() ?: 0) + 10).coerceIn(1, AppConfig.MAX_MAP_RING_MI)
                    onConfigChange(config.copy(mapRingRadiiMi = config.mapRingRadiiMi + next))
                }) { Text("+ Add ring", color = AdsbColors.Primary) }
            }

            Spacer(Modifier.height(AdsbDimens.SpacingSm))
            SectionLabel("RING COLOR")
            ColorSwatchRow(selected = config.mapRingColor) {
                onConfigChange(config.copy(mapRingColor = it))
            }

            Spacer(Modifier.height(AdsbDimens.SpacingSm))
            SectionLabel("RING WIDTH")
            PillRow(
                options = RingWidth.entries,
                selected = config.mapRingWidth,
                labelFor = RingWidth::label,
                onSelect = { onConfigChange(config.copy(mapRingWidth = it)) },
            )

            Spacer(Modifier.height(AdsbDimens.SpacingSm))
            SectionLabel("RING STYLE")
            PillRow(
                options = RingLineStyle.entries,
                selected = config.mapRingLineStyle,
                labelFor = RingLineStyle::label,
                onSelect = { onConfigChange(config.copy(mapRingLineStyle = it)) },
            )

            HorizontalDivider(color = AdsbColors.SurfaceElevated, modifier = Modifier.padding(vertical = 8.dp))
            SectionLabel("BASE MAP")
            Column(modifier = Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BaseMap.entries.forEach { map ->
                    OptionRow(
                        label = map.label,
                        selected = config.mapBaseMap == map,
                        onClick = { onConfigChange(config.copy(mapBaseMap = map)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontFamily = FontFamily.Monospace, fontSize = 10.sp,
        letterSpacing = 1.4.sp, color = AdsbColors.Primary)
}

/** Row of selectable pills — used for trail length, ring width, and ring line style. */
@Composable
private fun <T> PillRow(options: List<T>, selected: T, labelFor: (T) -> String, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
        options.forEach { option ->
            val isSelected = option == selected
            Text(
                labelFor(option),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = if (isSelected) AdsbColors.OnPrimary else AdsbColors.TextSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(AdsbDimens.PillCornerRadius))
                    .background(if (isSelected) AdsbColors.Primary else Color.Transparent)
                    .border(
                        1.dp,
                        if (isSelected) Color.Transparent else AdsbColors.Outline,
                        RoundedCornerShape(AdsbDimens.PillCornerRadius),
                    )
                    .clickable { onSelect(option) }
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun ColorSwatchRow(selected: RingColorPreset, onSelect: (RingColorPreset) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
        RingColorPreset.entries.forEach { preset ->
            val isSelected = preset == selected
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(preset.color)
                    .border(
                        if (isSelected) 2.dp else 1.dp,
                        if (isSelected) AdsbColors.TextPrimary else AdsbColors.Outline,
                        CircleShape,
                    )
                    .clickable { onSelect(preset) },
            )
        }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (checked) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
            contentDescription = null,
            tint = if (checked) AdsbColors.Primary else AdsbColors.TextDisabled,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 12.sp, color = AdsbColors.TextPrimary)
    }
}

/**
 * Peek detent only. Dragging up hands off to the shared aircraft-detail surface —
 * there is one detail implementation, not a second one living on the map.
 */
@Composable
private fun SelectionSheet(
    marker: MapMarker,
    onExpand: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onExpand),
        color = AdsbColors.Surface,
        shape = RoundedCornerShape(topStart = AdsbDimens.SheetCornerRadius, topEnd = AdsbDimens.SheetCornerRadius),
        border = BorderStroke(1.dp, AdsbColors.Outline),
    ) {
        Column(modifier = Modifier.padding(AdsbDimens.ScreenGutter)) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 10.dp)
                    .size(width = 34.dp, height = 4.dp)
                    .background(AdsbColors.Outline, RoundedCornerShape(2.dp)),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    marker.icao,
                    fontFamily = FontFamily.Monospace, fontSize = 16.sp, fontWeight = FontWeight.W600,
                    color = AdsbColors.Primary,
                )
                marker.callsign?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(it, fontSize = 17.sp, fontWeight = FontWeight.W600, color = AdsbColors.TextPrimary)
                }
                Spacer(Modifier.weight(1f))
                marker.distanceBearing?.let {
                    Text(it, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = AdsbColors.TextSecondary)
                }
            }
            Text(
                marker.detailLine,
                fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = AdsbColors.TextSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
            TextButton(onClick = onDismiss, contentPadding = PaddingValues(0.dp)) {
                Text("Dismiss", fontSize = 12.sp, color = AdsbColors.TextDisabled)
            }
        }
    }
}
