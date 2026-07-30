package com.laviavi.adsbandroid.ui.model

import androidx.compose.runtime.Immutable

@Immutable
data class LiveMetrics(
    val trackedCount: Int = 0,
    val framesPerSecond: String = "0",
    val validPercent: String = "0",
    val maxRangeMi: String = "0",
    val gainDb: String = "---",
    val sparklineData: List<Float> = emptyList(),
)
