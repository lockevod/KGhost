package com.enderthor.kghost.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.enderthor.kghost.FileLogTree
import com.enderthor.kghost.R
import com.enderthor.kghost.data.KGhostConfig
import com.enderthor.kghost.managers.ConfigurationManager
import kotlinx.coroutines.launch

/**
 * The "Settings" tab: device-level toggles that are NOT per-profile — the master kill-switch and the
 * file-logging diagnostic. Kept separate from the per-profile Ghost Pace tab on purpose.
 */
@Composable
fun AppSettingsScreen(
    config: KGhostConfig,
    configManager: ConfigurationManager,
) {
    val scope = rememberCoroutineScope()
    var saveFailed by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SwitchRow(
            label = stringResource(R.string.master_enabled_label),
            description = stringResource(R.string.master_enabled_description),
            checked = config.masterEnabled,
            onCheckedChange = { on ->
                scope.launch { saveFailed = !configManager.updateConfig { it.copy(masterEnabled = on) } }
            },
        )

        SwitchRow(
            label = stringResource(R.string.tidy_label),
            description = stringResource(R.string.tidy_description),
            checked = config.autoTidy,
            onCheckedChange = { on ->
                scope.launch { saveFailed = !configManager.updateConfig { it.copy(autoTidy = on) } }
            },
        )

        HorizontalDivider()

        SwitchRow(
            label = stringResource(R.string.race_filelog_label),
            description = stringResource(R.string.race_filelog_description),
            checked = config.fileLogging,
            onCheckedChange = { on ->
                FileLogTree.enabled = on
                scope.launch { saveFailed = !configManager.updateConfig { it.copy(fileLogging = on) } }
            },
        )
        if (config.fileLogging) {
            Text(
                text = stringResource(R.string.race_filelog_path, FileLogTree.pathHint()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (saveFailed) {
            Text(text = stringResource(R.string.settings_save_failed))
        }
    }
}
