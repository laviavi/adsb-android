package com.laviavi.adsbandroid.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.laviavi.adsbandroid.ui.theme.AdsbColors

@Composable
fun SignalBars(
    bars: Int,
    activeColor: Color,
    modifier: Modifier = Modifier,
) {
    val desc = "$bars of 3 signal bars"
    Canvas(
        modifier = modifier
            .size(width = 14.dp, height = 12.dp)
            .semantics { contentDescription = desc },
    ) {
        val barW = 3.dp.toPx()
        val gap = 2.dp.toPx()
        val heights = floatArrayOf(4.dp.toPx(), 6.dp.toPx(), 9.dp.toPx())

        for (i in 0..2) {
            val color = if (i < bars) activeColor else AdsbColors.SurfaceElevated
            val h = heights[i]
            val x = i * (barW + gap)
            drawRect(
                color = color,
                topLeft = Offset(x, size.height - h),
                size = Size(barW, h),
            )
        }
    }
}
