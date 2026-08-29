package com.laviavi.adsbandroid.ui.text

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
 * The Live row's right-hand data block is content-width, not the old fixed-dp
 * tracks (§58's redesign traded column-to-column left-edge alignment for a
 * block that spans the row's full height instead of only its top two lines) —
 * so what's still a real contract is narrower than before: every row must be
 * the same height, the data block's right edge must land at the same x on
 * every row (it's the row's trailing edge), and the gutter between identity
 * and the data block must survive under width pressure — the identity text
 * truncates instead of ever invading it.
 *
 * Asserted by measurement rather than by looking at a screenshot, because the
 * failure mode is a few pixels of stagger that is invisible in one row and
 * obvious in twenty.
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
            altitude = "FL350", vs = VsArrow.UP, distance = "14.2", bearing = "073°", bearingDeg = 73.0,
        ),
        // A four-digit altitude with a down arrow is wider than `FL350`: this is the
        // row that would stagger a content-sized block if it were left-aligned.
        row(
            icao = "3C6444", callsign = "DLH441", type = "A359",
            registration = "D-AIXA", operator = "Lufthansa", route = "FRA → LAX",
            altitude = "8200", vs = VsArrow.DOWN, distance = "22.7", bearing = "318°", bearingDeg = 318.0,
        ),
        // No enrichment at all — the shortest identity text, which is where a
        // ragged block would drift furthest from its neighbours.
        row(
            icao = "A91B03", callsign = null, type = null,
            registration = null, operator = null, route = null,
            altitude = "—", vs = VsArrow.UNKNOWN, distance = "—", bearing = "—", bearingDeg = null,
        ),
    )

    @Test
    fun `rows align and hold their height at 411 dp`() = assertLayout(411.dp)

    @Test
    fun `rows align and hold their height at 360 dp`() = assertLayout(360.dp)

    private fun assertLayout(screenWidth: Dp) {
        rule.setContent {
            // Scrollable so the Column measures at its natural height regardless of the
            // Robolectric root's virtual screen height — matches the real list, which is a
            // LazyColumn and never height-constrains an off-screen row's measurement either.
            // Without this, stacking enough rows to exceed that bound silently clamps the
            // last row's last line, which is a test-harness artifact, not a real layout bug.
            Column(modifier = Modifier.requiredWidth(screenWidth).verticalScroll(rememberScrollState())) {
                samples.forEach { AircraftRow(row = it, onClick = {}) }
            }
        }

        val density = rule.density
        fun lefts(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
            .map { it.unclippedBoundsInRoot().left }
        fun rights(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
            .map { it.unclippedBoundsInRoot().right }

        // (a) the data block's right edge — the row's trailing edge — lands at the
        // same x on every row, regardless of how wide its content happens to be.
        val blockRights = rights(AircraftRowTags.DATA_BLOCK)
        assertEquals("expected one data block per row", samples.size, blockRights.size)
        assertEquals("data block right edge staggers across rows at $screenWidth: $blockRights", 1, blockRights.distinct().size)

        // (b) every row is the same height. Stated as equality rather than as
        // "<= 72 dp" on purpose: Robolectric's text engine is a stub and reports
        // its own line heights, so an absolute dp assertion here would be measuring
        // the stub, not the layout. Equality still catches the failure that matters
        // — one row's cell wrapping while its neighbours' do not.
        val heights = rule.onAllNodesWithTag(AircraftRowTags.IDENTITY, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .map { it.layoutInfo.coordinates.parentLayoutCoordinates!!.size.height }
        assertEquals("rows differ in height at $screenWidth: $heights", 1, heights.distinct().size)

        // (c) the gutter survives at every width — the identity text truncates instead.
        val gutterPx = with(density) { AdsbDimens.RowGutter.toPx() }
        rights(AircraftRowTags.IDENTITY).zip(lefts(AircraftRowTags.DATA_BLOCK)).forEach { (idRight, blockLeft) ->
            assertTrue(
                "gutter ${blockLeft - idRight}px < required ${gutterPx}px at $screenWidth",
                blockLeft - idRight >= gutterPx - 1f,
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
        bearingDeg: Double?,
    ) = AircraftRowUi(
        icao = icao,
        callsign = callsign,
        typeCode = type,
        registration = registration,
        registrationMark = registration?.let { DataSource.ALGORITHMIC },
        operator = operator,
        operatorKind = operator?.let { com.laviavi.adsbandroid.enrich.OperatorKind.AIRLINE },
        route = route,
        routeMark = route?.let { DataSource.NETWORK },
        altitude = altitude,
        vsArrow = vs,
        speed = "442 kt",
        distance = distance,
        distanceUnit = "mi",
        bearing = bearing,
        bearingDeg = bearingDeg,
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
