package com.enderthor.kghost.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.enderthor.kghost.R
import com.enderthor.kghost.managers.StoragePermission
import timber.log.Timber

/** Resume-aware all-files-access state: re-checks on every ON_RESUME so returning from the system
 *  permission screen flips the UI without a manual refresh. */
@Composable
fun rememberHasAllFilesAccess(): State<Boolean> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(StoragePermission.hasAllFilesAccess(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                state.value = StoragePermission.hasAllFilesAccess(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return state
}

/** Error-container warning + grant button, shown ONLY while all-files access is missing. Reused on
 *  the main screen and inside the import section so the rider can't miss that KGhost needs this
 *  access to load any ghosts. */
@Composable
fun PermissionWarningBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val hasAccess by rememberHasAllFilesAccess()
    if (hasAccess) return

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
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
                    runCatching { context.startActivity(StoragePermission.requestIntent(context)) }
                        .onFailure { Timber.w(it, "could not open all-files-access settings") }
                },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.import_permission_grant))
            }
        }
    }
}
