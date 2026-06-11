package com.enderthor.kghost.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.enderthor.kghost.R
import com.enderthor.kghost.data.GhostIcon
import com.enderthor.kghost.data.KGhostConfig
import com.enderthor.kghost.data.MAX_TARGET_SPEED_MS
import com.enderthor.kghost.data.ProfileSetting
import com.enderthor.kghost.engine.GhostPick
import com.enderthor.kghost.data.kmhToMs
import com.enderthor.kghost.data.mphToMs
import com.enderthor.kghost.data.msToKmh
import com.enderthor.kghost.data.msToMph
import com.enderthor.kghost.data.msToPaceMinKm
import com.enderthor.kghost.data.msToPaceMinMi
import com.enderthor.kghost.data.paceMinKmToMs
import com.enderthor.kghost.data.paceMinMiToMs
import com.enderthor.kghost.managers.ConfigurationManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/** Whether the target is entered as a speed or as a pace. */
private enum class TargetMode { SPEED, PACE }

/**
 * Ghost Pace section: enter the fixed-pace target as speed (km/h) or pace (min/km).
 *
 * This is one half of the unified ghost (see [SettingsScreen]): the Ghost Pace pace is what the
 * ghost runs at on stretches with no recorded history, and what it uses when no route is loaded.
 *
 * The toggle picks the entry mode; the value is converted to m/s via [kmhToMs] or [paceMinKmToMs] and
 * AUTO-SAVED (debounced) to [KGhostConfig.targetSpeedMs] — consistent with every other setting,
 * which also auto-saves, rather than a separate Save button. The VP can't be deactivated (it's the
 * fallback pace), so an empty/invalid entry is rejected and the stored target — 12 km/h by default —
 * always remains; the field always shows a value.
 *
 * Emitted as direct children of the caller's scrolling Column (no wrapper here), so it stacks with
 * the Race section under one scroll. Spacing comes from the parent Column's arrangement.
 */
