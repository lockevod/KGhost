package com.enderthor.kghost.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.enderthor.kghost.R
import com.enderthor.kghost.data.GhostIcon
import com.enderthor.kghost.data.KGhostConfig
import com.enderthor.kghost.engine.GhostPick
import com.enderthor.kghost.geo.TrackStore
import com.enderthor.kghost.geo.TrackStorage
import com.enderthor.kghost.import_.HistoryImporter
import com.enderthor.kghost.import_.ImportProgress
import com.enderthor.kghost.managers.ConfigurationManager
import com.enderthor.kghost.managers.StoragePermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * Race tab: settings for the "Race Your Own" feature.
 *
 * Controls bound to [KGhostConfig]: [KGhostConfig.raceEnabled] master switch,
 * [KGhostConfig.ghostPick] selector (Best / Last), [KGhostConfig.autoRecord] switch, and
 * [KGhostConfig.segmentEntryAlert] switch. Every change writes immediately through
 * [ConfigurationManager.saveConfig]; a false return surfaces a visible error message.
 *
 * @param config         current config (collected once in [MainActivity] and passed down).
 * @param configManager  shared manager — never creates its own DataStore.
 * @param recordedCount  read-only count of stored tracks, or null to hide the info line.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RaceSection(
    config: KGhostConfig,
    configManager: ConfigurationManager,
    recordedCount: Int? = null,
    onTracksChanged: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var saveFailed by remember { mutableStateOf(false) }

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
                    saveFailed = !configManager.updateConfig { it.copy(raceEnabled = enabled) }
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
                        saveFailed = !configManager.updateConfig { it.copy(ghostPick = GhostPick.BEST) }
                    }
                },
                label = { Text(stringResource(R.string.race_ghost_best)) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
            FilterChip(
                selected = config.ghostPick == GhostPick.LAST,
                onClick = {
                    scope.launch {
                        saveFailed = !configManager.updateConfig { it.copy(ghostPick = GhostPick.LAST) }
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
                    saveFailed = !configManager.updateConfig { it.copy(autoRecord = record) }
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
                    saveFailed = !configManager.updateConfig { it.copy(segmentEntryAlert = alert) }
                }
            },
        )

        HorizontalDivider()

        // ── Segment-exit alert switch ─────────────────────────────────────────
        SwitchRow(
            label = stringResource(R.string.race_segment_exit_label),
            description = stringResource(R.string.race_segment_exit_description),
            checked = config.segmentExitAlert,
            onCheckedChange = { alert ->
                scope.launch {
                    saveFailed = !configManager.updateConfig { it.copy(segmentExitAlert = alert) }
                }
            },
        )

        HorizontalDivider()

        // ── Show-ghost-on-map switch ──────────────────────────────────────────
        SwitchRow(
            label = stringResource(R.string.race_show_ghost_on_map),
            description = stringResource(R.string.race_show_ghost_on_map_desc),
            checked = config.showGhostOnMap,
            onCheckedChange = { show ->
                scope.launch {
                    saveFailed = !configManager.updateConfig { it.copy(showGhostOnMap = show) }
                }
            },
        )

        // ── Ghost icon picker ─────────────────────────────────────────────────
        Text(
            text = stringResource(R.string.race_ghost_icon_label),
            style = MaterialTheme.typography.bodyMedium,
        )
        // Fixed 2-column grid: every chip gets equal width (weight) so the four boxes line up in a
        // tidy 2×2 block instead of each sizing to its own label width. verticalArrangement keeps the
        // wrapped second row from gluing to the first on the narrow Karoo screen.
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            maxItemsInEachRow = 2,
        ) {
            data class IconChoice(val icon: GhostIcon, val labelRes: Int)
            listOf(
                IconChoice(GhostIcon.GHOST, R.string.race_ghost_icon_ghost),
                IconChoice(GhostIcon.CYCLIST, R.string.race_ghost_icon_cyclist),
                IconChoice(GhostIcon.ARROW, R.string.race_ghost_icon_arrow),
                IconChoice(GhostIcon.DOT, R.string.race_ghost_icon_dot),
            ).forEach { choice ->
                FilterChip(
                    selected = config.ghostIcon == choice.icon,
                    onClick = {
                        scope.launch {
                            saveFailed = !configManager.updateConfig { it.copy(ghostIcon = choice.icon) }
                        }
                    },
                    label = { Text(stringResource(choice.labelRes)) },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                )
            }
        }

        // Ghost icon SIZE is automatic — it follows the map zoom level (OnMapZoomLevel), so there is
        // no manual size picker.

        HorizontalDivider()

        // ── History import ────────────────────────────────────────────────────
        ImportSection(
            config = config,
            configManager = configManager,
            scope = scope,
            onTracksChanged = onTracksChanged,
        )

        // ── Save-failure notice ───────────────────────────────────────────────
        if (saveFailed) {
            Text(text = stringResource(R.string.settings_save_failed))
        }
}

