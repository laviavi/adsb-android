package com.laviavi.adsbandroid.ui.model

/**
 * What the coverage polar plots. One shape answers two questions: how far the
 * receiver hears in each sector, and how many aircrafts it has actually seen there.
 */
enum class CoverageMode(val label: String) {
    RANGE("RANGE"),
    AIRCRAFTS("AIRCRAFTS"),
}
