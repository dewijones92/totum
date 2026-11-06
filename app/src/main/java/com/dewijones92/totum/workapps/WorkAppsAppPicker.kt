package com.dewijones92.totum.workapps

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class InstalledApp(val packageName: String, val label: String, val canNotify: Boolean)

@Composable
fun AppPickerDialog(chosen: Set<String>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var filter by rememberSaveable { mutableStateOf("") }
    var everyApp by rememberSaveable { mutableStateOf(false) }
    var typed by rememberSaveable { mutableStateOf("") }
    val apps by produceState<List<InstalledApp>?>(null, context) {
        value = withContext(Dispatchers.Default) { launchableApps(context) }
    }
    val shown = remember(apps, filter, everyApp, chosen) {
        apps.orEmpty()
            .filter { it.packageName !in chosen && (everyApp || it.canNotify) }
            .filter { filter.isBlank() || it.label.contains(filter, true) || it.packageName.contains(filter, true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.work_apps_picker_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    label = { Text(stringResource(R.string.work_apps_picker_filter)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                FilterChip(
                    selected = everyApp,
                    onClick = { everyApp = !everyApp },
                    label = { Text(stringResource(R.string.work_apps_picker_every_app)) },
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                AppList(apps, shown, onPick)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it },
                        label = { Text(stringResource(R.string.work_apps_picker_package)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f).testTag("work-apps-package-field"),
                    )
                    TextButton(
                        onClick = { onPick(typed.trim()) },
                        enabled = typed.isNotBlank(),
                        modifier = Modifier.testTag("work-apps-package-add"),
                    ) { Text(stringResource(R.string.work_apps_picker_add_package)) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.work_apps_picker_close)) }
        },
    )
}

@Composable
private fun AppList(all: List<InstalledApp>?, shown: List<InstalledApp>, onPick: (String) -> Unit) {
    when {
        all == null -> Text(
            stringResource(R.string.work_apps_picker_loading),
            style = MaterialTheme.typography.bodyMedium
        )
        shown.isEmpty() -> Text(
            stringResource(R.string.work_apps_picker_none),
            style = MaterialTheme.typography.bodyMedium
        )
        else -> LazyColumn(Modifier.heightIn(max = LIST_MAX_HEIGHT.dp)) {
            items(shown, key = { it.packageName }) { app ->
                Column(
                    Modifier.fillMaxWidth().clickable { onPick(app.packageName) }.padding(vertical = 8.dp),
                ) {
                    Text(app.label, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        app.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun launchableApps(context: Context): List<InstalledApp> {
    val pm = context.packageManager
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val launchable = pm.queryIntentActivities(launcher, 0).map { it.activityInfo.packageName }.toSet()
    val declaresNotifications = pm.getInstalledPackages(
        PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())
    )
        .filter { Manifest.permission.POST_NOTIFICATIONS in it.requestedPermissions.orEmpty() }
        .map { it.packageName }
        .toSet()
    val apps = launchable.filter { it != context.packageName }.map { packageName ->
        InstalledApp(packageName, context.appLabel(packageName), packageName in declaresNotifications)
    }.sortedBy { it.label.lowercase() }
    Diag.log(
        WorkAppsQuiet.TAG,
        "dewidebug workapps picker: ${apps.size} launchable apps, ${apps.count { it.canNotify }} declare notifications",
    )
    return apps
}

private const val LIST_MAX_HEIGHT = 280
