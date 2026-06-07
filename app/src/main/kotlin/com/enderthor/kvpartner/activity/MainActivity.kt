package com.enderthor.kvpartner.activity

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.enderthor.kvpartner.R
import com.enderthor.kvpartner.data.KVPartnerConfig
import com.enderthor.kvpartner.geo.TrackStore
import com.enderthor.kvpartner.managers.ConfigurationManager
import com.enderthor.kvpartner.screens.PartnerScreen
import com.enderthor.kvpartner.screens.RaceScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Settings UI for the KVPartner extension.
 *
 * A minimal Compose host with three tabs: Partner (Virtual Partner target + units), Race (Race
 * Your Own settings), and Settings (display preferences). Configuration is loaded and saved
 * through [ConfigurationManager], which reuses the single process-wide DataStore defined in
 * `com.enderthor.kvpartner.managers`. This Activity never creates its own DataStore.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                TabLayout()
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
 * [KVPartnerExtension]; this call is a cheap directory listing done once at composition.
 */
@Composable
fun TabLayout() {
    val context = LocalContext.current
    val configManager = remember { ConfigurationManager(context) }
    val config by configManager.loadConfigFlow()
        .collectAsStateWithLifecycle(initialValue = KVPartnerConfig())

    // Read-only snapshot of the stored track count. allTrackIds() does a synchronous listFiles(),
    // so it must NOT run on Main (ANR rule); compute it once on Dispatchers.IO. Stays null until
    // loaded, which keeps the Race-tab count line hidden until the value is available.
    var trackCount by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) {
        trackCount = withContext(Dispatchers.IO) {
            try { TrackStore(File(context.filesDir, "tracks")).allTrackIds().size } catch (_: Exception) { null }
        }
    }

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabTitles = listOf(
        stringResource(R.string.tab_partner),
        stringResource(R.string.tab_race),
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
            0 -> PartnerScreen(config = config, configManager = configManager)
            else -> RaceScreen(config = config, configManager = configManager, recordedCount = trackCount)
        }
    }
}
