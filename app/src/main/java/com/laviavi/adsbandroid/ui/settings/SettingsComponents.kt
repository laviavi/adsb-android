package com.laviavi.adsbandroid.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.laviavi.adsbandroid.ui.theme.AdsbColors

/** Opaque bordered panel with a clear header — no translucency over content. */
@Composable
fun SettingsSection(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = AdsbColors.Primary,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = AdsbColors.TextSecondary,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(AdsbColors.Surface)
                .border(1.dp, AdsbColors.Outline, RoundedCornerShape(10.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

/**
 * Single-choice row. Selected state is carried by fill + accent border + text
 * weight, not colour alone, so it reads at a glance and survives glare.
 */
@Composable
fun OptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
) {
    val borderColor = when {
        !enabled -> AdsbColors.Outline.copy(alpha = 0.5f)
        selected -> AdsbColors.Primary
        else -> AdsbColors.Outline
    }
    val labelColor = when {
        !enabled -> AdsbColors.TextDisabled
        selected -> AdsbColors.TextPrimary
        else -> AdsbColors.TextSecondary
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected && enabled) AdsbColors.SurfaceElevated else Color.Transparent)
            .border(if (selected && enabled) 2.dp else 1.dp, borderColor, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            enabled = enabled,
            colors = RadioButtonDefaults.colors(
                selectedColor = AdsbColors.Primary,
                unselectedColor = AdsbColors.OutlineStrong,
                disabledSelectedColor = AdsbColors.TextDisabled,
                disabledUnselectedColor = AdsbColors.TextDisabled,
            ),
            modifier = Modifier.size(24.dp),
        )
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = labelColor)
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (enabled) AdsbColors.TextSecondary else AdsbColors.TextDisabled,
                )
            }
        }
    }
}

/** Labelled on/off control with a visible boundary. */
@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, AdsbColors.Outline, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) AdsbColors.TextPrimary else AdsbColors.TextDisabled,
            )
            if (description != null) {
                Text(description, style = MaterialTheme.typography.labelSmall, color = AdsbColors.TextSecondary)
            }
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = AdsbColors.OnPrimary,
                checkedTrackColor = AdsbColors.Primary,
                uncheckedThumbColor = AdsbColors.TextSecondary,
                uncheckedTrackColor = AdsbColors.Surface,
                uncheckedBorderColor = AdsbColors.OutlineStrong,
            ),
        )
    }
}

/** Text input with an always-visible border and a bright value. */
@Composable
fun SettingsField(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = AdsbColors.TextSecondary) },
        placeholder = placeholder?.let {
            { Text(it, color = AdsbColors.TextDisabled, style = MaterialTheme.typography.bodySmall) }
        },
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = AdsbColors.TextPrimary,
            unfocusedTextColor = AdsbColors.TextPrimary,
            focusedBorderColor = AdsbColors.Primary,
            unfocusedBorderColor = AdsbColors.Outline,
            focusedContainerColor = AdsbColors.SurfaceElevated,
            unfocusedContainerColor = AdsbColors.SurfaceElevated,
            cursorColor = AdsbColors.Primary,
        ),
    )
}

enum class BannerTone { INFO, WARNING, ERROR, SUCCESS }

/** Status/error strip — opaque, bordered, never a faint tint. */
@Composable
fun InfoBanner(text: String, tone: BannerTone = BannerTone.INFO, modifier: Modifier = Modifier) {
    val accent = when (tone) {
        BannerTone.INFO -> AdsbColors.Primary
        BannerTone.WARNING -> AdsbColors.Warning
        BannerTone.ERROR -> AdsbColors.Error
        BannerTone.SUCCESS -> AdsbColors.Success
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(AdsbColors.SurfaceElevated)
            .border(BorderStroke(1.dp, accent), RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = AdsbColors.TextPrimary)
    }
}

/**
 * Row that opens a settings subpage. [value] is the current setting summarised,
 * so the subpage's state is readable without entering it.
 */
@Composable
fun NavigationRow(
    label: String,
    value: String,
    description: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(AdsbColors.SurfaceElevated)
            .border(1.dp, AdsbColors.Outline, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = AdsbColors.TextPrimary)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.labelSmall,
                    color = AdsbColors.TextSecondary)
            }
        }
        Text(value, style = MaterialTheme.typography.bodyMedium, color = AdsbColors.Primary)
        Text("  ›", style = MaterialTheme.typography.bodyLarge, color = AdsbColors.TextSecondary)
    }
    Spacer(Modifier.height(8.dp))
}

/**
 * Stepped slider for a numeric receiver setting.
 *
 * Shows the live value and the default, because these two thresholds can silence
 * the receiver entirely and the way back has to be obvious.
 */
