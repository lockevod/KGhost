package com.enderthor.kghost.activity

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enderthor.kghost.R
import com.enderthor.kghost.data.KGhostConfig
import com.enderthor.kghost.geo.TrackStore
import com.enderthor.kghost.geo.TrackStorage
import com.enderthor.kghost.managers.ConfigurationManager
import com.enderthor.kghost.screens.AppSettingsScreen
import com.enderthor.kghost.screens.SettingsScreen
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.ActiveRideProfile
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings UI for the KGhost extension.
 *
 * A minimal Compose host with three tabs: Partner (Ghost Pace target + units), Race (Race
 * Your Own settings), and Settings (display preferences). Configuration is loaded and saved
 * through [ConfigurationManager], which reuses the single process-wide DataStore defined in
 * `com.enderthor.kghost.managers`. This Activity never creates its own DataStore.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // The app has no MaterialTheme wrapper otherwise, so every MaterialTheme.colorScheme.*
            // reference in the settings screens would resolve to M3's built-in light scheme regardless
            // of the Karoo's day/night mode (white-on-white at night). Drive the scheme from the system
            // night mode so the settings UI matches the device theme like the data fields already do.
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    TabLayout()
                }
            }
        }
    }
}

/**
 * Three-tab layout. The config is collected once here and passed down so all tabs share the
 * same source of truth; saves go straight back through [ConfigurationManager.saveConfig].
 *
 * A [TrackStore] is instantiated here — read-only from the UI side — solely to expose the
 * recorded-track count on the Race tab. All actual write IO runs on Dispatchers.IO inside
 * [KGhostExtension]; this call is a cheap directory listing done once at composition.
 */
@Composable
fun TabLayout() {
    val context = LocalContext.current
    val configManager = remember { ConfigurationManager(context) }
    val config by configManager.loadConfigFlow()
        .collectAsStateWithLifecycle(initialValue = KGhostConfig())

    // Read-only snapshot of the stored track count. allTrackIds() does a synchronous listFiles(),
    // so it must NOT run on Main (ANR rule); compute it on Dispatchers.IO. Stays null until loaded,
    // which keeps the Race-tab count line hidden until the value is available. Recomputed whenever
    // [refreshKey] bumps — the import flow calls onTracksChanged() on completion so the "recorded
    // tracks: N" line reflects a just-finished import instead of staying stale until recreation.
    var trackCount by remember { mutableStateOf<Int?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(refreshKey) {
        trackCount = withContext(Dispatchers.IO) {
            try { TrackStore(TrackStorage.tracksDir(context)).allTrackIds().size } catch (_: Exception) { null }
        }
    }

    // The settings Activity runs in its OWN process (separate from the extension service), so it needs
    // its own KarooSystemService to read the rider's unit system (UserProfile.preferredUnit.distance)
    // and show the Ghost Pace target in km/h or mph. Connected for the lifetime of this screen.
    var imperial by remember { mutableStateOf(false) }
    var activeProfileId by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) {
        val karooSystem = KarooSystemService(context)
        var unitsConsumer: String? = null
        var profileConsumer: String? = null
        karooSystem.connect { connected ->
            if (connected) {
                unitsConsumer = karooSystem.addConsumer<UserProfile> { profile ->
                    imperial = profile.preferredUnit.distance == UserProfile.PreferredUnit.UnitType.IMPERIAL
                }
                profileConsumer = karooSystem.addConsumer<ActiveRideProfile> { evt ->
                    activeProfileId = evt.profile.id
                }
            }
        }
        onDispose {
            unitsConsumer?.let { karooSystem.removeConsumer(it) }
            profileConsumer?.let { karooSystem.removeConsumer(it) }
            karooSystem.disconnect()
        }
    }

    // Single combined tab for now. The TabRow scaffold is intentionally kept (not collapsed into a
    // plain screen) so additional tabs can be added later without restructuring — see [SettingsScreen]
    // for why Partner + Race were merged into one place.
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabTitles = listOf(
        stringResource(R.string.tab_main),
        stringResource(R.string.tab_settings),
    )

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            tabTitles.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = {
                        Text(
                            text = title,
                            maxLines = 1,
                            softWrap = false,
                            style = MaterialTheme.typography.titleSmall,
                        )
                    },
                )
            }
        }
        when (selectedTab) {
            0 -> SettingsScreen(
                config = config,
                configManager = configManager,
                imperial = imperial,
                activeProfileId = activeProfileId,
            )
            else -> AppSettingsScreen(
                config = config,
                configManager = configManager,
                recordedCount = trackCount,
                onTracksChanged = { refreshKey++ },
            )
        }
    }
}
