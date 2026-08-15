package com.laviavi.adsbandroid.ui.map

import androidx.compose.ui.graphics.toArgb
import com.laviavi.adsbandroid.ui.model.MapMarker
import com.laviavi.adsbandroid.ui.theme.AdsbColors
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.expressions.Expression.eq
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.toColor
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconColor
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconOpacity
import org.maplibre.android.style.layers.PropertyFactory.iconRotate
import org.maplibre.android.style.layers.PropertyFactory.iconRotationAlignment
import org.maplibre.android.style.layers.PropertyFactory.iconSize
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.textOffset
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * MapLibre equivalent of the old Canvas-based `AircraftOverlay`: range rings, observer,
 * trails, markers, labels and clustering, now as GeoJSON sources + style layers instead
 * of a `draw()` loop. Sources/layers belong to one [org.maplibre.android.maps.Style]
 * instance — a base-map switch creates a brand new `Style`, so a new [AircraftMapLayer]
 * is built and attached each time (see `MapScreen.kt`), not reused across the switch.
 *
 * Marker visuals (shape/color/rotation/opacity) are computed per-feature in Kotlin,
 * matching the exact logic the old `drawMarker()` used, and just relayed by simple
 * `get()` expressions — deliberately avoiding complex `match`/`switchCase` style
 * expressions, which are far more failure-prone to get exactly right without a way to
 * visually verify on-device.
 */
internal class AircraftMapLayer(style: org.maplibre.android.maps.Style, density: Float) {

    private val aircraftSource = GeoJsonSource(SRC_AIRCRAFT)
    private val trailsSource = GeoJsonSource(SRC_TRAILS)
    private val ringsSource = GeoJsonSource(SRC_RINGS)
    private val ringLabelsSource = GeoJsonSource(SRC_RING_LABELS)
    private val observerSource = GeoJsonSource(SRC_OBSERVER)
    private val clusterSource = GeoJsonSource(
        SRC_CLUSTER,
        GeoJsonOptions().withCluster(true).withClusterMaxZoom(14).withClusterRadius(CLUSTER_RADIUS_PX),
    )

