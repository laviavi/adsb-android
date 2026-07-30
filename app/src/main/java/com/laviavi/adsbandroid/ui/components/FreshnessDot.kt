package com.laviavi.adsbandroid.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.laviavi.adsbandroid.ui.model.AgeTier
import com.laviavi.adsbandroid.ui.theme.AdsbColors

@Composable
fun FreshnessDot(
    tier: AgeTier,
    modifier: Modifier = Modifier,
) {
    val color: Color
    val label: String
    val filled: Boolean
    when (tier) {
        AgeTier.FRESH -> { color = AdsbColors.Success; label = "fresh"; filled = true }
        AgeTier.AGEING -> { color = AdsbColors.Warning; label = "ageing"; filled = false }
        AgeTier.STALE -> { color = AdsbColors.TextDisabled; label = "stale"; filled = false }
    }
    Canvas(
        modifier = modifier
            .size(8.dp)
            .semantics { contentDescription = label },
    ) {
        if (filled) {
            drawCircle(color = color)
        } else {
            drawCircle(color = color, style = Stroke(width = 1.5.dp.toPx()))
        }
    }
}
