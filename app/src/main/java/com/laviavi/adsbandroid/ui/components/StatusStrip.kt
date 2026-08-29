package com.laviavi.adsbandroid.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    // Deliberately binary, unlike bgColor/borderColor above — the dongle identity
    // itself only ever needs to answer "is it actually running or not".
    val dongleColor = if (status.state == ReceiverState.RUNNING) AdsbColors.Success else AdsbColors.Error

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(AdsbDimens.StatusStripHeight)
            .background(bgColor)
            .border(width = 1.dp, color = borderColor)
            .clickable(onClick = onNavigateToReceiver)
            .padding(horizontal = AdsbDimens.CardPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AdsbDimens.SpacingLg),
    ) {
        Icon(Icons.Outlined.Usb, contentDescription = null, modifier = Modifier.size(14.dp), tint = dongleColor)
        Text(
            text = status.sourceName ?: status.stateLabel,
            color = dongleColor,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.W600,
        )
        if (status.state == ReceiverState.RUNNING) {
            StatusField("RUN TIME", status.uptime)
            StatusField("FRAMES/S", status.msgRate)
            StatusField("VALID", status.validPercent, valueColor = AdsbColors.Success)
        }
    }
}

@Composable
private fun StatusField(label: String, value: String, valueColor: Color = AdsbColors.TextSecondary) {
    Column {
        Text(label, fontSize = 8.sp, letterSpacing = 0.4.sp, color = AdsbColors.TextDisabled)
        Text(value, fontSize = 12.sp, color = valueColor, fontFamily = FontFamily.Monospace)
    }
}
