package com.laviavi.adsbandroid.ui.text

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.laviavi.adsbandroid.enrich.DataSource
import com.laviavi.adsbandroid.ui.model.AgeTier
import com.laviavi.adsbandroid.ui.model.AircraftRowUi
import com.laviavi.adsbandroid.ui.model.VsArrow
import com.laviavi.adsbandroid.ui.theme.AdsbDimens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Live row's right-hand data block is a layout contract, not a matter of taste:
 * the three columns must read as straight lines down the whole list, and under width
 * pressure the identity text must be what gives way — never the numbers, and never
 * the gap between them.
 *
 * Asserted by measurement rather than by looking at a screenshot, because the
 * failure mode is a few pixels of stagger that is invisible in one row and obvious
 * in twenty.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AircraftRowLayoutTests {

    @get:Rule
    val rule = createComposeRule()

    /** Longest realistic values — the ones that actually push the columns around. */
    private val samples = listOf(
        row(
            icao = "A4B1C2", callsign = "UAL2184", type = "B738",
            registration = "N38901", operator = "United Airlines", route = "LAX → EWR",
            altitude = "FL350", vs = VsArrow.UP, distance = "14.2", bearing = "073°",
        ),
        // A four-digit altitude with a down arrow is wider than `FL350`: this is the
        // row that staggers the column if the tracks are content-sized.
        row(
            icao = "3C6444", callsign = "DLH441", type = "A359",
            registration = "D-AIXA", operator = "Lufthansa", route = "FRA → LAX",
            altitude = "8200", vs = VsArrow.DOWN, distance = "22.7", bearing = "318°",
        ),
        // No enrichment at all — the shortest identity text, which is where a
        // content-sized block would drift furthest right.
        row(
            icao = "A91B03", callsign = null, type = null,
            registration = null, operator = null, route = null,
            altitude = "—", vs = VsArrow.UNKNOWN, distance = "—", bearing = "—",
        ),
    )

    @Test
    fun `columns align and the data block holds its width at 411 dp`() = assertLayout(411.dp)

    @Test
    fun `columns align and the data block holds its width at 360 dp`() = assertLayout(360.dp)

    private fun assertLayout(screenWidth: Dp) {
        rule.setContent {
            Column(modifier = Modifier.requiredWidth(screenWidth)) {
                samples.forEach { AircraftRow(row = it, onClick = {}) }
            }
        }

        val density = rule.density
        fun lefts(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
            .map { it.unclippedBoundsInRoot().left }
        fun rights(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
            .map { it.unclippedBoundsInRoot().right }
        fun widths(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
            .map { it.unclippedBoundsInRoot().width }

        // (a) every row puts each data column at the same x, to the pixel.
        listOf(AircraftRowTags.DIST, AircraftRowTags.ALT, AircraftRowTags.TRACK).forEach { tag ->
            val xs = lefts(tag)
            assertEquals("expected one $tag per row", samples.size, xs.size)
            assertEquals("$tag staggers across rows at $screenWidth: $xs", 1, xs.distinct().size)
        }

        // The tracks are literals, so their widths do not depend on the content.
        assertEquals(
            listOf(AdsbDimens.DataColDist, AdsbDimens.DataColAlt, AdsbDimens.DataColTrack)
                .map { with(density) { it.roundToPx() } },
            listOf(AircraftRowTags.DIST, AircraftRowTags.ALT, AircraftRowTags.TRACK)
                .map { widths(it).distinct().single().toInt() },
        )

        // (b) every row is the same height. Stated as equality rather than as
        // "<= 72 dp" on purpose: Robolectric's text engine is a stub and reports
        // its own line heights, so an absolute dp assertion here would be measuring
        // the stub, not the layout. Equality still catches the failure that matters
        // — one row's cell wrapping while its neighbours' do not. The absolute 72 dp
        // is a device check.
        val heights = rule.onAllNodesWithTag(AircraftRowTags.IDENTITY, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .map { it.layoutInfo.coordinates.parentLayoutCoordinates!!.size.height }
        assertEquals("rows differ in height at $screenWidth: $heights", 1, heights.distinct().size)

        // (c) the gutter survives at every width — the identity text truncates instead.
        val gutterPx = with(density) { AdsbDimens.RowGutter.toPx() }
        rights(AircraftRowTags.IDENTITY).zip(lefts(AircraftRowTags.DIST)).forEach { (idRight, distLeft) ->
            assertTrue(
                "gutter ${distLeft - idRight}px < required ${gutterPx}px at $screenWidth",
                distLeft - idRight >= gutterPx - 1f,
            )
        }
    }

    private fun row(
        icao: String,
        callsign: String?,
        type: String?,
        registration: String?,
        operator: String?,
        route: String?,
        altitude: String,
        vs: VsArrow,
        distance: String,
        bearing: String,
    ) = AircraftRowUi(
        icao = icao,
        callsign = callsign,
        typeCode = type,
        registration = registration,
        registrationMark = registration?.let { DataSource.ALGORITHMIC },
        operator = operator,
        route = route,
        routeMark = route?.let { DataSource.NETWORK },
        altitude = altitude,
        vsArrow = vs,
        speed = "442 kt",
        distance = distance,
        distanceUnit = "mi",
        bearing = bearing,
        signalBars = 2,
        messageCount = "126 msgs",
        age = "2s",
        ageTier = AgeTier.FRESH,
        emergency = false,
        raActive = false,
        raText = null,
        onGround = false,
        hasPosition = true,
    )
}

private fun androidx.compose.ui.semantics.SemanticsNode.unclippedBoundsInRoot() =
    androidx.compose.ui.geometry.Rect(
        offset = positionInRoot,
        size = androidx.compose.ui.geometry.Size(size.width.toFloat(), size.height.toFloat()),
    )
