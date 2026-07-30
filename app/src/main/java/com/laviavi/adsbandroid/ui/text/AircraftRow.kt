package com.laviavi.adsbandroid.ui.text

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laviavi.adsbandroid.enrich.DataSource
import com.laviavi.adsbandroid.ui.components.SignalBars
import com.laviavi.adsbandroid.ui.model.AgeTier
import com.laviavi.adsbandroid.ui.model.AircraftRowUi
import com.laviavi.adsbandroid.ui.model.VsArrow
import com.laviavi.adsbandroid.ui.theme.AdsbColors
import com.laviavi.adsbandroid.ui.theme.AdsbDimens

@Composable
fun AircraftRow(
    row: AircraftRowUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rowAlpha = if (row.ageTier == AgeTier.STALE) 0.6f else 1f
    val ageColor = when (row.ageTier) {
        AgeTier.FRESH -> AdsbColors.Success
        AgeTier.AGEING -> AdsbColors.Warning
        AgeTier.STALE -> AdsbColors.TextDisabled
    }
    val bgColor = when {
        row.raActive -> AdsbColors.ErrorFill
        else -> Color.Transparent
    }
    val semanticDesc = buildString {
        append(row.callsign ?: row.icao)
        row.typeCode?.let { append(", $it") }
        append(", ${row.altitude}")
        append(", ${row.speed}")
        append(", ${row.distance} miles")
        append(", bearing ${row.bearing}")
        append(", updated ${row.age} ago")
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = AdsbDimens.AircraftRowHeight)
            .background(bgColor)
            .clickable(onClick = onClick)
            .drawBehind {
                if (row.raActive) {
                    drawRect(AdsbColors.Error, Offset.Zero, size.copy(width = 4.dp.toPx()))
                }
                drawRect(
                    AdsbColors.Surface,
                    Offset(0f, size.height - 1.dp.toPx()),
                    size.copy(height = 1.dp.toPx()),
                )
            }
            .alpha(rowAlpha)
            .padding(start = if (row.raActive) 8.dp else AdsbDimens.ScreenGutter, end = AdsbDimens.ScreenGutter)
            .padding(vertical = 11.dp)
            .semantics { contentDescription = semanticDesc },
        verticalAlignment = Alignment.Top,
        // The gap is owned by the Row, not by a Spacer inside either child, so it
        // cannot be consumed when the identity text runs long.
        horizontalArrangement = Arrangement.spacedBy(AdsbDimens.RowGutter),
    ) {
        // Three lines in the spec's 72 dp row: line heights are pinned rather than
        // left to the font's default leading, which overflows and clips.
        //
        // The 72 dp is a floor, not a fixed height. Line heights are in sp, so at a
        // system font scale above 1.0 the three lines need more than 72 dp and a
        // fixed height silently cuts line 3 off — which is what it did on a device
        // set to 1.15. The row matches the spec at default scale and grows only for
        // users who have asked for larger text.
        Column(
            modifier = Modifier.weight(1f).testTag(AircraftRowTags.IDENTITY),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            // Line 1: ICAO · callsign · type · [RA badge]
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(row.icao, fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 17.sp,
                    fontWeight = FontWeight.W600, color = AdsbColors.Primary)
                row.callsign?.let {
                    Text(it, fontSize = 15.sp, lineHeight = 17.sp, fontWeight = FontWeight.W600,
                        color = AdsbColors.TextPrimary, maxLines = 1)
                }
                row.typeCode?.let {
                    Text(it, fontSize = 12.sp, lineHeight = 17.sp, color = AdsbColors.TextSecondary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (row.raActive) {
                    Surface(color = AdsbColors.Error, shape = RoundedCornerShape(3.dp)) {
                        Text("RA", modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                            fontSize = 10.sp, fontWeight = FontWeight.W700, color = AdsbColors.ErrorOnDark)
                    }
                }
            }
            // Line 2: registration · operator · route
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.registration?.let {
                    Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 15.sp,
                        color = AdsbColors.TextPrimary, maxLines = 1)
                    ProvenanceMark(row.registrationMark)
                }
                row.operator?.let {
                    Text(it, fontSize = 12.sp, lineHeight = 15.sp, color = AdsbColors.TextSecondary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = true))
                }
                if (row.operator == null) Spacer(Modifier.weight(1f))
                val routeText = row.route ?: if (row.raActive) row.raText else null
                if (routeText != null) {
                    val routeColor = if (row.raActive) AdsbColors.Error else AdsbColors.Primary
                    // Capped so a long route cannot starve the operator; `fill = false`
                    // keeps a short route from reserving space it does not need.
                    Text(routeText, fontSize = 12.sp, lineHeight = 15.sp, color = routeColor,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(0.52f, fill = false))
                    if (!row.raActive) ProvenanceMark(row.routeMark)
                } else {
                    Text("route not found", fontSize = 12.sp, lineHeight = 15.sp, color = AdsbColors.TextDisabled,
                        maxLines = 1)
                }
            }
            // Line 3: signal bars · msgs · speed · age
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SignalBars(bars = row.signalBars, activeColor = ageColor)
                Text(row.messageCount, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp,
                    color = AdsbColors.TextDisabled, maxLines = 1)
                Text(row.speed, fontSize = 11.sp, lineHeight = 14.sp, color = AdsbColors.TextSecondary, maxLines = 1)
                Text(row.age, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp,
                    color = ageColor, maxLines = 1)
            }
        }

        // No weight and no intrinsic sizing: the block is measured at its literal
        // width before the identity column is given what is left, so under width
        // pressure the identity text truncates and this never moves.
        Row(horizontalArrangement = Arrangement.spacedBy(AdsbDimens.DataColumnGap)) {
            DataColumn("DIST ${row.distanceUnit}", row.distance, AdsbDimens.DataColDist, AircraftRowTags.DIST)
            val vsChar = when (row.vsArrow) {
                VsArrow.UP -> "↑"
                VsArrow.DOWN -> "↓"
                VsArrow.LEVEL -> ""
                VsArrow.UNKNOWN -> ""
            }
            // No `ft` suffix — the header states the unit, and repeating it per row
            // is what pushes this column wide enough to collide with the callsign.
            DataColumn("ALT ft", "$vsChar${row.altitude}", AdsbDimens.DataColAlt, AircraftRowTags.ALT)
            DataColumn("TRACK", row.bearing, AdsbDimens.DataColTrack, AircraftRowTags.TRACK)
        }
    }
}

