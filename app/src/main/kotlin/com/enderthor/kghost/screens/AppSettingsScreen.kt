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
import androidx.compose.runtime.LaunchedEffect
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
 * The "Settings" tab: device-level + track-library management that is NOT per-profile — the master
 * kill-switch, the recorded-track library (auto-clean, record, import), and the diagnostic log. Kept
 * separate from the per-profile Ghost Pace tab on purpose.
 */
@Composable
fun AppSettingsScreen(
    config: KGhostConfig,
    configManager: ConfigurationManager,
    recordedCount: Int? = null,
    onTracksChanged: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var saveFailed by remember { mutableStateOf(false) }
    // The stable "Anon tag" (install id) — fetched once so the rider can read/quote it when reporting.
    var anonTag by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { anonTag = runCatching { configManager.getOrCreateInstallId() }.getOrNull() }

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ── Master switch ─────────────────────────────────────────────────────
        SwitchRow(
            label = stringResource(R.string.master_enabled_label),
            description = stringResource(R.string.master_enabled_description),
            checked = config.masterEnabled,
            onCheckedChange = { on ->
                scope.launch { saveFailed = !configManager.updateConfig { it.copy(masterEnabled = on) } }
            },
        )

        HorizontalDivider()

        // ── Recorded-track library: auto-clean, record, import ────────────────
        SwitchRow(
            label = stringResource(R.string.tidy_label),
            description = stringResource(R.string.tidy_description),
            checked = config.autoTidy,
            onCheckedChange = { on ->
                scope.launch { saveFailed = !configManager.updateConfig { it.copy(autoTidy = on) } }
            },
        )

        SwitchRow(
            label = stringResource(R.string.race_auto_record_label),
            description = stringResource(R.string.race_auto_record_description),
            checked = config.autoRecord,
            onCheckedChange = { record ->
                scope.launch { saveFailed = !configManager.updateConfig { it.copy(autoRecord = record) } }
            },
        )

        // Track count info line (shown only when the count is available).
        if (recordedCount != null) {
            Text(
                text = stringResource(R.string.race_recorded_tracks, recordedCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        ImportSection(
            config = config,
            configManager = configManager,
            scope = scope,
            onTracksChanged = onTracksChanged,
        )

        HorizontalDivider()

        // ── Diagnostics: file logging ─────────────────────────────────────────
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
            anonTag?.let { tag ->
                Text(
                    text = stringResource(R.string.race_filelog_anontag, tag),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = stringResource(R.string.race_filelog_upload_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (saveFailed) {
            Text(text = stringResource(R.string.settings_save_failed))
        }
    }
}
