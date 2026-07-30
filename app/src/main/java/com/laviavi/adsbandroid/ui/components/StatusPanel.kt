package com.laviavi.adsbandroid.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laviavi.adsbandroid.ui.theme.AdsbColors
import com.laviavi.adsbandroid.ui.theme.AdsbDimens

@Composable
fun StatusPanel(
    icon: @Composable () -> Unit,
    headline: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(AdsbDimens.SpacingXxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AdsbDimens.SpacingLg),
    ) {
        icon()
        Text(
            text = headline,
            fontSize = 17.sp,
            fontWeight = FontWeight.W600,
            color = AdsbColors.TextPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = body,
            fontSize = 13.sp,
            color = AdsbColors.TextSecondary,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp,
        )
        if (action != null) {
            Spacer(Modifier.height(AdsbDimens.SpacingSm))
            action()
        }
    }
}
