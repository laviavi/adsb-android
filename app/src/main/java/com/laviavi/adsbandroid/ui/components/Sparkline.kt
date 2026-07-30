package com.laviavi.adsbandroid.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.laviavi.adsbandroid.ui.theme.AdsbColors

private val RAMP = listOf(
    Color(0xFF1E4A5E),
    Color(0xFF245E77),
    Color(0xFF2C7291),
    AdsbColors.Primary,
)

@Composable
fun Sparkline(
    data: List<Float>,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier.fillMaxWidth().height(34.dp),
    ) {
        if (data.isEmpty()) return@Canvas
        val maxVal = data.max().coerceAtLeast(1f)
        val barCount = data.size.coerceAtMost(60)
        val gap = 2.dp.toPx()
        val totalGaps = (barCount - 1) * gap
        val barWidth = ((size.width - totalGaps) / barCount).coerceAtLeast(1f)

        val visible = data.takeLast(barCount)
        visible.forEachIndexed { i, value ->
            val fraction = (value / maxVal).coerceIn(0f, 1f)
            val barHeight = fraction * size.height
            val colorIndex = (i * RAMP.lastIndex / (barCount - 1).coerceAtLeast(1)).coerceIn(0, RAMP.lastIndex)
            drawRect(
                color = RAMP[colorIndex],
                topLeft = Offset(i * (barWidth + gap), size.height - barHeight),
                size = Size(barWidth, barHeight),
            )
        }
    }
}