@Composable
fun PartnerSection(
    config: KGhostConfig,
    configManager: ConfigurationManager,
    imperial: Boolean = false,
    activeProfileId: String? = null,
) {
    var mode by remember { mutableStateOf(TargetMode.SPEED) }
    var targetText by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    // True once the rider actually edits the field, so the auto-save below ignores the programmatic
    // seeding (initial load + unit-toggle re-format) and only commits real edits.
    var userEdited by remember { mutableStateOf(false) }

    // The VP target is ALWAYS present (default 12 km/h; it can't be deactivated — it's the fallback),
    // so currentMs is always > 0 and the field always shows a value.
    val currentMs = config.targetMs()
    val currentLabel = if (imperial) {
        String.format(Locale.US, "%.1f mph  /  %.2f min/mi", msToMph(currentMs), msToPaceMinMi(currentMs))
    } else {
        String.format(Locale.US, "%.1f km/h  /  %.2f min/km", msToKmh(currentMs), msToPaceMinKm(currentMs))
    }

    // Seed the input from the stored target in the CURRENTLY selected unit, and re-seed whenever the
    // unit flips or the stored value changes (after a save). This (a) prefills the field so the rider
    // sees what's actually stored (default 12) instead of a blank box, and (b) reinterprets the value
    // into the new unit on a SPEED⇄PACE toggle — without this, a "25" typed as km/h would be read as
    // 25 min/km on the next save and persist a ~2.4 km/h pace. Keyed on (mode, currentMs) only, so it
    // does NOT clobber the rider's in-progress typing on unrelated config emissions.
    LaunchedEffect(mode, currentMs, imperial) {
        targetText = when (mode) {
            TargetMode.SPEED ->
                String.format(Locale.US, "%.1f", if (imperial) msToMph(currentMs) else msToKmh(currentMs))
            TargetMode.PACE ->
                String.format(Locale.US, "%.2f", if (imperial) msToPaceMinMi(currentMs) else msToPaceMinKm(currentMs))
        }
        status = null
        // This is a programmatic (re)seed — initial load, a unit flip, or the post-save re-format. Mark
        // the field clean so the auto-save below does NOT re-commit it (the next real keystroke re-arms
        // userEdited). Prevents the seed→save→reseed double-write and the unit-flip re-save.
        userEdited = false
    }

    // Auto-save (debounced): commit the typed target ~700 ms after the rider stops typing, like every
    // other setting. Keyed on (targetText, mode) so each keystroke restarts the debounce. Guards:
    // [userEdited] skips the programmatic seeds above; the `!= currentMs` check skips the no-op re-save
    // the seed triggers after a save. The VP can't be deactivated, so an empty/invalid/out-of-range
    // entry is REJECTED (the stored target — 12 km/h by default — stays); only a finite, positive,
    // in-range value (MAX_TARGET_SPEED_MS ≈ 108 km/h) is persisted.
    LaunchedEffect(targetText, mode, imperial) {
        if (!userEdited) return@LaunchedEffect
        delay(700)
        val value = targetText.replace(',', '.').trim().toDoubleOrNull()
        val targetMs = when (mode) {
            TargetMode.SPEED -> value?.let { if (imperial) mphToMs(it) else kmhToMs(it) } ?: Double.NaN
            TargetMode.PACE -> value?.let { if (imperial) paceMinMiToMs(it) else paceMinKmToMs(it) } ?: Double.NaN
        }
        status = when {
            !targetMs.isFinite() || targetMs <= 0.0 || targetMs > MAX_TARGET_SPEED_MS -> "invalid"
            targetMs == currentMs -> null // no-op (seed re-format after a save)
            configManager.updateConfig { it.copy(targetSpeedMs = targetMs) } -> null
            else -> "saveFailed"
        }
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
            border = selectedChipBorder(selected = mode == TargetMode.SPEED),
            modifier = Modifier.heightIn(min = 48.dp),
        )
        FilterChip(
            selected = mode == TargetMode.PACE,
            onClick = { mode = TargetMode.PACE },
            label = { Text(stringResource(R.string.partner_mode_pace)) },
            border = selectedChipBorder(selected = mode == TargetMode.PACE),
            modifier = Modifier.heightIn(min = 48.dp),
        )
    }

    val fieldLabel = when (mode) {
        TargetMode.SPEED -> stringResource(if (imperial) R.string.partner_input_speed_imp else R.string.partner_input_speed)
        TargetMode.PACE -> stringResource(if (imperial) R.string.partner_input_pace_imp else R.string.partner_input_pace)
    }
    OutlinedTextField(
        value = targetText,
        onValueChange = { targetText = it; userEdited = true; status = null },
        label = { Text(fieldLabel) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )

    if (status == "invalid") {
        Text(text = stringResource(R.string.partner_invalid))
    }
    if (status == "saveFailed") {
        Text(text = stringResource(R.string.settings_save_failed))
    }
}

/**
 * Per-profile speed overrides section. Lists each Karoo profile and lets the rider customise the
 * ghost settings per profile (or inherit from global). Rendered after the general settings so the
 * global defaults are visible before the per-profile overrides.
 */
