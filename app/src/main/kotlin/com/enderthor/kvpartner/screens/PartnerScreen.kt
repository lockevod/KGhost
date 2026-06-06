package com.enderthor.kvpartner.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.enderthor.kvpartner.data.Units
import com.enderthor.kvpartner.data.kmhToMs
import com.enderthor.kvpartner.data.paceMinKmToMs
import com.enderthor.kvpartner.managers.ConfigurationManager
import kotlinx.coroutines.launch

/** Whether the target is entered as a speed or as a pace. */
private enum class TargetMode { SPEED, PACE }

/**
 * Partner tab: enter the Virtual Partner target as speed (km/h) or pace (min/km).
 *
 * The toggle picks the entry mode; on save the value is converted to m/s via [kmhToMs] or
 * [paceMinKmToMs] and stored in [KVPartnerConfig.targetSpeedMs]. The target must be > 0 to be
 * saved; a blank or non-positive value persists 0.0, which keeps the Virtual Partner inactive
 * (the engine emits `GapState.inactive()` while the target is 0).
 */
@Composable
fun PartnerScreen(
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
        "%.1f km/h  /  %.2f min/km".format(kmh, paceMinKm)
    } else {
        stringResource(R.string.partner_target_none)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = stringResource(R.string.partner_title), style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
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

        // Units selector (METRIC / IMPERIAL).
        Text(text = stringResource(R.string.partner_units))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Units.entries.forEach { unit ->
                FilterChip(
                    selected = config.units == unit,
                    onClick = { scope.launch { configManager.saveConfig(config.copy(units = unit)) } },
                    label = { Text(unit.name) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }

        Button(
            onClick = {
                val value = targetText.replace(',', '.').trim().toDoubleOrNull()
                val targetMs = when (mode) {
                    TargetMode.SPEED -> value?.let { kmhToMs(it) } ?: 0.0
                    TargetMode.PACE -> value?.let { paceMinKmToMs(it) } ?: 0.0
                }
                if (targetMs > 0.0) {
                    scope.launch { configManager.saveConfig(config.copy(targetSpeedMs = targetMs)) }
                    status = null
                } else {
                    status = "invalid"
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.partner_save))
        }

        if (status == "invalid") {
            Text(text = stringResource(R.string.partner_invalid))
        }

        Button(
            onClick = {
                scope.launch { configManager.saveConfig(config.copy(targetSpeedMs = 0.0)) }
                targetText = ""
                status = null
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.partner_clear))
        }
    }
}