    init {
        MarkerIcons.all(density).forEach { (name, bmp) -> style.addImage(name, bmp, true) }

        style.addSource(ringsSource)
        style.addSource(ringLabelsSource)
        style.addSource(observerSource)
        style.addSource(trailsSource)
        style.addSource(aircraftSource)
        style.addSource(clusterSource)

        // Halo drawn first (under) the colored ring stroke, same technique the Canvas
        // version used, so a ring reads against any basemap brightness/color.
        style.addLayer(
            LineLayer(LYR_RING_HALO, SRC_RINGS).withProperties(
                lineColor("#000000"),
                lineWidth(get(PROP_HALO_WIDTH)),
                lineOpacity(get(PROP_OPACITY)),
                lineCap(Property.LINE_CAP_ROUND),
            )
        )
        style.addLayer(
            LineLayer(LYR_RING, SRC_RINGS).withProperties(
                lineColor(get(PROP_COLOR)),
                lineWidth(get(PROP_WIDTH)),
                lineOpacity(get(PROP_OPACITY)),
                lineCap(Property.LINE_CAP_ROUND),
            )
        )
        style.addLayer(
            SymbolLayer(LYR_RING_LABEL, SRC_RING_LABELS).withProperties(
                textField(get(PROP_LABEL)),
                textSize(11f),
                textColor(get(PROP_COLOR)),
                textHaloColor("#000000"),
                textHaloWidth(1.5f),
                textAllowOverlap(true),
                textIgnorePlacement(true),
                textOffset(arrayOf(0.6f, -0.6f)),
            )
        )

        style.addLayer(
            CircleLayer(LYR_OBSERVER_RING, SRC_OBSERVER).withProperties(
                circleRadius(9f),
                circleOpacity(0f),
                circleStrokeColor(AdsbColors.Primary.toHex()),
                circleStrokeWidth(2f),
            )
        )
        style.addLayer(
            CircleLayer(LYR_OBSERVER_DOT, SRC_OBSERVER).withProperties(
                circleRadius(3f),
                circleColor(AdsbColors.Primary.toHex()),
            )
        )

        style.addLayer(
            LineLayer(LYR_TRAILS, SRC_TRAILS).withProperties(
                lineColor(AdsbColors.Primary.toHex()),
                lineWidth(1.5f),
                lineOpacity(0.43f),
                lineCap(Property.LINE_CAP_ROUND),
            )
        )

        // Individual aircraft: icon + selection/alert rings + two label layers (a
        // collision-managed default, and an always-shown one for selected/RA/emergency
        // — mirrors the old grid-collision-with-bypass behaviour using native primitives).
        style.addLayer(
            CircleLayer(LYR_ALERT_RING, SRC_AIRCRAFT).withProperties(
                circleRadius(12f),
                circleOpacity(0f),
                circleStrokeColor(AdsbColors.Error.toHex()),
                circleStrokeWidth(1.5f),
            ).withFilter(eq(get(PROP_ALERT), literal(true)))
        )
        style.addLayer(
            CircleLayer(LYR_SELECTED_RING, SRC_AIRCRAFT).withProperties(
                circleRadius(14f),
                circleOpacity(0f),
                circleStrokeColor(AdsbColors.Primary.toHex()),
                circleStrokeWidth(1.5f),
            ).withFilter(eq(get(PROP_SELECTED), literal(true)))
        )
        style.addLayer(
            SymbolLayer(LYR_AIRCRAFT_ICONS, SRC_AIRCRAFT).withProperties(
                iconImage(get(PROP_SHAPE)),
                iconRotate(get(PROP_ROTATION)),
                iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                iconColor(toColor(get(PROP_COLOR))),
                iconSize(get(PROP_SIZE_MUL)),
                iconOpacity(get(PROP_OPACITY)),
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
            )
        )
        style.addLayer(
            SymbolLayer(LYR_LABELS, SRC_AIRCRAFT).withProperties(
                textField(get(PROP_LABEL)),
                textSize(10f),
                textColor(get(PROP_COLOR)),
                textHaloColor("#000000"),
                textHaloWidth(1.2f),
                textOffset(arrayOf(0.9f, 0.9f)),
                textAllowOverlap(false),
                textIgnorePlacement(false),
            ).withFilter(
                Expression.all(eq(get(PROP_HAS_LABEL), literal(true)), eq(get(PROP_ALWAYS_LABEL), literal(false)))
            ).also { it.setMinZoom(LABEL_MIN_ZOOM) }
        )
        style.addLayer(
            SymbolLayer(LYR_LABELS_ALWAYS, SRC_AIRCRAFT).withProperties(
                textField(get(PROP_LABEL)),
                textSize(10f),
                textColor(get(PROP_COLOR)),
                textHaloColor("#000000"),
                textHaloWidth(1.2f),
                textOffset(arrayOf(0.9f, 0.9f)),
                textAllowOverlap(true),
                textIgnorePlacement(true),
            ).withFilter(
                Expression.all(eq(get(PROP_HAS_LABEL), literal(true)), eq(get(PROP_ALWAYS_LABEL), literal(true)))
            )
        )

        // Clustered mode: hidden until update() turns it on.
        style.addLayer(
            CircleLayer(LYR_CLUSTER_CIRCLES, SRC_CLUSTER).withProperties(
                circleRadius(
                    Expression.interpolate(
                        Expression.linear(), get("point_count"),
                        Expression.stop(0, 14f), Expression.stop(50, 20f),
                    )
                ),
                circleColor(AdsbColors.Primary.toHex()),
                circleOpacity(0.78f),
                circleStrokeWidth(0f),
                org.maplibre.android.style.layers.PropertyFactory.visibility(Property.NONE),
            ).withFilter(Expression.has("point_count"))
        )
        style.addLayer(
            SymbolLayer(LYR_CLUSTER_COUNT, SRC_CLUSTER).withProperties(
                textField(Expression.toString(get("point_count"))),
                textSize(12f),
                textColor(AdsbColors.OnPrimary.toHex()),
                textAllowOverlap(true),
                textIgnorePlacement(true),
                org.maplibre.android.style.layers.PropertyFactory.visibility(Property.NONE),
            ).withFilter(Expression.has("point_count"))
        )
    }

