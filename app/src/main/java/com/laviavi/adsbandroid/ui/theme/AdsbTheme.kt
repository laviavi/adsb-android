package com.laviavi.adsbandroid.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object AdsbColors {
    val Background      = Color(0xFF0A0E13)
    val Surface         = Color(0xFF141A21)
    val SurfaceElevated = Color(0xFF1E2730)
    val Outline         = Color(0xFF3D4A57)
    val OutlineStrong   = Color(0xFF5A6B7C)

    val Primary         = Color(0xFF38BDF8)
    val OnPrimary       = Color(0xFF001E2B)
    val TextPrimary     = Color(0xFFE6EDF3)
    val TextSecondary   = Color(0xFFA9B7C6)
    val TextDisabled    = Color(0xFF6B7A89)
    val Error           = Color(0xFFFF6B6B)
    val Warning         = Color(0xFFFFB74D)
    val Success         = Color(0xFF4ADE80)

    // Tinted fills from the mockups
    val SuccessFill     = Color(0x1F4ADE80)   // 12%
    val ErrorFill       = Color(0x12FF6B6B)   // 7%
    val WarningFill     = Color(0x14FFB74D)   // 8%
    val PrimaryFill     = Color(0x2938BDF8)   // 16%
    val ErrorOnDark     = Color(0xFF2B0000)

    // Derived
    val ListHeaderBg    = Color(0xFF0F141A)
    val NavBar          = Surface
    val StatusStripBg   = SurfaceElevated
}

object AdsbDimens {
    val NavBarHeight          = 66.dp
    val StatusStripHeight     = 40.dp
    val AircraftRowHeight     = 72.dp

    /**
     * Live-row right-hand data block. Fixed tracks, not content-sized, so every
     * row's block has the same width and x-position and the three columns read as
     * straight lines down the list.
     *
     * Each track is the widest real value at 13 sp monospace plus ~2 dp slack —
     * `22.7` / `↓8200` (the arrow is wider than a digit) / `073°`. They are not
     * derived from the header text, which is a different size and weight.
     */
    val DataColDist           = 44.dp
    val DataColAlt            = 58.dp
    val DataColTrack          = 42.dp
    val DataColumnGap         = 6.dp

    /** Identity column ↔ data block. Owned by the Row, never consumed by either side. */
    val RowGutter             = 12.dp
    val CardCornerRadius      = 12.dp
    val SheetCornerRadius     = 20.dp
    val PillCornerRadius      = 100.dp
    val StepperCornerRadius   = 10.dp
    val CardPadding           = 12.dp
    val ScreenGutter          = 16.dp
    val SpacingXs             = 4.dp
    val SpacingSm             = 8.dp
    val SpacingMd             = 12.dp
    val SpacingLg             = 16.dp
    val SpacingXl             = 24.dp
    val SpacingXxl            = 32.dp
}

private val AdsbColorScheme = darkColorScheme(
    primary            = AdsbColors.Primary,
    onPrimary          = AdsbColors.OnPrimary,
    background         = AdsbColors.Background,
    onBackground       = AdsbColors.TextPrimary,
    surface            = AdsbColors.Surface,
    onSurface          = AdsbColors.TextPrimary,
    surfaceVariant     = AdsbColors.SurfaceElevated,
    onSurfaceVariant   = AdsbColors.TextSecondary,
    outline            = AdsbColors.Outline,
    outlineVariant     = AdsbColors.OutlineStrong,
    error              = AdsbColors.Error,
    onError            = AdsbColors.ErrorOnDark,
)

private val AdsbTypography = Typography(
    displayLarge = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.W600, fontFamily = FontFamily.Monospace),
    displayMedium = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.W600, fontFamily = FontFamily.Monospace),
    displaySmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.W600, fontFamily = FontFamily.Monospace),
    titleLarge  = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.W600),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.W600),
    bodyLarge   = TextStyle(fontSize = 15.sp),
    bodyMedium  = TextStyle(fontSize = 13.sp),
    bodySmall   = TextStyle(fontSize = 12.sp),
    labelMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
    labelSmall  = TextStyle(fontSize = 11.sp),
)

val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp)

val SectionHeaderStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 10.sp,
    fontWeight = FontWeight.W600,
    letterSpacing = 1.4.sp,
    color = AdsbColors.Primary,
)

val DataMonoStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
    fontWeight = FontWeight.Normal,
)

@Composable
fun AdsbTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AdsbColorScheme, typography = AdsbTypography, content = content)
}
