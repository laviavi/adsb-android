package com.laviavi.adsbandroid.ui.text

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.laviavi.adsbandroid.pipeline.AppConfig
import com.laviavi.adsbandroid.ui.MainViewModel
import com.laviavi.adsbandroid.ui.history.HistoryScreen
import com.laviavi.adsbandroid.ui.stats.StatsScreen
import com.laviavi.adsbandroid.ui.theme.AdsbColors
import kotlinx.coroutines.launch

private enum class TrafficTab(val label: String) { LIVE("Live"), HISTORY("History"), STATS("Stats") }

/**
 * Traffic destination: the live start section (tuner chip, title, Start/Stop)
 * stays fixed at top since it applies regardless of which sub-tab is open;
 * Live (default), History, and Stats sit below it as swipeable pager pages,
 * kept in sync with the tab row. Each tab's content is the pre-existing
 * screen, unchanged.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TrafficScreen(
    viewModel: MainViewModel,
    onAircraftClick: (String) -> Unit,
    onNavigateToReceiver: () -> Unit,
    onShowOnMap: (String) -> Unit,
    onConfigChange: (AppConfig) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onReconnect: () -> Unit,
    onResetCounters: () -> Unit,
    onClearHistory: () -> Unit,
    onShareHistory: () -> Unit,
    onShareEventLog: () -> Unit,
    onShareHistoryDebug: () -> Unit, // TEMP DEBUG: history investigation — delete with the rest
    onCheckGlobalDb: (onResult: (String) -> Unit) -> Unit,
    onExit: () -> Unit,
) {
    val history by viewModel.history.collectAsStateWithLifecycle()
    val visits by viewModel.visits.collectAsStateWithLifecycle()
    val sourceState by viewModel.sourceState.collectAsStateWithLifecycle()

    val pagerState = rememberPagerState { TrafficTab.entries.size }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize().background(AdsbColors.Background)) {
        LiveTopBar(
            sourceState = sourceState,
            onStart = onStart,
            onStop = onStop,
            onReconnect = onReconnect,
            onResetCounters = onResetCounters,
            onExit = onExit,
        )

        TabRow(
            selectedTabIndex = pagerState.currentPage,
            containerColor = AdsbColors.Surface,
            contentColor = AdsbColors.Primary,
        ) {
            TrafficTab.entries.forEachIndexed { index, t ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                    text = { Text(t.label, fontSize = 12.sp, fontWeight = FontWeight.W600) },
                )
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { page ->
            when (TrafficTab.entries[page]) {
                TrafficTab.LIVE -> LiveBody(
                    viewModel = viewModel,
                    onAircraftClick = onAircraftClick,
                    onNavigateToReceiver = onNavigateToReceiver,
                    onShowOnMap = onShowOnMap,
                    onConfigChange = onConfigChange,
                    onStart = onStart,
                    sourceState = sourceState,
                )
                TrafficTab.HISTORY -> HistoryScreen(
                    entries = history, onClear = onClearHistory, onShare = onShareHistory, onShareEventLog = onShareEventLog,
                    onShareHistoryDebug = onShareHistoryDebug, onCheckGlobalDb = onCheckGlobalDb,
                )
                TrafficTab.STATS -> StatsScreen(visits = visits)
            }
        }
    }
}
