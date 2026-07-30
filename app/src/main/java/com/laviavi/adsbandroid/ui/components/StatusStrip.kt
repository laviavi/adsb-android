package com.laviavi.adsbandroid.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laviavi.adsbandroid.ui.model.ReceiverState
import com.laviavi.adsbandroid.ui.model.ReceiverStatusUi
import com.laviavi.adsbandroid.ui.theme.AdsbColors
import com.laviavi.adsbandroid.ui.theme.AdsbDimens

@Composable
fun StatusStrip(
    status: ReceiverStatusUi,
    onNavigateToReceiver: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (bgColor, borderColor) = when (status.state) {
        ReceiverState.RUNNING -> AdsbColors.SuccessFill to AdsbColors.Success.copy(alpha = 0.25f)
        ReceiverState.STARTING -> AdsbColors.PrimaryFill to AdsbColors.Primary.copy(alpha = 0.25f)
        ReceiverState.NO_SIGNAL -> AdsbColors.WarningFill to AdsbColors.Warning.copy(alpha = 0.25f)
        ReceiverState.ERROR, ReceiverState.SDR_UNPLUGGED -> AdsbColors.ErrorFill to AdsbColors.Error.copy(alpha = 0.25f)
        else -> AdsbColors.Surface to AdsbColors.SurfaceElevated
    }
    val stateColor = when (status.state) {
        ReceiverState.RUNNING -> AdsbColors.Success
        ReceiverState.STARTING -> AdsbColors.Primary
        ReceiverState.NO_SIGNAL -> AdsbColors.Warning
        ReceiverState.ERROR, ReceiverState.SDR_UNPLUGGED -> AdsbColors.Error
        else -> AdsbColors.TextDisabled
    }
    val stateGlyph = when (status.state) {
        ReceiverState.RUNNING -> "●"
        ReceiverState.STARTING -> "◐"
        ReceiverState.NO_SIGNAL -> "◍"
        ReceiverState.NO_SDR, ReceiverState.STOPPED -> "○"
        ReceiverState.SDR_UNPLUGGED -> "○"
        ReceiverState.ERROR -> "✕"
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(AdsbDimens.StatusStripHeight)
            .background(bgColor)
            .border(width = 1.dp, color = borderColor)
            .clickable(onClick = onNavigateToReceiver)
            .padding(horizontal = AdsbDimens.CardPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AdsbDimens.CardPadding),
    ) {
        Text(
            text = "$stateGlyph ${status.stateLabel}",
            color = stateColor,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.W600,
        )
        if (status.state == ReceiverState.RUNNING) {
            Text(
                text = status.gainDb,
                color = AdsbColors.TextSecondary,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
            Text(
                text = status.uptime,
                color = AdsbColors.TextSecondary,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
            Text(
                text = status.msgRate,
                color = AdsbColors.TextSecondary,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
            Text(
                text = "CRC ${status.crcPercent}",
                color = AdsbColors.Primary,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
        }
    }
}
