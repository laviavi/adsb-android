package com.laviavi.adsbandroid.ui.map

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
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView

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
    var currentZoom by remember { mutableDoubleStateOf(computeZoom(RangeStep.DEFAULT.outerNm, minDimPx, observerPosition.first)) }

    val observer = remember(observerPosition) { LatLng(observerPosition.first, observerPosition.second) }
    val selected = remember(markers, selectedIcao) {
        selectedIcao?.let { id -> markers.find { it.icao == id } }
    }

    // MapLibre requires this before any MapView is created; cheap/idempotent to call repeatedly.
    remember { MapLibre.getInstance(context) }

    val mapView = remember {
        MapView(context).apply {
            onCreate(null)
        }
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    // Rebuilt every time the style (base map) changes — a new Style instance discards
    // every previously added source/layer, so the aircraft layer can't be reused across it.
    var aircraftLayer by remember { mutableStateOf<AircraftMapLayer?>(null) }
    // The current style's own label-layer IDs (differ per OpenFreeMap style — see
    // BasemapLabelStyler) — fetched once per style switch, reused by the label
    // size/color effect below so changing just those doesn't need a re-fetch.
    var labelLayerIds by remember { mutableStateOf<List<String>>(emptyList()) }

    DisposableEffect(mapView) {
        mapView.onStart()
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            map = m
            m.uiSettings.isCompassEnabled = false
            m.uiSettings.setAttributionMargins(8, 0, 0, 8)
            m.cameraPosition = org.maplibre.android.camera.CameraPosition.Builder()
                .target(observer)
                .zoom(computeZoom(RangeStep.DEFAULT.outerNm, minDimPx, observer.latitude))
                .build()
            m.addOnCameraIdleListener { currentZoom = m.cameraPosition.zoom }
            m.addOnMapClickListener { latLng ->
                if (layersOpen) layersOpen = false
                val screenPoint = m.projection.toScreenLocation(latLng)
                val hitIcao = m.queryRenderedFeatures(screenPoint, AircraftMapLayer.LYR_AIRCRAFT_ICONS)
                    .firstOrNull()?.getStringProperty("icao")
                selectedIcao = if (hitIcao != null && hitIcao != selectedIcao) hitIcao else if (hitIcao == null) null else selectedIcao
                true
            }
            m.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE && followObserver) {
                    followObserver = false
                }
            }
        }
    }

    // Base map switch: MapLibre's setStyle() replaces the whole Style, wiping every
    // previously added source/layer — the aircraft layer is rebuilt against the new one.
    LaunchedEffect(map, config.mapBaseMap) {
        val m = map ?: return@LaunchedEffect
        val styleUrl = config.mapBaseMap.styleUrl
        m.setStyle(styleUrl) { style ->
            aircraftLayer = AircraftMapLayer(style, density)
        }
        labelLayerIds = BasemapLabelStyler.fetchLabelLayerIds(styleUrl)
        map?.style?.let { style ->
            BasemapLabelStyler.apply(style, labelLayerIds, config.mapLabelSize, config.mapLabelColor?.toHex())
        }
    }

    // Label size/color changing alone (no basemap switch) reapplies against the
    // already-fetched IDs — no need to refetch the style JSON for this.
    LaunchedEffect(map, labelLayerIds, config.mapLabelSize, config.mapLabelColor) {
        val style = map?.style ?: return@LaunchedEffect
        BasemapLabelStyler.apply(style, labelLayerIds, config.mapLabelSize, config.mapLabelColor?.toHex())
    }

    // One combined camera effect instead of two separately-keyed ones: a past bug had
    // a recenter effect and a zoom effect both firing on the same rangeStep change,
    // and the map's pan animation and zoom animation running in the same frame
    // silently swallowed one of them. Merging them here can't reintroduce that.
    LaunchedEffect(map, followObserver, observer, rangeStep) {
        val m = map ?: return@LaunchedEffect
        val target = if (followObserver) observer else m.cameraPosition.target ?: observer
        m.animateCamera(
            CameraUpdateFactory.newLatLngZoom(target, computeZoom(rangeStep.outerNm, minDimPx, target.latitude)),
            300,
        )
    }

    // Marker/ring updates are a field-level GeoJSON refresh, not a layer rebuild.
    LaunchedEffect(aircraftLayer, markers, selectedIcao, config, observer, currentZoom) {
        val layer = aircraftLayer ?: return@LaunchedEffect
        val m = map
        val result = layer.update(
            markers = markers,
            observer = observer,
            selectedIcao = selectedIcao,
            showRangeRings = config.mapShowRangeRings,
            showLabels = config.mapShowLabels,
            showGroundTraffic = config.mapShowGroundTraffic,
            ringRadiiNm = config.mapRingRadiiMi.sorted().map { DistanceUnit.MILES.toNm(it.toDouble()) },
            ringLabels = config.mapRingRadiiMi.sorted().map { config.distanceUnit.formatWhole(DistanceUnit.MILES.toNm(it.toDouble())) },
            ringColorHex = config.mapRingColor.toHex(),
            ringWidthPx = config.mapRingWidth.dp,
            ringDash = null,
            zoom = currentZoom,
        )
        shownOfTotal = result.shown to result.total
        m?.style?.let { layer.setClusterLayersVisible(result.clustered, it) }
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
                // "Show my location" zooms to fit the largest configured ring, not
                // whatever the +/- stepper happens to be on — the rings can go out
                // to 250 mi, well past the stepper's fixed range steps.
                val largestMi = config.mapRingRadiiMi.maxOrNull()
                map?.let { m ->
                    val zoom = if (largestMi != null) {
                        computeZoom(DistanceUnit.MILES.toNm(largestMi.toDouble()), minDimPx, observer.latitude)
                    } else {
                        computeZoom(rangeStep.outerNm, minDimPx, observer.latitude)
                    }
                    m.animateCamera(CameraUpdateFactory.newLatLngZoom(observer, zoom), 300)
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
                onDismiss = { selectedIcao = null },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

private fun RingColorPreset.toHex(): String = "#%06X".format(color.toArgb() and 0xFFFFFF)

/**
 * Tile zoom at which the outer ring spans ~80 % of the shorter screen edge.
 *
 * Works in physical pixels, not dp — the same web-mercator ground-resolution
 * formula osmdroid used; MapLibre's zoom levels are the same standard convention.
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
                    "Above ${AircraftMapLayer.MAX_DRAWN_MARKERS} markers the map draws an evenly " +
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

            Spacer(Modifier.height(AdsbDimens.SpacingSm))
            SectionLabel("BASEMAP LABEL SIZE")
            PillRow(
                options = MapLabelSize.entries,
                selected = config.mapLabelSize,
                labelFor = MapLabelSize::label,
                onSelect = { onConfigChange(config.copy(mapLabelSize = it)) },
            )

            Spacer(Modifier.height(AdsbDimens.SpacingSm))
            SectionLabel("BASEMAP LABEL COLOR")
            NullableColorSwatchRow(selected = config.mapLabelColor) {
                onConfigChange(config.copy(mapLabelColor = it))
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

/** Same swatch row, plus a leading "no override" option — used for basemap label color, where null means "leave the basemap's own colors alone". */
@Composable
private fun NullableColorSwatchRow(selected: RingColorPreset?, onSelect: (RingColorPreset?) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(AdsbColors.Surface)
                .border(
                    if (selected == null) 2.dp else 1.dp,
                    if (selected == null) AdsbColors.TextPrimary else AdsbColors.Outline,
                    CircleShape,
                )
                .clickable { onSelect(null) },
            contentAlignment = Alignment.Center,
        ) {
            Text("×", fontSize = 12.sp, color = AdsbColors.TextSecondary)
        }
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
