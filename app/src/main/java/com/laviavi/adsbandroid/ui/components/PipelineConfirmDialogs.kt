package com.laviavi.adsbandroid.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * Confirms before stopping the receiver — same copy and semantics wherever
 * Start/Stop appears (Traffic, Receiver), so the two screens can't drift
 * apart the way they previously did.
 */
@Composable
fun StopConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Stop receiving?") },
        text = { Text("The session and its counters end.") },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text("Stop") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Confirms before reconnecting — a reconnect ends the session same as Stop does. */
@Composable
fun ReconnectConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reconnect?") },
        text = { Text("The session and its counters end, and the receiver restarts.") },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text("Reconnect") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Confirms before fully exiting — stronger than Stop: releases every resource and closes the app. */
@Composable
fun ExitConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Exit the app?") },
        text = { Text("The receiver stops, every open connection closes, and the app closes completely.") },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text("Exit") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
