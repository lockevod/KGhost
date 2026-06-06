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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enderthor.kvpartner.R
import com.enderthor.kvpartner.data.KVPartnerConfig
import com.enderthor.kvpartner.managers.ConfigurationManager
import com.enderthor.kvpartner.screens.PartnerScreen
import com.enderthor.kvpartner.screens.SettingsScreen

/**
 * Settings UI for the KVPartner extension.
 *
 * A minimal Compose host with two tabs: Partner (Virtual Partner target + units) and
 * Settings (display preferences). Configuration is loaded and saved through
 * [ConfigurationManager], which reuses the single process-wide DataStore defined in
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
 * Two-tab layout. The config is collected once here and passed down so both tabs share the
 * same source of truth; saves go straight back through [ConfigurationManager.saveConfig].
 */
@Composable
fun TabLayout() {
    val context = LocalContext.current
    val configManager = remember { ConfigurationManager(context) }
    val config by configManager.loadConfigFlow()
        .collectAsStateWithLifecycle(initialValue = KVPartnerConfig())

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabTitles = listOf(
        stringResource(R.string.tab_partner),
        stringResource(R.string.tab_settings),
    )

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            tabTitles.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title) },
                )
            }
        }
        when (selectedTab) {
            0 -> PartnerScreen(config = config, configManager = configManager)
            else -> SettingsScreen(config = config, configManager = configManager)
        }
    }
}
