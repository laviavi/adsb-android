package com.laviavi.adsbandroid.ui.text

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laviavi.adsbandroid.enrich.DataSource
import com.laviavi.adsbandroid.enrich.OperatorKind
import com.laviavi.adsbandroid.ui.components.SignalBars
import com.laviavi.adsbandroid.ui.model.AgeTier
import com.laviavi.adsbandroid.ui.model.AircraftRowUi
import com.laviavi.adsbandroid.ui.model.VsArrow
import com.laviavi.adsbandroid.ui.theme.AdsbColors
import com.laviavi.adsbandroid.ui.theme.AdsbDimens

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AircraftRow(
    row: AircraftRowUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    val rowAlpha = if (row.ageTier == AgeTier.STALE) 0.6f else 1f
    val ageColor = when (row.ageTier) {
        AgeTier.FRESH -> AdsbColors.Success
        AgeTier.AGEING -> AdsbColors.Warning
        AgeTier.STALE -> AdsbColors.TextDisabled
    }
    val bgColor = if (row.raActive) AdsbColors.ErrorFill else Color.Transparent
    // ICAO is deliberately absent from this row (Avi: no one reads it on Live) —
    // it stays the row's identity everywhere else (History, Stats, detail sheet),
    // so the semantic description still leads with it for accessibility/testing.
    val semanticDesc = buildString {
        append(row.registration ?: row.callsign ?: row.icao)
        row.typeCode?.let { append(", $it") }
        append(", ${row.altitude}")
        append(", ${row.speed}")
        append(", ${row.distance} ${row.distanceUnit}")
        append(", bearing ${row.bearing}")
        append(", updated ${row.age} ago")
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = AdsbDimens.AircraftRowHeight)
            .background(bgColor)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
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
            .padding(vertical = 13.dp)
            .semantics { contentDescription = semanticDesc },
        horizontalArrangement = Arrangement.spacedBy(AdsbDimens.RowGutter),
    ) {
        Column(
            modifier = Modifier.weight(1f).testTag(AircraftRowTags.IDENTITY),
            verticalArrangement = Arrangement.Center,
        ) {
            // Line 1: tail number (primary) · route — or the RA advisory in its place.
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val primary = row.registration ?: row.callsign
                if (primary == null) {
                    Text(" ", fontSize = 15.sp, lineHeight = 18.sp, color = Color.Transparent, maxLines = 1)
                } else {
                    Text(
                        primary, fontFamily = FontFamily.Monospace, fontSize = 15.sp, lineHeight = 18.sp,
                        fontWeight = FontWeight.W600, color = AdsbColors.TextPrimary, maxLines = 1,
                    )
                    if (row.registration != null) ProvenanceMark(row.registrationMark)
                }
                val routeText = row.route ?: if (row.raActive) row.raText else null
                if (routeText != null) {
                    val routeColor = if (row.raActive) AdsbColors.Error else AdsbColors.Primary
                    Text(
                        routeText, fontSize = 12.sp, lineHeight = 15.sp, color = routeColor,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (!row.raActive) ProvenanceMark(row.routeMark)
                }
            }
            // Line 2: callsign (only when the tail number led line 1) · type.
            val secondaryCallsign = if (row.registration != null) row.callsign else null
            val line2 = listOfNotNull(secondaryCallsign, row.typeCode).joinToString(" · ")
            if (line2.isEmpty()) {
                Text(" ", fontSize = 12.sp, lineHeight = 15.sp, color = Color.Transparent, maxLines = 1)
            } else {
                Text(
                    line2, fontSize = 12.sp, lineHeight = 15.sp, color = AdsbColors.TextSecondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            // Line 3: operator. Placeholder mirrors lines 1-2's — operator can be
            // genuinely unknown, and an omitted line would make this row shorter
            // than its neighbours in the list. A registered-owner value (as opposed
            // to a real airline name) is marked, never shown as if it were the
            // operating carrier.
            if (row.operator == null) {
                Text(" ", fontSize = 12.sp, lineHeight = 15.sp, color = Color.Transparent, maxLines = 1)
            } else {
                val text = if (row.operatorKind == OperatorKind.OWNER) "${row.operator} (owner)" else row.operator
                Text(
                    text, fontSize = 12.sp, lineHeight = 15.sp, color = AdsbColors.TextSecondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            // Line 4: signal bars · msgs · age.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 2.dp),
            ) {
                SignalBars(bars = row.signalBars, activeColor = ageColor)
                Text(
                    row.messageCount, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp,
                    color = AdsbColors.TextDisabled, maxLines = 1,
                )
                Text(
                    row.age, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp,
                    color = ageColor, maxLines = 1,
                )
            }
        }

        // Distance / altitude / bearing, vertically centered against the whole
        // row rather than pinned to its top half — content-width, not the old
        // fixed-dp tracks, so no per-value header labels are needed either:
        // the unit suffixes (mi/ft/kt) and the bearing arrow carry the meaning.
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
            modifier = Modifier
                .drawBehind {
                    drawLine(
                        AdsbColors.Outline,
                        Offset(0f, 0f),
                        Offset(0f, size.height),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
                .padding(start = AdsbDimens.RowGutter)
                .testTag(AircraftRowTags.DATA_BLOCK),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    row.distance,
                    color = AdsbColors.TextPrimary,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace, fontSize = 20.sp,
                        fontFeatureSettings = TABULAR_FIGURES,
                    ),
                )
                Text(" ${row.distanceUnit}", fontSize = 10.sp, color = AdsbColors.TextDisabled)
            }
            val vsChar = when (row.vsArrow) {
                VsArrow.UP -> "↑"
                VsArrow.DOWN -> "↓"
                VsArrow.LEVEL, VsArrow.UNKNOWN -> ""
            }
            Text(
                "$vsChar${row.altitude}",
                color = AdsbColors.TextSecondary,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                    fontFeatureSettings = TABULAR_FIGURES,
                ),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                BearingArrow(degrees = row.bearingDeg, tint = AdsbColors.TextDisabled)
                Text(
                    "${row.bearing} · ${row.speed}",
                    color = AdsbColors.TextDisabled,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                        fontFeatureSettings = TABULAR_FIGURES,
                    ),
                )
            }
        }
    }
}

