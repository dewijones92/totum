package com.dewijones92.totum.workapps

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.dailyalarms.QuietGroup
import com.dewijones92.totum.dailyalarms.WorkAppsSettings
import com.dewijones92.totum.reminders.kit.KeepScreenAwake
import com.dewijones92.totum.ui.common.BackHeader
import com.dewijones92.totum.ui.common.LocalSnackbarOffers
import com.dewijones92.totum.ui.common.SnackbarOffers
import com.dewijones92.totum.ui.common.rememberRemoveWithUndo
import com.dewijones92.totum.ui.common.rememberSnackbarOffers
import com.dewijones92.totum.ui.settings.QuestCard

@Composable
fun WorkAppsQuietScreen(workApps: WorkAppsQuiet, onBack: () -> Unit, modifier: Modifier = Modifier) {
    KeepScreenAwake("work apps quiet hours page")
    val settings by workApps.settings.collectAsStateWithLifecycle()
    var generation by remember { mutableIntStateOf(0) }
    val access = remember(generation) { workApps.hasAccess() }
    LifecycleResumeEffect(Unit) {
        generation++
        workApps.accessChanged("page resumed")
        onPauseOrDispose { }
    }
    val update: SettingsUpdate = { source, change -> workApps.update("page: $source", change) }
    val snackbar = remember { SnackbarHostState() }
    val ownOffers = rememberSnackbarOffers(snackbar)
    val offers = LocalSnackbarOffers.current ?: ownOffers
    val removals = rememberGroupRemovals(workApps, offers)
    Surface(modifier = modifier.fillMaxSize()) {
        Box {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                BackHeader(stringResource(R.string.work_apps_name), onBack)
                Text(
                    stringResource(R.string.work_apps_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                StatusCard(workApps, settings, access, generation, update)
                AccessCard(access)
                if (settings.groups.isEmpty()) {
                    Text(
                        stringResource(R.string.work_apps_groups_none),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                settings.groups.forEach { group ->
                    key(group.id) { WorkAppsGroupCard(workApps, group, settings, update, removals) }
                }
                AddGroupButton(workApps, settings)
                Spacer(Modifier.size(32.dp))
            }
            SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
}

internal typealias SettingsUpdate = (String, (WorkAppsSettings) -> WorkAppsSettings) -> Unit

internal class GroupRemovals(val group: (String) -> Unit, val app: (Pair<String, String>) -> Unit)

@Composable
private fun rememberGroupRemovals(workApps: WorkAppsQuiet, offers: SnackbarOffers?): GroupRemovals {
    val context = LocalContext.current
    val resources = LocalResources.current
    val undo = stringResource(R.string.undo)
    val group = rememberRemoveWithUndo(
        offers = offers,
        undoLabel = undo,
        tag = WorkAppsQuiet.TAG,
        duration = SnackbarDuration.Long,
        removedMessage = { removed: RemovedGroup ->
            resources.getString(
                R.string.work_apps_group_deleted,
                removed.group.name
            )
        },
        remove = { id: String -> workApps.removeGroup(id, "page") },
        restore = { workApps.restoreGroup(it, "undo") },
    )
    val app = rememberRemoveWithUndo(
        offers = offers,
        undoLabel = undo,
        tag = WorkAppsQuiet.TAG,
        duration = SnackbarDuration.Long,
        removedMessage = { removed: RemovedApp ->
            resources.getString(R.string.work_apps_app_removed, context.appLabel(removed.packageName))
        },
        remove = { target: Pair<String, String> -> workApps.removeApp(target.first, target.second, "page") },
        restore = { workApps.restoreApp(it, "undo") },
    )
    return remember(group, app) { GroupRemovals(group, app) }
}

@Composable
private fun AddGroupButton(workApps: WorkAppsQuiet, settings: WorkAppsSettings) {
    val name = stringResource(R.string.work_apps_new_group_name)
    Button(
        onClick = {
            val used = settings.groups.map { it.emoji }.toSet()
            val emoji = QuietGroup.EMOJI_CHOICES.firstOrNull { it !in used } ?: QuietGroup.EMOJI_CHOICES.first()
            workApps.addGroup(name, emoji, "page: add group")
        },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("work-apps-add-group"),
    ) { Text(stringResource(R.string.work_apps_add_group)) }
}

@Composable
private fun StatusCard(
    workApps: WorkAppsQuiet,
    settings: WorkAppsSettings,
    access: Boolean,
    generation: Int,
    update: SettingsUpdate,
) {
    val context = LocalContext.current
    val line = remember(settings, access, generation) {
        workApps.status()?.let { context.workAppsLine(it) }
    } ?: stringResource(R.string.work_apps_status_off)
    QuestCard(Icons.Outlined.NotificationsOff, stringResource(R.string.work_apps_status_title)) {
        Text(line, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("work-apps-status"))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.work_apps_switch), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.work_apps_switch_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.enabled,
                onCheckedChange = { on ->
                    update("switch") { it.copy(enabled = on) }
                    if (on && !access) context.startActivity(notificationAccessIntent())
                },
                modifier = Modifier.testTag("work-apps-switch"),
            )
        }
    }
}

@Composable
private fun AccessCard(access: Boolean) {
    val context = LocalContext.current
    QuestCard(Icons.Outlined.VerifiedUser, stringResource(R.string.work_apps_access_title)) {
        Text(
            stringResource(if (access) R.string.work_apps_access_on else R.string.work_apps_access_off),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.testTag("work-apps-access"),
        )
        Text(
            stringResource(R.string.work_apps_access_why),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = {
                Diag.log(WorkAppsQuiet.TAG, "dewidebug workapps opening notification access settings granted=$access")
                context.startActivity(notificationAccessIntent())
            },
        ) { Text(stringResource(R.string.work_apps_access_open)) }
        if (!access) {
            Text(
                stringResource(R.string.work_apps_access_restricted),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun notificationAccessIntent(): Intent =
    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
