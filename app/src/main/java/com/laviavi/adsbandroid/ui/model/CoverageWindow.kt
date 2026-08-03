package com.laviavi.adsbandroid.ui.model

/** Which [com.laviavi.adsbandroid.observability.CoverageMetricsRow] the polar plots — the current 5-minute window, or every tick ever recorded. */
enum class CoverageWindow(val label: String) {
    LIVE("LIVE"),
    ALL_TIME("ALL-TIME"),
}
