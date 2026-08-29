package com.laviavi.adsbandroid.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.ui.graphics.vector.ImageVector

enum class AdsbDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    LIVE("live", "Traffic", Icons.Outlined.List),
    MAP("map", "Map", Icons.Outlined.Map),
    RECEIVER("receiver", "Receiver", Icons.Outlined.Radar),
    SETTINGS("settings", "Settings", Icons.Outlined.Tune),
}