@Composable
fun ProfileSection(
    config: KGhostConfig,
    configManager: ConfigurationManager,
    imperial: Boolean = false,
    activeProfileId: String? = null,
) {
    val pScope = rememberCoroutineScope()

    Text(text = stringResource(R.string.profile_section_title), style = MaterialTheme.typography.titleMedium)
    Text(
        text = stringResource(R.string.profile_section_description),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    val settings = config.profileSettings
    if (settings.isEmpty()) {
        Text(
            text = stringResource(R.string.profile_roster_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        val active = settings.filter { it.profileId == activeProfileId }
        val customized = settings.filter { it.profileId != activeProfileId && !it.useGlobal }
        val globalStubs = settings.filter { it.profileId != activeProfileId && it.useGlobal }

        val persist: (ProfileSetting) -> Unit = { updated ->
            pScope.launch {
                configManager.updateConfig { c ->
                    c.copy(profileSettings = c.profileSettings.map { if (it.profileId == updated.profileId) updated else it })
                }
            }
        }

        (active + customized).forEach { setting ->
            key(setting.profileId) {
                ProfileCard(setting, setting.profileId == activeProfileId, imperial, persist)
            }
        }

        if (globalStubs.isNotEmpty()) {
            var expanded by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.profile_using_global_group, globalStubs.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(text = if (expanded) "▾" else "▸", style = MaterialTheme.typography.bodyMedium)
            }
            if (expanded) {
                globalStubs.forEach { setting ->
                    key(setting.profileId) { ProfileCard(setting, false, imperial, persist) }
                }
            }
        }
    }
}

/**
 * One per-profile card: name + Active badge, a Use-Global toggle, and (when custom) a speed-only
 * Ghost-Pace base entry plus an enable switch. Edits are pushed up via [onChange]; the parent persists.
 */
@Composable
private fun ProfileCard(
    setting: ProfileSetting,
    isActive: Boolean,
    imperial: Boolean,
    onChange: (ProfileSetting) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val title = if (isActive) {
                "${setting.profileName}  ·  ${stringResource(R.string.profile_active_badge)}"
            } else {
                setting.profileName
            }
            Text(text = title, style = MaterialTheme.typography.titleSmall)

            SwitchRow(
                label = stringResource(R.string.profile_use_global),
                checked = setting.useGlobal,
                onCheckedChange = { onChange(setting.copy(useGlobal = it)) },
            )

            if (!setting.useGlobal) {
                SwitchRow(
                    label = stringResource(R.string.profile_enabled_label),
                    checked = setting.enabled,
                    onCheckedChange = { onChange(setting.copy(enabled = it)) },
                )

                // Mode: Fixed pace vs Your rides (per profile).
                Text(
                    text = stringResource(R.string.race_mode_label),
                    style = MaterialTheme.typography.bodyMedium,
                )
                GhostModeChips(
                    raceEnabled = setting.raceEnabled,
                    onChange = { yours -> onChange(setting.copy(raceEnabled = yours)) },
                )

                var text by remember(setting.profileId, setting.useGlobal, imperial) {
                    mutableStateOf(
                        String.format(
                            Locale.US, "%.1f",
                            if (imperial) msToMph(setting.targetSpeedMs) else msToKmh(setting.targetSpeedMs),
                        ),
                    )
                }
                var edited by remember(setting.profileId, setting.useGlobal, imperial) { mutableStateOf(false) }
                LaunchedEffect(text, imperial) {
                    if (!edited) return@LaunchedEffect
                    delay(700)
                    val v = text.replace(',', '.').trim().toDoubleOrNull()
                    val ms = v?.let { if (imperial) mphToMs(it) else kmhToMs(it) }
                    if (ms != null && ms.isFinite() && ms > 0.0 && ms <= MAX_TARGET_SPEED_MS && ms != setting.targetSpeedMs) {
                        onChange(setting.copy(targetSpeedMs = ms))
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; edited = true },
                    label = {
                        Text(stringResource(if (imperial) R.string.profile_target_label_imperial else R.string.profile_target_label_metric))
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )

                val dimmed = dimmedContentColor()
                // Best/Last/Average + icon apply only in "Your rides" mode → dim when Fixed pace.
                Text(
                    text = stringResource(R.string.race_ghost_pick_label),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (setting.raceEnabled) Color.Unspecified else dimmed,
                )
                GhostPickChips(
                    selected = setting.ghostPick,
                    enabled = setting.raceEnabled,
                    onPick = { pick -> onChange(setting.copy(ghostPick = pick)) },
                )
                Text(
                    text = stringResource(R.string.race_ghost_icon_label),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (setting.raceEnabled) Color.Unspecified else dimmed,
                )
                GhostIconChips(
                    selected = setting.ghostIcon,
                    enabled = setting.raceEnabled,
                    onPick = { icon -> onChange(setting.copy(ghostIcon = icon)) },
                )
            }
        }
    }
}
