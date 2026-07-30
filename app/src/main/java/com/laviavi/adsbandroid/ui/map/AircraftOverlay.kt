package com.laviavi.adsbandroid.ui.map

import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import com.laviavi.adsbandroid.ui.model.MapMarker
import com.laviavi.adsbandroid.ui.model.MarkerShape
import com.laviavi.adsbandroid.ui.theme.AdsbColors
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * Draws the whole aircraft layer — range rings, observer, trails, markers, labels —
 * in a single overlay pass.
 *
 * One overlay rather than an osmdroid `Marker` per aircraft: markers move at 2 Hz
 * and rebuilding an overlay collection at that rate is what makes map screens cost
 * frames. Here a position update is a field assignment plus `invalidate()`, and the
 * draw is a flat loop over pre-formatted values that never allocates a string.
 */
class AircraftOverlay(
    private val density: Float,
) : Overlay() {

    /** Replaced wholesale on each 2 Hz publish; never mutated during a draw. */
    var markers: List<MapMarker> = emptyList()
    var observer: GeoPoint? = null
    var selectedIcao: String? = null

    var showRangeRings: Boolean = true
    var showLabels: Boolean = true
    var showGroundTraffic: Boolean = true

    /** Ring radii in nautical miles, inner first. */
    var ringRadiiNm: List<Double> = emptyList()
    var ringLabels: List<String> = emptyList()

    /** Set when the draw decimated: how many of how many are actually rendered. */
    var onDecimated: ((shown: Int, total: Int) -> Unit)? = null
    /** Set when the draw clustered instead of drawing individual markers. */
    var clustered: Boolean = false
        private set

    private val dp = density
    private fun px(v: Float) = v * dp

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = px(1f)
    }
    private val ringLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = px(10f)
        color = AdsbColors.Primary.toArgb()
    }
    private val observerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = px(2f)
        color = AdsbColors.Primary.toArgb()
    }
    private val observerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = AdsbColors.Primary.toArgb()
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = px(1.5f)
        color = AdsbColors.Primary.toArgb()
        alpha = 110
    }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AdsbColors.Background.toArgb()
        alpha = 204 // 80 %
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = px(10f)
    }
    private val clusterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AdsbColors.Primary.toArgb()
        alpha = 200
    }
    private val clusterTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        textSize = px(12f)
        color = AdsbColors.OnPrimary.toArgb()
        textAlign = Paint.Align.CENTER
    }

    private val point = Point()
    private val planePath = Path()
    private val trailPath = Path()

    /** Reused across draws so label collision costs no allocation per frame. */
    private val occupiedCells = HashSet<Long>()

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val projection = mapView.projection
        val zoom = mapView.zoomLevelDouble

        if (showRangeRings) drawRings(canvas, mapView)
        drawObserver(canvas, projection)

        val visible = markers.filter { showGroundTraffic || !it.onGround }

        // Above 150 markers, or zoomed far out with enough traffic to overlap,
        // individual glyphs stop being readable and start costing frames.
        // The zoom rule carries a count floor too — collapsing three aircraft into
        // a bubble reading "1" hides information instead of summarising it.
        clustered = visible.size > CLUSTER_MARKER_THRESHOLD ||
            (zoom < CLUSTER_ZOOM_BELOW && visible.size >= CLUSTER_MIN_COUNT)
        if (clustered) {
            drawClusters(canvas, projection, visible)
            onDecimated?.invoke(visible.size, visible.size)
            return
        }

        // Above the decimation cap, draw a bounded subset — and tell the caller,
        // so the cap can be shown to the user rather than silently applied.
        val drawn = if (visible.size > MAX_DRAWN_MARKERS) {
            onDecimated?.invoke(MAX_DRAWN_MARKERS, visible.size)
            val stride = visible.size.toFloat() / MAX_DRAWN_MARKERS
            (0 until MAX_DRAWN_MARKERS).map { visible[(it * stride).toInt()] }
        } else {
            onDecimated?.invoke(visible.size, visible.size)
            visible
        }

        occupiedCells.clear()
        for (m in drawn) {
            projection.toPixels(GeoPoint(m.lat, m.lon), point)
            if (m.trail.size > 1) drawTrail(canvas, projection, m)
            drawMarker(canvas, m, point.x.toFloat(), point.y.toFloat())
        }
        // Labels in a second pass so a marker never draws over a label already placed.
        if (showLabels) {
            for (m in drawn) {
                projection.toPixels(GeoPoint(m.lat, m.lon), point)
                drawLabel(canvas, m, point.x.toFloat(), point.y.toFloat(), zoom)
            }
        }
    }

    private fun drawRings(canvas: Canvas, mapView: MapView) {
        val obs = observer ?: return
        val projection = mapView.projection
        projection.toPixels(obs, point)
        val cx = point.x.toFloat()
        val cy = point.y.toFloat()

        ringRadiiNm.forEachIndexed { i, radiusNm ->
            val radiusPx = nauticalMilesToPixels(radiusNm, obs.latitude, mapView)
            if (radiusPx <= 0f) return@forEachIndexed
            // Outer rings fade: .22 / .16 / .10 alpha, innermost strongest.
            val alpha = when (i) {
                0 -> 0.22f
                1 -> 0.16f
                else -> 0.10f
            }
            ringPaint.color = AdsbColors.Primary.toArgb()
            ringPaint.alpha = (alpha * 255).roundToInt()
            canvas.drawCircle(cx, cy, radiusPx, ringPaint)

            ringLabels.getOrNull(i)?.let { label ->
                ringLabelPaint.alpha = 200
                canvas.drawText(label, cx + px(4f), cy - radiusPx - px(3f), ringLabelPaint)
            }
        }
    }

    private fun drawObserver(canvas: Canvas, projection: org.osmdroid.views.Projection) {
        val obs = observer ?: return
        projection.toPixels(obs, point)
        canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), px(9f), observerPaint)
        canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), px(3f), observerDotPaint)
    }

    private fun drawTrail(canvas: Canvas, projection: org.osmdroid.views.Projection, m: MapMarker) {
        trailPath.reset()
        m.trail.forEachIndexed { i, p ->
            projection.toPixels(GeoPoint(p.latitude, p.longitude), point)
            if (i == 0) trailPath.moveTo(point.x.toFloat(), point.y.toFloat())
            else trailPath.lineTo(point.x.toFloat(), point.y.toFloat())
        }
        canvas.drawPath(trailPath, trailPaint)
    }

    private fun drawMarker(canvas: Canvas, m: MapMarker, x: Float, y: Float) {
        val selected = m.icao == selectedIcao
        val colour = when {
            m.raActive || m.emergency -> AdsbColors.Error
            m.isStale -> AdsbColors.TextDisabled
            m.onGround -> AdsbColors.Warning
            else -> AdsbColors.Success
        }.toArgb()

        val scale = if (selected) 1.4f else 1f
        markerPaint.color = colour
        // Stale aircraft draw hollow at half alpha: shape and fill both change, so
        // the state survives a colour-blind reading.
        if (m.isStale) {
            markerPaint.style = Paint.Style.STROKE
            markerPaint.strokeWidth = px(1.5f)
            markerPaint.alpha = 128
        } else {
            markerPaint.style = Paint.Style.FILL
            markerPaint.alpha = 255
        }

        when (m.shape) {
            MarkerShape.PLANE -> drawPlane(canvas, x, y, (m.trackDeg ?: 0).toFloat(), scale)
            MarkerShape.SQUARE -> {
                val h = px(5f) * scale
                canvas.drawRect(x - h, y - h, x + h, y + h, markerPaint)
            }
            MarkerShape.CIRCLE -> canvas.drawCircle(x, y, px(5f) * scale, markerPaint)
            MarkerShape.TRIANGLE -> drawTriangle(canvas, x, y, (m.trackDeg ?: 0).toFloat(), scale)
        }

        if (selected) {
            markerPaint.style = Paint.Style.STROKE
            markerPaint.strokeWidth = px(1.5f)
            markerPaint.alpha = 255
            markerPaint.color = AdsbColors.Primary.toArgb()
            canvas.drawCircle(x, y, px(14f), markerPaint)
        }
        if (m.raActive || m.emergency) {
            markerPaint.style = Paint.Style.STROKE
            markerPaint.strokeWidth = px(1.5f)
            markerPaint.color = AdsbColors.Error.toArgb()
            markerPaint.alpha = 160
            canvas.drawCircle(x, y, px(12f), markerPaint)
        }
    }

    /**
     * A plane silhouette rather than a bare triangle: a triangle reads as a
     * direction indicator even when track is unknown, which is exactly the claim
     * the receiver cannot make. Aircraft without a track use a circle instead.
     */
    private fun drawPlane(canvas: Canvas, x: Float, y: Float, trackDeg: Float, scale: Float) {
        val s = px(1f) * scale
        planePath.reset()
        planePath.moveTo(0f, -9f * s)          // nose
        planePath.lineTo(1.6f * s, -3f * s)
        planePath.lineTo(9f * s, 2.2f * s)     // right wing
        planePath.lineTo(9f * s, 4.2f * s)
        planePath.lineTo(1.6f * s, 2.2f * s)
        planePath.lineTo(1.6f * s, 6.5f * s)
        planePath.lineTo(4f * s, 8.6f * s)     // right tailplane
        planePath.lineTo(4f * s, 9.6f * s)
        planePath.lineTo(0f, 8.2f * s)
        planePath.lineTo(-4f * s, 9.6f * s)    // left tailplane
        planePath.lineTo(-4f * s, 8.6f * s)
        planePath.lineTo(-1.6f * s, 6.5f * s)
        planePath.lineTo(-1.6f * s, 2.2f * s)
        planePath.lineTo(-9f * s, 4.2f * s)    // left wing
        planePath.lineTo(-9f * s, 2.2f * s)
        planePath.lineTo(-1.6f * s, -3f * s)
        planePath.close()

        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(trackDeg)
        canvas.drawPath(planePath, markerPaint)
        canvas.restore()
    }

    private fun drawTriangle(canvas: Canvas, x: Float, y: Float, trackDeg: Float, scale: Float) {
        val s = px(1f) * scale
        planePath.reset()
        planePath.moveTo(0f, -8f * s)
        planePath.lineTo(6f * s, 7f * s)
        planePath.lineTo(-6f * s, 7f * s)
        planePath.close()
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(trackDeg)
        canvas.drawPath(planePath, markerPaint)
        canvas.restore()
    }

    /**
     * Labels are suppressed by a grid hash: the first label to claim a cell keeps
     * it. Selected, emergency and RA aircraft bypass suppression — their label is
     * always shown.
     */
    private fun drawLabel(canvas: Canvas, m: MapMarker, x: Float, y: Float, zoom: Double) {
        val text = m.label ?: return
        val always = m.icao == selectedIcao || m.raActive || m.emergency
        if (!always && zoom < LABEL_MIN_ZOOM) return

        if (!always) {
            val cell = (x / LABEL_CELL_W).toInt().toLong() shl 32 or
                ((y / LABEL_CELL_H).toInt().toLong() and 0xFFFFFFFFL)
            if (!occupiedCells.add(cell)) return
        }

        labelPaint.color = when {
            m.raActive || m.emergency -> AdsbColors.Error
            m.isStale -> AdsbColors.TextDisabled
            else -> AdsbColors.TextPrimary
        }.toArgb()

        val w = labelPaint.measureText(text)
        val lx = x + px(9f)
        val ly = y + px(14f)
        canvas.drawRect(lx - px(2f), ly - px(9f), lx + w + px(2f), ly + px(3f), labelBgPaint)
        canvas.drawText(text, lx, ly, labelPaint)
    }

    private fun drawClusters(
        canvas: Canvas,
        projection: org.osmdroid.views.Projection,
        visible: List<MapMarker>,
    ) {
        val cells = HashMap<Long, Int>()
        val anchors = HashMap<Long, Point>()
        for (m in visible) {
            projection.toPixels(GeoPoint(m.lat, m.lon), point)
            val key = (point.x / CLUSTER_CELL).toLong() shl 32 or
                ((point.y / CLUSTER_CELL).toLong() and 0xFFFFFFFFL)
            cells[key] = (cells[key] ?: 0) + 1
            anchors.getOrPut(key) { Point(point.x, point.y) }
        }
        cells.forEach { (key, count) ->
            val p = anchors.getValue(key)
            val r = px(10f) + px(6f) * (count.coerceAtMost(50) / 50f)
            canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), r, clusterPaint)
            canvas.drawText(
                count.toString(),
                p.x.toFloat(),
                p.y.toFloat() + clusterTextPaint.textSize / 3f,
                clusterTextPaint,
            )
        }
    }

    /** Nearest marker within [HIT_RADIUS_DP] of a tap, or null. */
    fun hitTest(mapView: MapView, screenX: Float, screenY: Float): MapMarker? {
        val projection = mapView.projection
        val limit = px(HIT_RADIUS_DP)
        var best: MapMarker? = null
        var bestDist = Float.MAX_VALUE
        for (m in markers) {
            if (!showGroundTraffic && m.onGround) continue
            projection.toPixels(GeoPoint(m.lat, m.lon), point)
            val dx = point.x - screenX
            val dy = point.y - screenY
            val d = dx * dx + dy * dy
            if (d < bestDist && d <= limit * limit) {
                bestDist = d
                best = m
            }
        }
        return best
    }

    private fun nauticalMilesToPixels(nm: Double, atLatitude: Double, mapView: MapView): Float {
        val metres = nm * 1852.0
        val metresPerPixel = org.osmdroid.util.TileSystem.GroundResolution(
            atLatitude, mapView.zoomLevelDouble,
        )
        return if (metresPerPixel <= 0) 0f else (metres / metresPerPixel).toFloat()
    }

    companion object {
        const val CLUSTER_MARKER_THRESHOLD = 150
        const val CLUSTER_ZOOM_BELOW = 8.0
        /** Zoomed-out clustering only kicks in once bubbles actually summarise something. */
        const val CLUSTER_MIN_COUNT = 20
        const val MAX_DRAWN_MARKERS = 400
        const val LABEL_MIN_ZOOM = 9.0
        private const val LABEL_CELL_W = 150f
        private const val LABEL_CELL_H = 44f
        private const val CLUSTER_CELL = 90
        private const val HIT_RADIUS_DP = 22f
    }
}
