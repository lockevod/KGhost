package com.enderthor.kghost.engine

import com.enderthor.kghost.data.KGhostConfig
import com.enderthor.kghost.data.ProfileSetting
import com.enderthor.kghost.data.sanitizeTargetMs

/**
 * The settings that actually apply to the current ride after resolving the active ride profile
 * against the global config. [active] folds in BOTH the master kill-switch and the per-profile enable.
 */
data class EffectiveProfile(
    val active: Boolean,
    val targetSpeedMs: Double,
    val profileName: String,
)

/**
 * Resolve the effective ride settings for [activeProfileId] against [global], mirroring Kcrash's
 * CrashProfileResolver. A null/blank id, an unknown id, or a `useGlobal` entry inherits the global
 * Ghost-Pace target and is active (subject to the master switch). A custom entry supplies its own
 * target and enable. The master switch always ANDs (kill-switch semantics).
 */
fun resolveProfile(global: KGhostConfig, activeProfileId: String?): EffectiveProfile {
    val setting = activeProfileId
        ?.takeIf { it.isNotBlank() }
        ?.let { id -> global.profileSettings.firstOrNull { it.profileId == id } }
    val useGlobal = setting == null || setting.useGlobal
    return EffectiveProfile(
        active = global.masterEnabled && (if (useGlobal) true else setting!!.enabled),
        targetSpeedMs = if (useGlobal) global.targetMs() else sanitizeTargetMs(setting!!.targetSpeedMs),
        profileName = setting?.profileName ?: "",
    )
}

/**
 * Upsert the roster with the just-seen profile, mirroring Kcrash's learnProfile. On first sight append
 * a `useGlobal` stub; on a rename update the name while preserving the override; prune a stale entry
 * that reused this [name] under a different id (a profile deleted + recreated). Blank [id] is a no-op.
 */
fun learnProfile(settings: List<ProfileSetting>, id: String, name: String): List<ProfileSetting> {
    if (id.isBlank()) return settings
    val pruned = settings.filterNot { it.profileName == name && it.profileId != id }
    val existing = pruned.firstOrNull { it.profileId == id }
    return when {
        existing == null -> pruned + ProfileSetting(profileId = id, profileName = name)
        existing.profileName != name -> pruned.map { if (it.profileId == id) it.copy(profileName = name) else it }
        else -> pruned
    }
}