/**
 * "Import history" section. Gated on all-files storage access: when missing it shows a permission
 * card; once granted it offers a full import and a "new only" import, both cancelable, with a live
 * progress line and a final summary.
 *
 * The [HistoryImporter] is wired to the SAME on-disk locations the extension uses:
 *   - tracks land in `context.filesDir/tracks` (mirrors [KGhostExtension]'s TrackStore dir, so ②
 *     reads exactly what is imported here),
 *   - sources are scanned from `/sdcard/FitFiles` and `/sdcard/KGhost`.
 *
 * The last-scan epoch (for "new only") is persisted in [KGhostConfig.lastScanEpoch] via
 * [ConfigurationManager]. The importer's synchronous `lastScanSetter` launches a save on [scope].
 */
@Composable
private fun ImportSection(
    config: KGhostConfig,
    configManager: ConfigurationManager,
    scope: CoroutineScope,
    onTracksChanged: () -> Unit = {},
) {
    val context = LocalContext.current

    // Re-check permission whenever the screen resumes so granting it elsewhere and returning here
    // flips the UI from the permission card to the import buttons without a manual refresh.
    var hasAccess by remember { mutableStateOf(StoragePermission.hasAllFilesAccess(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasAccess = StoragePermission.hasAllFilesAccess(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var progress by remember { mutableStateOf<ImportProgress?>(null) }
    var canceled by remember { mutableStateOf(false) }
    var importJob by remember { mutableStateOf<Job?>(null) }
    // Observable running flag. `importJob?.isActive` is NOT Compose state, so when the import
    // coroutine finishes on its own the buttons would not re-enable until some unrelated
    // recomposition happened to fire. Drive it explicitly: true at launch, false on completion.
    var running by remember { mutableStateOf(false) }

    // Keep the importer reading the latest persisted lastScanEpoch without rebuilding it per change.
    val currentConfig by rememberUpdatedState(config)

    Text(
        text = stringResource(R.string.import_title),
        style = MaterialTheme.typography.titleMedium,
    )
    Text(
        text = stringResource(R.string.import_folder_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (!hasAccess) {
        // ── Permission gate ───────────────────────────────────────────────────
        // Rendered as an error-container card (not a plain surface) so the rider can't miss that
        // this access is required — without it KGhost loads no ghosts at all.
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.import_permission_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.import_permission_description),
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = {
                        // B-I4: some Karoo OS builds expose no all-files-access settings screen;
                        // startActivity would then throw ActivityNotFoundException and crash the
                        // settings UI. Guard it and log instead.
                        runCatching { context.startActivity(StoragePermission.requestIntent(context)) }
                            .onFailure { Timber.w(it, "could not open all-files-access settings") }
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.import_permission_grant))
                }
            }
        }
        return
    }

    // ── Import controls (permission granted) ──────────────────────────────────
    fun startImport(onlyNew: Boolean) {
        progress = null
        canceled = false
        running = true
        val job = scope.launch {
            // TrackStorage.tracksDir() does file IO (mkdirs + one-time migration), so build the
            // store on Dispatchers.IO rather than on the Main-thread button click.
            val importer = withContext(Dispatchers.IO) {
                HistoryImporter(
                    fitFilesDir = File("/sdcard/FitFiles"),
                    importDir = File("/sdcard/KGhost"),
                    trackStore = TrackStore(TrackStorage.tracksDir(context)),
                    decimate = HistoryImporter::defaultDecimate,
                    lastScanProvider = { currentConfig.lastScanEpoch },
                    lastScanSetter = { epoch ->
                        scope.launch { configManager.updateConfig { it.copy(lastScanEpoch = epoch) } }
                    },
                )
            }
            importer.import(onlyNew = onlyNew)
                .flowOn(Dispatchers.IO)
                .collect {
                    progress = it
                    // On completion, ask the host to refresh the recorded-track count so the
                    // "recorded tracks: N" line reflects the just-imported tracks immediately.
                    if (it.phase == ImportProgress.Phase.DONE) onTracksChanged()
                }
        }
        // Re-enable the controls when the import ends (normal completion OR cancel). invokeOnCompletion
        // fires on whatever thread finishes the job; a Compose MutableState write is thread-safe and
        // schedules recomposition on Main.
        job.invokeOnCompletion { running = false }
        importJob = job
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = { startImport(onlyNew = false) },
            enabled = !running,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.import_all), maxLines = 1)
        }
        OutlinedButton(
            onClick = { startImport(onlyNew = true) },
            enabled = !running,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.import_new_only), maxLines = 1)
        }
    }

    if (running) {
        OutlinedButton(
            onClick = {
                importJob?.cancel()
                canceled = true
            },
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.import_cancel))
        }
    }

    // ── Progress / summary line ───────────────────────────────────────────────
    val p = progress
    when {
        canceled -> Text(
            text = stringResource(R.string.import_canceled),
            style = MaterialTheme.typography.bodyMedium,
        )
        p == null -> Unit
        p.phase == ImportProgress.Phase.DONE -> Text(
            text = stringResource(R.string.import_summary, p.imported, p.skippedDuplicates, p.failed),
            style = MaterialTheme.typography.bodyMedium,
        )
        p.phase == ImportProgress.Phase.SCANNING -> Text(
            text = stringResource(R.string.import_scanning),
            style = MaterialTheme.typography.bodyMedium,
        )
        p.phase == ImportProgress.Phase.PARSING -> Text(
            text = stringResource(R.string.import_parsing, p.current, p.total),
            style = MaterialTheme.typography.bodyMedium,
        )
        else -> Unit
    }
}
