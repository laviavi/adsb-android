package com.laviavi.adsbandroid.ui.map

import androidx.compose.ui.graphics.Color
import com.laviavi.adsbandroid.ui.theme.AdsbColors

/** Range-ring color presets. [CYAN] reproduces the app's original hardcoded ring color. */
enum class RingColorPreset(val label: String, val color: Color) {
    CYAN("Cyan", AdsbColors.Primary),
    AMBER("Amber", AdsbColors.Warning),
    GREEN("Green", AdsbColors.Success),
    RED("Red", AdsbColors.Error),
    WHITE("White", AdsbColors.TextPrimary),
    GREY("Grey", AdsbColors.OutlineStrong),
}

/** [THIN] reproduces the app's original hardcoded 1dp ring stroke. */
enum class RingWidth(val label: String, val dp: Float) {
    THIN("Thin", 1f),
    MEDIUM("Medium", 2f),
    THICK("Thick", 3f),
}

/** [SOLID] reproduces the app's original hardcoded ring line style. */
enum class RingLineStyle(val label: String) {
    SOLID("Solid"),
    DASHED("Dashed"),
    DOTTED("Dotted"),
}