/**
 * One fixed-width track. The width is a literal, never derived from the content
 * or the header: with content sizing a row reading `↓8200` measures wider than
 * one reading `↑FL350`, so that row's whole block shifts left and the columns
 * stagger down the list.
 *
 * Values may clip if they exceed the track; they may never wrap, because wrapping
 * changes the row height. Tabular figures stop the digits changing width at 4 Hz.
 */
@Composable
private fun DataColumn(
    header: String,
    value: String,
    width: androidx.compose.ui.unit.Dp,
    tag: String,
) {
    Column(modifier = Modifier.width(width).testTag(tag)) {
        Text(
            header,
            modifier = Modifier.fillMaxWidth(),
            fontFamily = FontFamily.Monospace, fontSize = 9.sp, lineHeight = 11.sp,
            // Spec calls for .08em (0.72 sp) tracking. Cut to .045em so the longest
            // header, `DIST mi`, still fits inside its 44 dp track once the system
            // font scale is above 1.0 — at 1.15 the spec value clipped the `i`. The
            // track is sized from the widest value, never from the header, so the
            // header is what has to give.
            letterSpacing = 0.4.sp, color = AdsbColors.TextDisabled,
            textAlign = TextAlign.End,
            maxLines = 1, softWrap = false,
        )
        Text(
            value,
            modifier = Modifier.fillMaxWidth(),
            color = AdsbColors.TextPrimary, maxLines = 1, softWrap = false,
            textAlign = TextAlign.End,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 16.sp,
                fontFeatureSettings = TABULAR_FIGURES,
            ),
        )
    }
}

/** Fixed-advance digits — required on every numeric column (spec §Typography). */
internal const val TABULAR_FIGURES = "tnum"

/** Handles for the layout assertions in `AircraftRowLayoutTests`. */
object AircraftRowTags {
    const val IDENTITY = "aircraftRow.identity"
    const val DIST     = "aircraftRow.dist"
    const val ALT      = "aircraftRow.alt"
    const val TRACK    = "aircraftRow.track"
}

@Composable
fun ProvenanceMark(source: DataSource?) {
    val (glyph, desc) = when (source) {
        DataSource.ALGORITHMIC -> "*" to "algorithmically derived"
        DataSource.DATABASE -> "•" to "from offline database"
        DataSource.NETWORK -> "~" to "from network lookup"
        DataSource.DECODED, null -> return
    }
    Text(
        text = glyph,
        fontSize = 10.sp,
        color = AdsbColors.TextDisabled,
        modifier = Modifier.semantics { contentDescription = desc },
    )
}