    /** Applies one publish. Returns (shown, total) for the decimation chip, and whether clustering is active. */
    fun update(
        markers: List<MapMarker>,
        observer: LatLng?,
        selectedIcao: String?,
        showRangeRings: Boolean,
        showLabels: Boolean,
        showGroundTraffic: Boolean,
        ringRadiiNm: List<Double>,
        ringLabels: List<String>,
        ringColorHex: String,
        ringWidthPx: Float,
        ringDash: FloatArray?,
        zoom: Double,
    ): DrawResult {
        observerSource.setGeoJson(
            observer?.let { FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude))) }
                ?: FeatureCollection.fromFeatures(emptyList())
        )

        if (showRangeRings && observer != null) {
            val ringFeatures = ringRadiiNm.mapIndexed { i, nm ->
                val alpha = (0.55f - i * 0.05f).coerceAtLeast(0.30f)
                Feature.fromGeometry(
                    LineString.fromLngLats(circlePoints(observer.latitude, observer.longitude, nm).map { Point.fromLngLat(it.second, it.first) })
                ).apply {
                    addStringProperty(PROP_COLOR, ringColorHex)
                    addNumberProperty(PROP_WIDTH, ringWidthPx)
                    addNumberProperty(PROP_HALO_WIDTH, ringWidthPx + 2f)
                    addNumberProperty(PROP_OPACITY, alpha)
                }
            }
            ringsSource.setGeoJson(FeatureCollection.fromFeatures(ringFeatures))

            val labelFeatures = ringRadiiNm.mapIndexedNotNull { i, nm ->
                val label = ringLabels.getOrNull(i) ?: return@mapIndexedNotNull null
                val (lat, lon) = circlePoints(observer.latitude, observer.longitude, nm, 1).first()
                Feature.fromGeometry(Point.fromLngLat(lon, lat)).apply {
                    addStringProperty(PROP_LABEL, label)
                    addStringProperty(PROP_COLOR, ringColorHex)
                }
            }
            ringLabelsSource.setGeoJson(FeatureCollection.fromFeatures(labelFeatures))
        } else {
            ringsSource.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            ringLabelsSource.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        }

        val visible = markers.filter { showGroundTraffic || !it.onGround }
        val clustered = visible.size > CLUSTER_MARKER_THRESHOLD ||
            (zoom < CLUSTER_ZOOM_BELOW && visible.size >= CLUSTER_MIN_COUNT)

        if (clustered) {
            clusterSource.setGeoJson(
                FeatureCollection.fromFeatures(visible.map { Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)) })
            )
            aircraftSource.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            trailsSource.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            return DrawResult(shown = visible.size, total = visible.size, clustered = true)
        }

        val drawn = if (visible.size > MAX_DRAWN_MARKERS) {
            val stride = visible.size.toFloat() / MAX_DRAWN_MARKERS
            (0 until MAX_DRAWN_MARKERS).map { visible[(it * stride).toInt()] }
        } else visible

        val features = drawn.map { m -> markerFeature(m, m.icao == selectedIcao, showLabels) }
        aircraftSource.setGeoJson(FeatureCollection.fromFeatures(features))

        val trailFeatures = drawn.filter { it.trail.size > 1 }.map { m ->
            Feature.fromGeometry(LineString.fromLngLats(m.trail.map { Point.fromLngLat(it.longitude, it.latitude) }))
        }
        trailsSource.setGeoJson(FeatureCollection.fromFeatures(trailFeatures))

        return DrawResult(shown = drawn.size, total = visible.size, clustered = false)
    }

    fun setClusterLayersVisible(visible: Boolean, style: org.maplibre.android.maps.Style) {
        val v = if (visible) Property.VISIBLE else Property.NONE
        style.getLayer(LYR_CLUSTER_CIRCLES)?.setProperties(org.maplibre.android.style.layers.PropertyFactory.visibility(v))
        style.getLayer(LYR_CLUSTER_COUNT)?.setProperties(org.maplibre.android.style.layers.PropertyFactory.visibility(v))
        val iv = if (visible) Property.NONE else Property.VISIBLE
        style.getLayer(LYR_AIRCRAFT_ICONS)?.setProperties(org.maplibre.android.style.layers.PropertyFactory.visibility(iv))
        style.getLayer(LYR_LABELS)?.setProperties(org.maplibre.android.style.layers.PropertyFactory.visibility(iv))
        style.getLayer(LYR_LABELS_ALWAYS)?.setProperties(org.maplibre.android.style.layers.PropertyFactory.visibility(iv))
        style.getLayer(LYR_SELECTED_RING)?.setProperties(org.maplibre.android.style.layers.PropertyFactory.visibility(iv))
        style.getLayer(LYR_ALERT_RING)?.setProperties(org.maplibre.android.style.layers.PropertyFactory.visibility(iv))
        style.getLayer(LYR_TRAILS)?.setProperties(org.maplibre.android.style.layers.PropertyFactory.visibility(iv))
    }

    private fun markerFeature(m: MapMarker, selected: Boolean, showLabels: Boolean): Feature {
        val colorHex = when {
            m.raActive || m.emergency -> AdsbColors.Error
            m.isStale -> AdsbColors.TextDisabled
            m.onGround -> AdsbColors.Warning
            else -> AdsbColors.Success
        }.toHex()
        val shape = when (m.shape) {
            com.laviavi.adsbandroid.ui.model.MarkerShape.PLANE -> MarkerIcons.PLANE
            com.laviavi.adsbandroid.ui.model.MarkerShape.SQUARE -> MarkerIcons.SQUARE
            com.laviavi.adsbandroid.ui.model.MarkerShape.CIRCLE -> MarkerIcons.CIRCLE
            com.laviavi.adsbandroid.ui.model.MarkerShape.TRIANGLE -> MarkerIcons.TRIANGLE
        }
        return Feature.fromGeometry(Point.fromLngLat(m.lon, m.lat)).apply {
            addStringProperty(PROP_ICAO, m.icao)
            addStringProperty(PROP_SHAPE, shape)
            addNumberProperty(PROP_ROTATION, (m.trackDeg ?: 0).toFloat())
            addStringProperty(PROP_COLOR, colorHex)
            addNumberProperty(PROP_SIZE_MUL, if (selected) 1.4f else 1f)
            addNumberProperty(PROP_OPACITY, if (m.isStale) 0.5f else 1f)
            addBooleanProperty(PROP_SELECTED, selected)
            addBooleanProperty(PROP_ALERT, m.raActive || m.emergency)
            val label = m.label.takeIf { showLabels }
            addBooleanProperty(PROP_HAS_LABEL, label != null)
            addStringProperty(PROP_LABEL, label ?: "")
            addBooleanProperty(PROP_ALWAYS_LABEL, selected || m.raActive || m.emergency)
        }
    }

    companion object {
        const val LYR_AIRCRAFT_ICONS = "aircraft-icons"
        const val LYR_LABELS = "aircraft-labels"
        const val LYR_LABELS_ALWAYS = "aircraft-labels-always"
        const val LYR_SELECTED_RING = "aircraft-selected-ring"
        const val LYR_ALERT_RING = "aircraft-alert-ring"
        private const val LYR_CLUSTER_CIRCLES = "aircraft-cluster-circles"
        private const val LYR_CLUSTER_COUNT = "aircraft-cluster-count"
        private const val LYR_TRAILS = "aircraft-trails"
        private const val LYR_RING = "range-ring"
        private const val LYR_RING_HALO = "range-ring-halo"
        private const val LYR_RING_LABEL = "range-ring-label"
        private const val LYR_OBSERVER_RING = "observer-ring"
        private const val LYR_OBSERVER_DOT = "observer-dot"

        private const val SRC_AIRCRAFT = "src-aircraft"
        private const val SRC_TRAILS = "src-trails"
        private const val SRC_RINGS = "src-rings"
        private const val SRC_RING_LABELS = "src-ring-labels"
        private const val SRC_OBSERVER = "src-observer"
        private const val SRC_CLUSTER = "src-cluster"

        private const val PROP_ICAO = "icao"
        private const val PROP_SHAPE = "shape"
        private const val PROP_ROTATION = "rotation"
        private const val PROP_COLOR = "color"
        private const val PROP_SIZE_MUL = "sizeMul"
        private const val PROP_OPACITY = "opacity"
        private const val PROP_SELECTED = "selected"
        private const val PROP_ALERT = "alert"
        private const val PROP_HAS_LABEL = "hasLabel"
        private const val PROP_LABEL = "label"
        private const val PROP_ALWAYS_LABEL = "alwaysLabel"
        private const val PROP_WIDTH = "width"
        private const val PROP_HALO_WIDTH = "haloWidth"

        const val CLUSTER_MARKER_THRESHOLD = 150
        const val CLUSTER_ZOOM_BELOW = 8.0
        const val CLUSTER_MIN_COUNT = 20
        const val MAX_DRAWN_MARKERS = 400
        private const val LABEL_MIN_ZOOM = 9.0f
        private const val CLUSTER_RADIUS_PX = 60

        /** Great-circle destination points around a center — renders a range ring as an actual geodesic circle (real nm at any zoom), not a MapLibre CircleLayer (screen-pixel radius, not geo-accurate). Returns (lat, lon) pairs. */
        fun circlePoints(centerLat: Double, centerLon: Double, radiusNm: Double, points: Int = 144): List<Pair<Double, Double>> {
            val earthRadiusNm = 3440.065
            val angular = radiusNm / earthRadiusNm
            val lat1 = Math.toRadians(centerLat)
            val lon1 = Math.toRadians(centerLon)
            return (0..points).map { i ->
                val bearing = Math.toRadians(i * 360.0 / points)
                val lat2 = asin(sin(lat1) * cos(angular) + cos(lat1) * sin(angular) * cos(bearing))
                val lon2 = lon1 + atan2(sin(bearing) * sin(angular) * cos(lat1), cos(angular) - sin(lat1) * sin(lat2))
                Math.toDegrees(lat2) to Math.toDegrees(lon2)
            }
        }
    }
}

internal data class DrawResult(val shown: Int, val total: Int, val clustered: Boolean)

private fun androidx.compose.ui.graphics.Color.toHex(): String =
    "#%06X".format(this.toArgb() and 0xFFFFFF)
