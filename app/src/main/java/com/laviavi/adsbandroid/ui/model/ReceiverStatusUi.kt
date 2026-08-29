package com.laviavi.adsbandroid.ui.model

import androidx.compose.runtime.Immutable

@Immutable
data class ReceiverStatusUi(
    val state: ReceiverState = ReceiverState.STOPPED,
    val stateLabel: String = "STOPPED",
    val uptime: String = "00:00:00",
    val msgRate: String = "0",
    val validPercent: String = "0%",
    val sourceName: String? = null,
    val errorMessage: String? = null,
)

enum class ReceiverState {
    RUNNING, STARTING, NO_SIGNAL, NO_SDR, SDR_UNPLUGGED, ERROR, STOPPED
}
