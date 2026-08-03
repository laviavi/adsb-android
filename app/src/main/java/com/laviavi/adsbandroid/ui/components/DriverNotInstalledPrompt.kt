package com.laviavi.adsbandroid.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.laviavi.adsbandroid.capture.UsbHotplugReceiver

/**
 * Dialog shown when USB source is selected but driver is not installed.
 * Tapping "Install Driver" opens Play Store directly to the driver app.
 * Triggered automatically when dongle is plugged in without driver.
 */
@Composable
fun DriverNotInstalledPrompt(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(Icons.Default.Warning, contentDescription = null,
                tint = MaterialTheme.colorScheme.error)
        },
        title = { Text("RTL-SDR Driver Required", textAlign = TextAlign.Center) },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "To use your RTL-SDR Blog V4 dongle, install the free " +
                    "RTL-SDR driver app from the Play Store.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "After installing, plug in your dongle and the app connects automatically.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = { UsbHotplugReceiver.openDriverInstallPage(context) }) { Text("Install Driver") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Not now") }
        },
    )
}
