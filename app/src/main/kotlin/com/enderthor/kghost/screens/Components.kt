package com.enderthor.kghost.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The disabled-content color (38 % onSurface) shared by every screen that dims controls which exist
 * but don't apply in the current mode (e.g. a "Your rides" option while in Fixed-pace mode). One
 * definition so all screens dim identically.
 */
@Composable
fun dimmedContentColor(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)

/**
 * Border for a [androidx.compose.material3.FilterChip] that makes the SELECTED state read on ANY
 * background. The default selected container ([secondaryContainer], a tonal grey) is fine on the page
 * [surface] but blends into a [Card]'s greyer [surfaceVariant] — so a selected chip inside a profile
 * card looks identical to an unselected one. A 2dp primary outline on the selected chip (the
 * unselected chips keep their default thin outline) pops regardless of the container behind it, with
 * no layout cost. Shared so every FilterChip across the Race / per-profile / partner screens marks
 * selection identically.
 */
@Composable
fun selectedChipBorder(selected: Boolean, enabled: Boolean = true): BorderStroke? =
    FilterChipDefaults.filterChipBorder(
        enabled = enabled,
        selected = selected,
        selectedBorderColor = MaterialTheme.colorScheme.primary,
        selectedBorderWidth = 2.dp,
    )

/**
 * A label (+ optional description) on the left and a [Switch] on the right. Shared by the Race,
 * Settings, and per-profile sections so every toggle row looks and sizes the same (48dp touch target).
 *
 * When [enabled] is false the switch is non-interactive and the text dims — used to show a control that
 * exists but doesn't apply in the current mode (e.g. a "Your rides" option while in Fixed-pace mode).
 */
@Composable
fun SwitchRow(
    label: String,
    description: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    val dimmed = dimmedContentColor()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) Color.Unspecified else dimmed,
            )
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else dimmed,
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}