@Composable
fun StepperRow(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    step: Int = 1,
    default: Int,
    description: String? = null,
    onValueChange: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(AdsbColors.SurfaceElevated)
            .border(1.dp, AdsbColors.Outline, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge,
                color = AdsbColors.TextPrimary, modifier = Modifier.weight(1f))
            Text(
                value.toString(),
                style = MaterialTheme.typography.titleMedium,
                color = if (value == default) AdsbColors.TextPrimary else AdsbColors.Warning,
            )
        }
        if (description != null) {
            Text(description, style = MaterialTheme.typography.labelSmall,
                color = AdsbColors.TextSecondary)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Buttons as well as a slider: these need single-step precision, and a
            // 48dp target is not achievable by dragging a slider one step at a time.
            OutlinedButton(
                onClick = { onValueChange((value - step).coerceAtLeast(min)) },
                enabled = value > min,
                modifier = Modifier.semantics { contentDescription = "Decrease $label" },
            ) { Text("−") }
            Slider(
                value = value.toFloat(),
                onValueChange = { onValueChange((it / step).toInt() * step) },
                valueRange = min.toFloat()..max.toFloat(),
                steps = ((max - min) / step - 1).coerceAtLeast(0),
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                colors = SliderDefaults.colors(
                    thumbColor = AdsbColors.Primary,
                    activeTrackColor = AdsbColors.Primary,
                    inactiveTrackColor = AdsbColors.Outline,
                ),
            )
            OutlinedButton(
                onClick = { onValueChange((value + step).coerceAtMost(max)) },
                enabled = value < max,
                modifier = Modifier.semantics { contentDescription = "Increase $label" },
            ) { Text("+") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Range $min–$max · default $default",
                style = MaterialTheme.typography.labelSmall,
                color = AdsbColors.TextSecondary, modifier = Modifier.weight(1f))
            if (value != default) {
                TextButton(onClick = { onValueChange(default) }) {
                    Text("Reset", color = AdsbColors.Primary)
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}

/**
 * Editable Stepper: [ − | typed value | + ] unit  [ Off ]
 *
 * value == 0 means Off. Pressing Off sets value to 0; pressing + from Off
 * jumps to min. − never crosses below min into Off — use the Off button.
 */
@Composable
fun EditableStepperRow(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    step: Int = 1,
    unit: String = "",
    description: String? = null,
    onValueChange: (Int) -> Unit,
) {
    val isOff = value == 0
    var rawText by remember(value) { mutableStateOf(if (isOff) "" else value.toString()) }

    fun commit() {
        val n = rawText.toIntOrNull()
        onValueChange(if (n == null) min else n.coerceIn(min, max))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(AdsbColors.SurfaceElevated)
            .border(1.dp, AdsbColors.Outline, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = AdsbColors.TextPrimary)
        if (description != null) {
            Text(description, style = MaterialTheme.typography.labelSmall, color = AdsbColors.TextSecondary)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, AdsbColors.Outline, RoundedCornerShape(8.dp)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clickable(enabled = !isOff && value > min) {
                            onValueChange((value - step).coerceAtLeast(min))
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "−",
                        style = MaterialTheme.typography.titleLarge,
                        color = if (!isOff && value > min) AdsbColors.TextPrimary else AdsbColors.TextDisabled,
                    )
                }
                Box(Modifier.width(1.dp).height(40.dp).background(AdsbColors.Outline))
                BasicTextField(
                    value = if (isOff) "—" else rawText,
                    onValueChange = { if (!isOff) rawText = it.filter { c -> c.isDigit() } },
                    enabled = !isOff,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleMedium.copy(
                        color = if (isOff) AdsbColors.TextSecondary else AdsbColors.TextPrimary,
                        textAlign = TextAlign.Center,
                    ),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { commit() }),
                    modifier = Modifier
                        .width(56.dp)
                        .onFocusChanged { if (!it.isFocused && !isOff) commit() },
                    decorationBox = { inner ->
                        Box(
                            Modifier.padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) { inner() }
                    },
                )
                Box(Modifier.width(1.dp).height(40.dp).background(AdsbColors.Outline))
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clickable(enabled = isOff || value < max) {
                            onValueChange(if (isOff) min else (value + step).coerceAtMost(max))
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "+",
                        style = MaterialTheme.typography.titleLarge,
                        color = if (isOff || value < max) AdsbColors.TextPrimary else AdsbColors.TextDisabled,
                    )
                }
            }
            if (unit.isNotEmpty()) {
                Text(unit, style = MaterialTheme.typography.bodyMedium, color = AdsbColors.TextSecondary)
            }
            OutlinedButton(
                onClick = { onValueChange(0) },
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (isOff) AdsbColors.Error.copy(alpha = 0.12f) else Color.Transparent,
                    contentColor = if (isOff) AdsbColors.Error else AdsbColors.TextSecondary,
                ),
                border = BorderStroke(1.dp, if (isOff) AdsbColors.Error else AdsbColors.Outline),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                modifier = Modifier.height(40.dp),
            ) { Text("Off") }
        }
    }
    Spacer(Modifier.height(8.dp))
}
