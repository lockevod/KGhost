package com.enderthor.kvpartner.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.enderthor.kvpartner.R
import com.enderthor.kvpartner.data.GapDisplay
import com.enderthor.kvpartner.data.KVPartnerConfig
import com.enderthor.kvpartner.managers.ConfigurationManager
import kotlinx.coroutines.launch

/**
 * Settings tab: data-field display preferences.
 *
 * Switches toggle the graphic and numeric fields; the selector picks which gap metric the
 * fields render. Each change writes straight back through [ConfigurationManager.saveConfig].
 */
@Composable
fun SettingsScreen(
    config: KVPartnerConfig,
    configManager: ConfigurationManager,
) {
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.titleMedium,
        )

        SwitchRow(
            label = stringResource(R.string.settings_show_graphic),
            checked = config.showGraphic,
            onCheckedChange = { scope.launch { configManager.saveConfig(config.copy(showGraphic = it)) } },
        )
        SwitchRow(
            label = stringResource(R.string.settings_show_numeric),
            checked = config.showNumeric,
            onCheckedChange = { scope.launch { configManager.saveConfig(config.copy(showNumeric = it)) } },
        )

        Text(text = stringResource(R.string.settings_gap_display))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GapDisplay.entries.forEach { option ->
                FilterChip(
                    selected = config.gapDisplay == option,
                    onClick = { scope.launch { configManager.saveConfig(config.copy(gapDisplay = option)) } },
                    label = { Text(option.name) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
