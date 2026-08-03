package com.laviavi.adsbandroid.ui.offline

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.laviavi.adsbandroid.offline.FileManifestStore
import com.laviavi.adsbandroid.offline.FileTileStore
import com.laviavi.adsbandroid.offline.LocalOnlyTileDownloader
import com.laviavi.adsbandroid.offline.LocalTileSource
import com.laviavi.adsbandroid.offline.NetworkEligibility
import com.laviavi.adsbandroid.offline.NetworkState
import com.laviavi.adsbandroid.offline.OfflineMapManager
import com.laviavi.adsbandroid.offline.OfflineRadius
import com.laviavi.adsbandroid.offline.SystemOfflineClock
import com.laviavi.adsbandroid.offline.TileRef
import com.laviavi.adsbandroid.offline.UuidGenerator
import com.laviavi.adsbandroid.pipeline.AppConfigStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Reproduces "Settings -> Manage offline maps" end to end against real Android-backed
 * adapters (Robolectric), without touching a device. If the screen crashes on open or
 * on the first couple of taps, this fails instead of the app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineMapsScreenSmokeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private fun manager(dir: File) = OfflineMapManager(
        store = FileManifestStore(dir),
        tiles = FileTileStore(dir),
        downloader = LocalOnlyTileDownloader(FileTileStore(dir)),
        eligibility = object : NetworkEligibility { override fun currentState() = NetworkState.WIFI_UNMETERED },
        clock = SystemOfflineClock(),
        ids = UuidGenerator(),
    )

    private fun newViewModel(dir: File): OfflineMapsViewModel {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cacheSource = object : LocalTileSource {
            override fun availableTiles(): Set<TileRef> = emptySet()
            override suspend fun fetch(tile: TileRef): ByteArray? = null
        }
        return OfflineMapsViewModel(
            manager = manager(dir),
            eligibility = object : NetworkEligibility { override fun currentState() = NetworkState.WIFI_UNMETERED },
            cacheSource = cacheSource,
            configStore = AppConfigStore(context),
        )
    }

    @Test
    fun `screen opens with no saved maps and the download sheet works`() {
        val dir = tmpFolder.newFolder()
        val viewModel = newViewModel(dir)

        composeRule.setContent {
            OfflineMapsScreen(observerLat = 33.95, observerLon = -117.33, onBack = {}, viewModel = viewModel)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Offline maps").assertExists()
        composeRule.onNodeWithText("No offline maps yet. Download one to use the map without a connection.")
            .assertExists()

        composeRule.onNodeWithText("Add an offline map").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Choose a radius").assertExists()
        composeRule.onNodeWithText(OfflineRadius.entries.first().label).performClick()
        composeRule.waitForIdle()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h891dp")
    fun `screen with an existing saved map supports select and delete without crashing`() {
        val dir = tmpFolder.newFolder()
        val viewModel = newViewModel(dir)
        runBlocking { manager(dir).downloadNew(33.95, -117.33, OfflineRadius.entries.first()) }
        viewModel.refresh()
        // refresh() dispatches to Dispatchers.IO — wait for the real background write/read
        // to land before composing, instead of racing it.
        runBlocking { withTimeout(5_000) { while (viewModel.uiState.value.segments.isEmpty()) delay(20) } }

        composeRule.setContent {
            OfflineMapsScreen(observerLat = 33.95, observerLon = -117.33, onBack = {}, viewModel = viewModel)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Select").performClick()
        composeRule.waitForIdle()

        // The seeded segment is named for its coordinates since there is no LocationNamer.
        val segmentName = viewModel.uiState.value.segments.single().displayName
        composeRule.onNodeWithText(segmentName).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Delete 1 selected").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Delete 1 offline map(s)?").assertExists()
        composeRule.onNodeWithText("Delete").performClick()
        composeRule.waitForIdle()
    }
}