/**
 * Small triangle rotated to [degrees] — carries "which way" at a glance so the
 * value beside it doesn't need a header label to say what kind of angle it is
 * (a bare degree number alone can't tell bearing from heading; rotation can).
 * Points due north (up) at 0 dp rotation. Renders nothing when the bearing is
 * unknown rather than pointing at a meaningless default.
 */
@Composable
private fun BearingArrow(degrees: Double?, tint: Color, modifier: Modifier = Modifier) {
    if (degrees == null) return
    Canvas(
        modifier = modifier
            .size(11.dp)
            .rotate(degrees.toFloat()),
    ) {
        val path = Path().apply {
            moveTo(size.width / 2f, 0f)
            lineTo(size.width, size.height)
            lineTo(size.width / 2f, size.height * 0.65f)
            lineTo(0f, size.height)
            close()
        }
        drawPath(path, tint)
    }
}

/** Fixed-advance digits — required on every numeric column (spec §Typography). */
internal const val TABULAR_FIGURES = "tnum"

/** Handles for the layout assertions in `AircraftRowLayoutTests`. */
object AircraftRowTags {
    const val IDENTITY = "aircraftRow.identity"
    const val DATA_BLOCK = "aircraftRow.dataBlock"
}

@Composable
fun ProvenanceMark(source: DataSource?) {
    val (glyph, desc) = when (source) {
        DataSource.ALGORITHMIC -> "*" to "algorithmically derived"
        DataSource.DATABASE -> "•" to "from offline database"
        // Network-sourced fields no longer get a mark — Avi doesn't use the
        // provenance distinction and asked for it gone specifically here.
        DataSource.NETWORK, DataSource.DECODED, null -> return
    }
    Text(
        text = glyph,
        fontSize = 10.sp,
        color = AdsbColors.TextDisabled,
        modifier = Modifier.semantics { contentDescription = desc },
    )
}
