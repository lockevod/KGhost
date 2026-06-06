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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.enderthor.kvpartner.R
import com.enderthor.kvpartner.data.KVPartnerConfig
import com.enderthor.kvpartner.engine.GhostPick
import com.enderthor.kvpartner.managers.ConfigurationManager
import kotlinx.coroutines.launch

/**
 * Race tab: settings for the "Race Your Own" feature.
 *
 * Controls bound to [KVPartnerConfig]: [KVPartnerConfig.raceEnabled] master switch,
 * [KVPartnerConfig.ghostPick] selector (Best / Last), [KVPartnerConfig.autoRecord] switch, and
 * [KVPartnerConfig.segmentEntryAlert] switch. Every change writes immediately through
 * [ConfigurationManager.saveConfig]; a false return surfaces a visible error message.
 *
 * @param config         current config (collected once in [MainActivity] and passed down).
 * @param configManager  shared manager — never creates its own DataStore.
 * @param recordedCount  read-only count of stored tracks, or null to hide the info line.
 */
@Composable
fun RaceScreen(
    config: KVPartnerConfig,
    configManager: ConfigurationManager,
    recordedCount: Int? = null,
) {
    val scope = rememberCoroutineScope()
    var saveFailed by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.race_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(text = stringResource(R.string.race_description))

        HorizontalDivider()

        // ── Master switch ─────────────────────────────────────────────────────
        SwitchRow(
            label = stringResource(R.string.race_enabled_label),
            description = stringResource(R.string.race_enabled_description),
            checked = config.raceEnabled,
            onCheckedChange = { enabled ->
                scope.launch {
                    saveFailed = !configManager.saveConfig(config.copy(raceEnabled = enabled))
                }
            },
        )

        HorizontalDivider()

        // ── Ghost selector ────────────────────────────────────────────────────
        Text(
            text = stringResource(R.string.race_ghost_pick_label),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(R.string.race_ghost_pick_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = config.ghostPick == GhostPick.BEST,
                onClick = {
                    scope.launch {
                        saveFailed = !configManager.saveConfig(config.copy(ghostPick = GhostPick.BEST))
                    }
                },
                label = { Text(stringResource(R.string.race_ghost_best)) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
            FilterChip(
                selected = config.ghostPick == GhostPick.LAST,
                onClick = {
                    scope.launch {
                        saveFailed = !configManager.saveConfig(config.copy(ghostPick = GhostPick.LAST))
                    }
                },
                label = { Text(stringResource(R.string.race_ghost_last)) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }

        HorizontalDivider()

        // ── Auto-record switch ────────────────────────────────────────────────
        SwitchRow(
            label = stringResource(R.string.race_auto_record_label),
            description = stringResource(R.string.race_auto_record_description),
            checked = config.autoRecord,
            onCheckedChange = { record ->
                scope.launch {
                    saveFailed = !configManager.saveConfig(config.copy(autoRecord = record))
                }
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

        HorizontalDivider()

        // ── Segment-entry alert switch ────────────────────────────────────────
        SwitchRow(
            label = stringResource(R.string.race_segment_alert_label),
            description = stringResource(R.string.race_segment_alert_description),
            checked = config.segmentEntryAlert,
            onCheckedChange = { alert ->
                scope.launch {
                    saveFailed = !configManager.saveConfig(config.copy(segmentEntryAlert = alert))
                }
            },
        )

        // ── Save-failure notice ───────────────────────────────────────────────
        if (saveFailed) {
            Text(text = stringResource(R.string.settings_save_failed))
        }
    }
}

/**
 * A labelled [Switch] row reused across the Race settings panel.
 *
 * [label] appears in body-medium weight; an optional [description] appears below in smaller,
 * muted text. The switch sits at the end of the row and is the only tap target for toggling.
 */
@Composable
private fun SwitchRow(
    label: String,
    description: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
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
            )
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}
