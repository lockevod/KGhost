package com.enderthor.kghost.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.enderthor.kghost.R
import com.enderthor.kghost.data.GhostIcon
import com.enderthor.kghost.data.KGhostConfig
import com.enderthor.kghost.engine.GhostPick
import com.enderthor.kghost.import_.HistoryImportRunner
import com.enderthor.kghost.import_.ImportProgress
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enderthor.kghost.managers.ConfigurationManager
import com.enderthor.kghost.managers.StoragePermission
import kotlinx.coroutines.launch

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
) {
    val scope = rememberCoroutineScope()
    var saveFailed by remember { mutableStateOf(false) }

    Text(
        text = stringResource(R.string.race_title),
        style = MaterialTheme.typography.titleMedium,
    )
    Text(text = stringResource(R.string.race_description))

        // Recorded-track count — the same shared figure shown on the Settings tab next to Import, so
        // "Race Your Own" makes clear how many rides it has to race against and it reflects an import
        // (the count is recomputed on the host's refreshKey) without leaving this tab.
        if (recordedCount != null) {
            Text(
                text = stringResource(R.string.race_recorded_tracks, recordedCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider()

        // ── Mode: Fixed pace vs Your rides ────────────────────────────────────
        Text(
            text = stringResource(R.string.race_mode_label),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(R.string.race_mode_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        GhostModeChips(
            raceEnabled = config.raceEnabled,
            onChange = { yours ->
                scope.launch {
                    saveFailed = !configManager.updateConfig { it.copy(raceEnabled = yours) }
                }
            },
        )

        HorizontalDivider()

        // ── Ghost selector (only in "Your rides" mode) ────────────────────────
        Text(
            text = stringResource(R.string.race_ghost_pick_label),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(R.string.race_ghost_pick_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        GhostPickChips(
            selected = config.ghostPick,
            enabled = config.raceEnabled,
            onPick = { pick ->
                scope.launch {
                    saveFailed = !configManager.updateConfig { it.copy(ghostPick = pick) }
                }
            },
        )
        // Average behaves differently from Best/Last (it needs a warmup), so explain it inline.
        if (config.raceEnabled && config.ghostPick == GhostPick.AVERAGE) {
            Text(
                text = stringResource(R.string.race_ghost_average_hint),
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
            enabled = config.raceEnabled,
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
            enabled = config.raceEnabled,
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
            enabled = config.raceEnabled,
            onCheckedChange = { show ->
                scope.launch {
                    saveFailed = !configManager.updateConfig { it.copy(showGhostOnMap = show) }
                }
            },
        )

        // ── Ghost icon picker (only in "Your rides" mode) ─────────────────────
        Text(
            text = stringResource(R.string.race_ghost_icon_label),
            style = MaterialTheme.typography.bodyMedium,
            color = if (config.raceEnabled) Color.Unspecified else dimmedContentColor(),
        )
        GhostIconChips(
            selected = config.ghostIcon,
            enabled = config.raceEnabled,
            onPick = { icon ->
                scope.launch {
                    saveFailed = !configManager.updateConfig { it.copy(ghostIcon = icon) }
                }
            },
        )

        // Ghost icon SIZE is automatic — it follows the map zoom level (OnMapZoomLevel), so there is
        // no manual size picker.

        // ── Save-failure notice ───────────────────────────────────────────────
        if (saveFailed) {
            Text(text = stringResource(R.string.settings_save_failed))
        }
}

/** Two chips for the racing MODE: Fixed pace (Ghost Pace) vs Your rides (recorded history). */
@Composable
internal fun GhostModeChips(raceEnabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = !raceEnabled,
            onClick = { onChange(false) },
            label = { Text(stringResource(R.string.race_mode_fixed)) },
            border = selectedChipBorder(selected = !raceEnabled),
            modifier = Modifier.heightIn(min = 48.dp),
        )
        FilterChip(
            selected = raceEnabled,
            onClick = { onChange(true) },
            label = { Text(stringResource(R.string.race_mode_yours)) },
            border = selectedChipBorder(selected = raceEnabled),
            modifier = Modifier.heightIn(min = 48.dp),
        )
    }
}

/**
 * Best / Last / Average picker. [enabled] = false dims the chips. All three share equal width via
 * [weight(1f)] within the FlowRow row (maxItemsInEachRow=3 keeps them all on one line). Labels use
 * maxLines=1 so text never wraps inside a chip regardless of available width.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GhostPickChips(selected: GhostPick, enabled: Boolean, onPick: (GhostPick) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        maxItemsInEachRow = 3,
    ) {
        FilterChip(
            enabled = enabled,
            selected = selected == GhostPick.BEST,
            onClick = { onPick(GhostPick.BEST) },
            label = { Text(stringResource(R.string.race_ghost_best), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            border = selectedChipBorder(selected = selected == GhostPick.BEST, enabled = enabled),
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
        )
        FilterChip(
            enabled = enabled,
            selected = selected == GhostPick.LAST,
            onClick = { onPick(GhostPick.LAST) },
            label = { Text(stringResource(R.string.race_ghost_last), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            border = selectedChipBorder(selected = selected == GhostPick.LAST, enabled = enabled),
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
        )
        FilterChip(
            enabled = enabled,
            selected = selected == GhostPick.AVERAGE,
            onClick = { onPick(GhostPick.AVERAGE) },
            label = { Text(stringResource(R.string.race_ghost_average), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            border = selectedChipBorder(selected = selected == GhostPick.AVERAGE, enabled = enabled),
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
        )
    }
}

/** On-map ghost icon picker (ghost / cyclist / arrow / dot), as a tidy 2×2 grid. [enabled] dims it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GhostIconChips(selected: GhostIcon, enabled: Boolean, onPick: (GhostIcon) -> Unit) {
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
                enabled = enabled,
                selected = selected == choice.icon,
                onClick = { onPick(choice.icon) },
                label = { Text(stringResource(choice.labelRes)) },
                border = selectedChipBorder(selected = selected == choice.icon, enabled = enabled),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            )
        }
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
 * [ConfigurationManager]. Execution and progress live in the process-scoped [HistoryImportRunner], so
 * leaving and returning to this screen never cancels the scan or loses its progress — this Composable
 * only observes the runner's StateFlows and triggers start/cancel.
 */
@Composable
internal fun ImportSection(
    config: KGhostConfig,
    configManager: ConfigurationManager,
    onTracksChanged: () -> Unit = {},
) {
    val context = LocalContext.current

    // Re-check permission whenever the screen resumes so granting it elsewhere and returning here
    // flips the UI from the permission card to the import buttons without a manual refresh.
    var hasAccess by remember { mutableStateOf(StoragePermission.hasAllFilesAccess(context)) }
    // Confirm-arm for the DESTRUCTIVE rebuild (see the button below). Declared up here so the lifecycle
    // observer can disarm it: this Composable is never scrolled out of composition and survives
    // backgrounding, so an arm left standing would still be live days later — and one tap on a button
    // that reads "Tap again to confirm" long after the rider forgot arming it is the whole risk.
    var rebuildArmed by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasAccess = StoragePermission.hasAllFilesAccess(context)
            }
            if (event == Lifecycle.Event.ON_PAUSE) rebuildArmed = false
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Import state lives in the process-scoped [HistoryImportRunner], NOT in this Composable, so leaving
    // and returning to this screen never cancels the scan or loses its progress — we only OBSERVE it.
    val progress by HistoryImportRunner.progress.collectAsStateWithLifecycle()
    val running by HistoryImportRunner.running.collectAsStateWithLifecycle()
    val canceled by HistoryImportRunner.canceled.collectAsStateWithLifecycle()
    val pendingCompletion by HistoryImportRunner.pendingCompletion.collectAsStateWithLifecycle()
    val preparing by HistoryImportRunner.preparing.collectAsStateWithLifecycle()
    val rebuilding by HistoryImportRunner.rebuilding.collectAsStateWithLifecycle()
    val shortfall by HistoryImportRunner.shortfall.collectAsStateWithLifecycle()
    val rebuildRefused by HistoryImportRunner.rebuildRefused.collectAsStateWithLifecycle()
    val refusedCounts by HistoryImportRunner.refusedCounts.collectAsStateWithLifecycle()

    // Disarm on ANY import starting or finishing — arming Rebuild, running "All" instead and coming back
    // to a still-armed (i.e. one-tap-destructive) button is the same trap as the stale arm above.
    LaunchedEffect(running) { rebuildArmed = false }

    // Refresh the recorded-track count once per completion — even one that finished while we were on
    // another screen. We key on the runner's consumable [pendingCompletion] flag (NOT progress.phase,
    // which stays DONE forever and would re-fire onTracksChanged on every re-entry) and clear it after.
    LaunchedEffect(pendingCompletion) {
        if (pendingCompletion) {
            onTracksChanged()
            HistoryImportRunner.consumeCompletion()
        }
    }

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
        PermissionWarningBanner()
        return
    }

    // ── Import controls (permission granted) ──────────────────────────────────
    fun startImport(onlyNew: Boolean) {
        // Run in the process-scoped runner (uses the APPLICATION context so the work outlives this
        // Activity). It builds the importer on IO and is a no-op if a scan is already in flight.
        HistoryImportRunner.start(
            appContext = context.applicationContext,
            configManager = configManager,
            onlyNew = onlyNew,
            lastScanEpoch = config.lastScanEpoch,
        )
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

    // Rebuild ARCHIVES the whole imported library before re-importing it, so it is confirm-then-run: the
    // first tap only arms the button (the hint below already says what it does and how long it takes),
    // the second starts it. A modal dialog would be the usual gesture but does not fit a 2.2" screen.
    // `rebuildArmed` is declared at the top of this Composable so it can be disarmed on pause / on any
    // import starting or ending.
    OutlinedButton(
        onClick = {
            if (!rebuildArmed) {
                rebuildArmed = true
            } else {
                rebuildArmed = false
                HistoryImportRunner.rebuildAll(
                    appContext = context.applicationContext,
                    configManager = configManager,
                    lastScanEpoch = config.lastScanEpoch,
                )
            }
        },
        enabled = !running,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Text(
            stringResource(if (rebuildArmed) R.string.import_rebuild_confirm else R.string.import_rebuild),
            maxLines = 1,
        )
    }
    Text(
        text = stringResource(R.string.import_rebuild_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    // No Cancel during the archive+reset window: archive() is non-suspending so it always completes, and
    // a cancel landing just after it would leave the library archived with nothing re-importing it.
    if (running && !preparing) {
        OutlinedButton(
            onClick = { HistoryImportRunner.cancel() },
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.import_cancel))
        }
    }

    // ── Progress / summary line ───────────────────────────────────────────────
    val p = progress
    when {
        preparing -> Text(
            text = stringResource(R.string.import_rebuild_preparing),
            style = MaterialTheme.typography.bodyMedium,
        )
        // A cancel right after a rebuild's archive leaves the library in archive/ with nothing having
        // re-imported it. That IS recoverable — the keys of the archived tracks are gone and the ledger
        // was deleted, so a plain "All" re-imports every one of them — but only if the rider is TOLD.
        canceled && rebuilding -> Text(
            text = stringResource(R.string.import_canceled_rebuild),
            style = MaterialTheme.typography.bodyMedium,
        )
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
        p.phase == ImportProgress.Phase.ERROR -> Text(
            text = stringResource(R.string.import_error),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        else -> Unit
    }

    // The two rebuild-specific outcomes, OUTSIDE the when above so they survive every terminal phase.
    //
    // Refusal: the destructive phase declined and an ordinary import ran instead, so the line above is a
    // perfectly cheerful "0 imported · N duplicates" and a log-only refusal reads as "the button did
    // nothing" — the rider just taps Rebuild again.
    //
    // When it refused on the FILE COUNT the numbers come with it, because "they aren't all there" leaves
    // the rider nothing to do: they cannot know how many files to put back, so the button stays retired.
    if (rebuildRefused) Text(
        text = refusedCounts
            ?.let { (files, rides) ->
                stringResource(R.string.import_rebuild_missing, files, rides, rides - files)
            }
            ?: stringResource(R.string.import_rebuild_refused),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
    // Shortfall: a rebuild archived more rides than the re-import brought back. This must render under
    // ERROR as well as DONE — "Import failed" with 100 rides silently sitting in archive/ is exactly the
    // case where a strand is most likely and least survivable in silence.
    if (shortfall > 0) Text(
        text = stringResource(R.string.import_rebuild_shortfall, shortfall),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
}
