package com.laviavi.adsbandroid.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laviavi.adsbandroid.pipeline.PipelineStats
import java.util.concurrent.TimeUnit

@Composable
fun StatsBar(stats: PipelineStats.Snapshot, aircraftCount: Int, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            StatChip("AC", aircraftCount.toString())
            StatChip("Total", stats.totalMessages.toString())
            StatChip("Valid", stats.validMessages.toString())
            StatChip("msg/s", "%.0f".format(stats.messagesPerSecond))
            StatChip("Up", formatUptime(stats.uptimeMs))
        }
    }
}

@Composable
private fun StatChip(label: String, value: String) {
    Text("$label: $value", style = MaterialTheme.typography.labelSmall, fontSize = 11.sp)
}

private fun formatUptime(ms: Long): String {
    val h = TimeUnit.MILLISECONDS.toHours(ms)
    val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
    val s = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
    return "%02d:%02d:%02d".format(h, m, s)
}
