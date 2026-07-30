package com.laviavi.adsbandroid.ui.receiver

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laviavi.adsbandroid.observability.CompassSector
import com.laviavi.adsbandroid.observability.CoverageMetricsRow
import com.laviavi.adsbandroid.pipeline.PipelineStats
import com.laviavi.adsbandroid.ui.model.CoverageMode
import com.laviavi.adsbandroid.ui.theme.AdsbColors
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 60 s stacked rate chart — one column per second, bands stacked valid / corrected
 * / recovered / bad. Each column is scaled to the busiest second in the window, so
 * the shape shows the *composition* of reception, not its absolute volume.
 */
@Composable
fun RateChart(
    samples: List<PipelineStats.RateSample>,
    modifier: Modifier = Modifier,
    height: Dp = 40.dp,
) {
    val peak = remember(samples) { samples.maxOfOrNull { it.total } ?: 0L }
    val validPct = remember(samples) {
        val t = samples.sumOf { it.total }
        if (t == 0L) 0 else (samples.sumOf { it.valid } * 100 / t).toInt()
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .semantics {
                contentDescription = "Reception rate over the last 60 seconds"
                stateDescription = "valid $validPct percent of frames"
            },
    ) {
        if (samples.isEmpty() || peak <= 0L) return@Canvas

        val slots = PipelineStats.RATE_HISTORY_SEC
        val gap = 1.dp.toPx()
        val colWidth = (size.width + gap) / slots - gap
        // Newest sample sits at the right edge, so a short history grows inward.
        val startIndex = slots - samples.size

        samples.forEachIndexed { i, s ->
            val x = (startIndex + i) * (colWidth + gap)
            var y = size.height
            // Bad at the bottom, valid on top: the eye reads the healthy band
            // against the flat top edge rather than against a moving baseline.
            listOf(
                s.bad to AdsbColors.Error,
                s.recovered to AdsbColors.Primary,
                s.corrected to AdsbColors.Warning,
                s.valid to AdsbColors.Success,
            ).forEach { (count, colour) ->
                if (count <= 0L) return@forEach
                val h = size.height * (count.toFloat() / peak.toFloat())
                y -= h
                drawRect(colour, Offset(x, y), androidx.compose.ui.geometry.Size(colWidth, h))
            }
        }
    }
}

/**
 * 8-sector coverage polar: three rings, four spokes, and a polygon whose vertex
 * radius is `21 + value/max × 41` dp. Vertices are labelled with direction, value
 * and unit so the shape is readable without colour.
 */
@Composable
fun CoveragePolar(
    row: CoverageMetricsRow?,
    mode: CoverageMode,
    unitLabel: String,
    valueForSector: (CompassSector) -> Double,
    modifier: Modifier = Modifier,
    diameter: Dp = 150.dp,
) {
    val density = LocalDensity.current
    val labelPx = with(density) { 11.sp.toPx() }

    val values = remember(row, mode) {
        CompassSector.entries.associateWith { valueForSector(it) }
    }
    val max = remember(values) { values.values.maxOrNull()?.takeIf { it > 0 } ?: 1.0 }

    Box(modifier = modifier.size(diameter)) {
        Canvas(
            modifier = Modifier
                .size(diameter)
                .semantics {
                    contentDescription = "Coverage by compass sector, ${mode.label.lowercase()}"
                    stateDescription = CompassSector.entries.joinToString(", ") { s ->
                        "${s.name} ${values.getValue(s).toInt()} $unitLabel"
                    }
                },
        ) {
            val centre = Offset(size.width / 2f, size.height / 2f)
            val maxRadius = min(size.width, size.height) / 2f
            val innerR = maxRadius * (21f / 62f)
            val spanR = maxRadius * (41f / 62f)

            // Rings
            listOf(0.33f, 0.66f, 1f).forEach { f ->
                drawCircle(
                    color = AdsbColors.SurfaceElevated,
                    radius = innerR + spanR * f,
                    center = centre,
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
            // Spokes on the cardinal axes only
            listOf(0f, 90f, 180f, 270f).forEach { deg ->
                val rad = Math.toRadians(deg.toDouble() - 90.0)
                drawLine(
                    color = AdsbColors.SurfaceElevated,
                    start = centre,
                    end = Offset(
                        centre.x + (cos(rad) * maxRadius).toFloat(),
                        centre.y + (sin(rad) * maxRadius).toFloat(),
                    ),
                    strokeWidth = 1.dp.toPx(),
                )
            }

            // Polygon
            val path = Path()
            CompassSector.entries.forEachIndexed { i, sector ->
                val v = values.getValue(sector)
                val r = innerR + spanR * (v / max).toFloat().coerceIn(0f, 1f)
                val rad = Math.toRadians(i * 45.0 - 90.0)
                val p = Offset(
                    centre.x + (cos(rad) * r).toFloat(),
                    centre.y + (sin(rad) * r).toFloat(),
                )
                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            path.close()
            drawPath(path, AdsbColors.Primary.copy(alpha = 0.22f))
            drawPath(path, AdsbColors.Primary, style = Stroke(width = 1.5.dp.toPx()))

            // Vertex labels — E/W anchored inward so they cannot clip the edge.
            val paint = android.graphics.Paint().apply {
                color = AdsbColors.TextPrimary.toArgb()
                textSize = labelPx
                typeface = android.graphics.Typeface.MONOSPACE
                isAntiAlias = true
            }
            CompassSector.entries.forEachIndexed { i, sector ->
                val v = values.getValue(sector)
                val text = "${sector.name} ${v.toInt()}"
                val rad = Math.toRadians(i * 45.0 - 90.0)
                val lr = maxRadius * 0.94f
                var x = centre.x + (cos(rad) * lr).toFloat()
                val y = centre.y + (sin(rad) * lr).toFloat() + labelPx / 3f
                val width = paint.measureText(text)
                x = when (sector) {
                    CompassSector.W -> x - width * 0.05f
                    CompassSector.E -> x - width * 0.95f
                    CompassSector.N, CompassSector.S -> x - width / 2f
                    else -> if (cos(rad) < 0) x - width * 0.35f else x - width * 0.65f
                }
                drawContext.canvas.nativeCanvas.drawText(text, x, y, paint)
            }
        }
    }
}
