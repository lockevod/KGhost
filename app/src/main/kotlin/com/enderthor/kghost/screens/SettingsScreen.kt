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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.enderthor.kghost.R
import com.enderthor.kghost.data.KGhostConfig
import com.enderthor.kghost.managers.ConfigurationManager

/**
 * The single combined settings screen.
 *
 * The Ghost Pace and "Race Your Own" are not independent features — they are two halves of ONE
 * continuous ghost: on stretches you've ridden before the ghost is your past self; everywhere else
 * it keeps moving at the Ghost Pace pace. Because they're complementary (and never both the sole
 * source at once), they live together here rather than in separate tabs. The tab scaffold in
 * [com.enderthor.kghost.activity.MainActivity] is kept (one tab for now) so more tabs can be added
 * later without restructuring.
 *
 * Owns the one [verticalScroll] Column; [PartnerSection] and [RaceSection] emit their controls as
 * direct children so everything scrolls as a single page.
 */
@Composable
fun SettingsScreen(
    config: KGhostConfig,
    configManager: ConfigurationManager,
    imperial: Boolean = false,
    activeProfileId: String? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Intro: explain how the two sections relate (one continuous ghost).
        Text(
            text = stringResource(R.string.combined_intro_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.combined_intro_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider()

        // Ghost Pace pace (the fill pace / used when no route is loaded).
        PartnerSection(config = config, configManager = configManager, imperial = imperial, activeProfileId = activeProfileId)

        HorizontalDivider()

        // Race Your Own (recorded ghosts, ghost-on-map icon/size).
        RaceSection(
            config = config,
            configManager = configManager,
        )
    }
}
