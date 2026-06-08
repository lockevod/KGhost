package com.enderthor.kvpartner.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.enderthor.kvpartner.R
import com.enderthor.kvpartner.data.KVPartnerConfig
import com.enderthor.kvpartner.data.MAX_TARGET_SPEED_MS
import com.enderthor.kvpartner.data.kmhToMs
import com.enderthor.kvpartner.data.paceMinKmToMs
import com.enderthor.kvpartner.managers.ConfigurationManager
import kotlinx.coroutines.launch
import java.util.Locale

/** Whether the target is entered as a speed or as a pace. */
private enum class TargetMode { SPEED, PACE }

/**
 * Virtual Partner section: enter the fixed-pace target as speed (km/h) or pace (min/km).
 *
 * This is one half of the unified ghost (see [SettingsScreen]): the Virtual Partner pace is what the
 * ghost runs at on stretches with no recorded history, and what it uses when no route is loaded.
 *
 * The toggle picks the entry mode; on save the value is converted to m/s via [kmhToMs] or
 * [paceMinKmToMs] and stored in [KVPartnerConfig.targetSpeedMs]. The target must be > 0 to be saved;
 * a blank or non-positive value persists 0.0, which keeps the Virtual Partner inactive (the engine
 * emits `GapState.inactive()` while the target is 0).
 *
 * Emitted as direct children of the caller's scrolling Column (no wrapper here), so it stacks with
 * the Race section under one scroll. Spacing comes from the parent Column's arrangement.
 */
@Composable
fun PartnerSection(
    config: KVPartnerConfig,
    configManager: ConfigurationManager,
) {
    val scope = rememberCoroutineScope()

    var mode by remember { mutableStateOf(TargetMode.SPEED) }
    var targetText by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }

    val currentMs = config.targetSpeedMs
    val currentLabel = if (currentMs > 0.0) {
        val kmh = currentMs * 3.6
        val paceMinKm = if (currentMs > 0.0) 1000.0 / currentMs / 60.0 else 0.0
        String.format(Locale.US, "%.1f km/h  /  %.2f min/km", kmh, paceMinKm)
    } else {
        stringResource(R.string.partner_target_none)
    }

    // Seed the input from the stored target in the CURRENTLY selected unit, and re-seed whenever the
    // unit flips or the stored value changes (after Save/Clear). This (a) prefills the field so the
    // rider sees what's actually stored instead of a blank box, and (b) reinterprets the value into
    // the new unit on a SPEED⇄PACE toggle — without this, a "25" typed as km/h would be read as
    // 25 min/km on the next Save and silently persist a ~2.4 km/h pace. Keyed on (mode, currentMs)
    // only, so it does NOT clobber the rider's in-progress typing on unrelated config emissions.
    LaunchedEffect(mode, currentMs) {
        targetText = if (currentMs > 0.0) {
            when (mode) {
                TargetMode.SPEED -> String.format(Locale.US, "%.1f", currentMs * 3.6)
                TargetMode.PACE -> String.format(Locale.US, "%.2f", 1000.0 / currentMs / 60.0)
            }
        } else {
            ""
        }
        status = null
    }

    Text(text = stringResource(R.string.partner_title), style = MaterialTheme.typography.titleMedium)
    Text(
        text = stringResource(R.string.partner_description),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(text = stringResource(R.string.partner_current_target, currentLabel))

    // Mode toggle: speed vs pace.
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = mode == TargetMode.SPEED,
            onClick = { mode = TargetMode.SPEED },
            label = { Text(stringResource(R.string.partner_mode_speed)) },
            modifier = Modifier.heightIn(min = 48.dp),
        )
        FilterChip(
            selected = mode == TargetMode.PACE,
            onClick = { mode = TargetMode.PACE },
            label = { Text(stringResource(R.string.partner_mode_pace)) },
            modifier = Modifier.heightIn(min = 48.dp),
        )
    }

    val fieldLabel = when (mode) {
        TargetMode.SPEED -> stringResource(R.string.partner_input_speed)
        TargetMode.PACE -> stringResource(R.string.partner_input_pace)
    }
    OutlinedTextField(
        value = targetText,
        onValueChange = { targetText = it; status = null },
        label = { Text(fieldLabel) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )

    // Save + Clear side by side on one line (each half-width) instead of two stacked full-width
    // buttons. Clear is an OutlinedButton so the primary Save reads as the main action.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = {
                val value = targetText.replace(',', '.').trim().toDoubleOrNull()
                val targetMs = when (mode) {
                    TargetMode.SPEED -> value?.let { kmhToMs(it) } ?: 0.0
                    TargetMode.PACE -> value?.let { paceMinKmToMs(it) } ?: 0.0
                }
                // Only persist a finite, positive, physically-plausible target. [MAX_TARGET_SPEED_MS]
                // (≈108 km/h) is a generous cycling ceiling; this also blocks "1e400"→Infinity and
                // pathological pace inputs that would otherwise produce a non-finite or absurd m/s.
                if (targetMs.isFinite() && targetMs > 0.0 && targetMs <= MAX_TARGET_SPEED_MS) {
                    scope.launch {
                        val ok = configManager.updateConfig { it.copy(targetSpeedMs = targetMs) }
                        status = if (ok) null else "saveFailed"
                    }
                } else {
                    status = "invalid"
                }
            },
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.partner_save), maxLines = 1)
        }
        OutlinedButton(
            onClick = {
                scope.launch {
                    val ok = configManager.updateConfig { it.copy(targetSpeedMs = 0.0) }
                    if (ok) {
                        targetText = ""
                        status = null
                    } else {
                        status = "saveFailed"
                    }
                }
            },
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.partner_clear), maxLines = 1)
        }
    }

    if (status == "invalid") {
        Text(text = stringResource(R.string.partner_invalid))
    }
    if (status == "saveFailed") {
        Text(text = stringResource(R.string.settings_save_failed))
    }
}
