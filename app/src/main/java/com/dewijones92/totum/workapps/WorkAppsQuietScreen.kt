package com.dewijones92.totum.workapps

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.dailyalarms.WorkAppsSettings
import com.dewijones92.totum.reminders.DayHours
import com.dewijones92.totum.reminders.kit.KeepScreenAwake
import com.dewijones92.totum.reminders.kit.TimePickDialog
import com.dewijones92.totum.ui.common.BackHeader
import com.dewijones92.totum.ui.settings.QuestCard
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle

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
    val update: (String, (WorkAppsSettings) -> WorkAppsSettings) -> Unit = { source, change ->
        workApps.update("page: $source", change)
    }
    Surface(modifier = modifier.fillMaxSize()) {
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
            QuestCard(Icons.Outlined.Schedule, stringResource(R.string.work_apps_hours_title)) {
                HoursSection(settings, update)
            }
            QuestCard(Icons.Outlined.Apps, stringResource(R.string.work_apps_apps_title)) {
                AppsSection(settings, update)
            }
            QuestCard(Icons.Outlined.Pause, stringResource(R.string.work_apps_pause_title)) {
                PauseSection(workApps, settings)
            }
            Spacer(Modifier.size(32.dp))
        }
    }
}

@Composable
private fun StatusCard(
    workApps: WorkAppsQuiet,
    settings: WorkAppsSettings,
    access: Boolean,
    generation: Int,
    update: (String, (WorkAppsSettings) -> WorkAppsSettings) -> Unit,
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

@Composable
private fun HoursSection(
    settings: WorkAppsSettings,
    update: (String, (WorkAppsSettings) -> WorkAppsSettings) -> Unit,
) {
    Text(
        stringResource(R.string.work_apps_hours_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val locale = LocalConfiguration.current.locales[0]
    DayOfWeek.entries.forEach { day ->
        val name = day.getDisplayName(TextStyle.FULL, locale)
        DayRow(name, settings.window.hoursOn(day)) { hours -> update("hours $day") { it.withDay(day, hours) } }
    }
}

@Composable
private fun DayRow(name: String, hours: DayHours?, onChange: (DayHours?) -> Unit) {
    var picking by rememberSaveable(name) { mutableStateOf<Boolean?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Switch(
            checked = hours != null,
            onCheckedChange = { on -> onChange(if (on) WorkAppsSettings.DEFAULT_DAY else null) },
            modifier = Modifier.testTag("work-apps-day-$name").padding(end = 12.dp),
        )
        Text(
            if (hours == null) "$name · ${stringResource(R.string.work_apps_day_off)}" else name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        if (hours != null) {
            TextButton(onClick = { picking = true }) { Text(minuteText(hours.startMinuteOfDay)) }
            Text(stringResource(R.string.work_apps_hours_to), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { picking = false }) { Text(minuteText(hours.endMinuteOfDay)) }
        }
    }
    val choosingStart = picking
    if (hours != null && choosingStart != null) {
        val initial = (if (choosingStart) hours.startMinuteOfDay else hours.endMinuteOfDay).let(::timeOf)
        TimePickDialog(
            title = stringResource(
                if (choosingStart) R.string.work_apps_hours_start_title else R.string.work_apps_hours_end_title,
            ),
            initial = initial,
            onPick = { time ->
                picking = null
                val minute = time.hour * DayHours.MINUTES_PER_HOUR + time.minute
                onChange(
                    if (choosingStart) {
                        hours.copy(startMinuteOfDay = minute)
                    } else {
                        hours.copy(endMinuteOfDay = if (minute == 0) DayHours.MINUTES_PER_DAY else minute)
                    },
                )
            },
            onDismiss = { picking = null },
        )
    }
}

@Composable
private fun AppsSection(settings: WorkAppsSettings, update: (String, (WorkAppsSettings) -> WorkAppsSettings) -> Unit) {
    val context = LocalContext.current
    var adding by rememberSaveable { mutableStateOf(false) }
    if (settings.packages.isEmpty()) {
        Text(stringResource(R.string.work_apps_apps_none), style = MaterialTheme.typography.bodyMedium)
    }
    settings.packages.forEach { packageName ->
        val label = remember(packageName) { context.appLabel(packageName) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = { update("remove $packageName") { it.withoutApp(packageName) } },
                modifier = Modifier.testTag("work-apps-remove-$packageName"),
            ) { Text(stringResource(R.string.work_apps_remove, label)) }
        }
    }
    Button(onClick = { adding = true }, modifier = Modifier.testTag("work-apps-add")) {
        Text(stringResource(R.string.work_apps_add))
    }
    Text(
        stringResource(R.string.work_apps_sound_tip),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (adding) {
        AppPickerDialog(
            chosen = settings.packages.toSet(),
            onPick = { packageName ->
                adding = false
                update("add $packageName") { it.withApp(packageName) }
            },
            onDismiss = { adding = false },
        )
    }
}

@Composable
private fun PauseSection(workApps: WorkAppsQuiet, settings: WorkAppsSettings) {
    val now = remember(settings) { Instant.now() }
    Text(
        stringResource(R.string.work_apps_pause_summary),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val until = settings.pausedUntil?.takeIf { settings.isPausedAt(now) }
    if (until != null) {
        Text(
            stringResource(R.string.work_apps_paused, whenText(until, now, ZoneId.systemDefault())),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.testTag("work-apps-paused"),
        )
        Button(onClick = { workApps.resume("page") }) { Text(stringResource(R.string.work_apps_resume)) }
        return
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WorkAppsSettings.PAUSE_CHOICES_HOURS.forEach { hours ->
            AssistChip(
                onClick = { workApps.pauseFor(hours, "page") },
                label = { Text(pluralStringResource(R.plurals.work_apps_pause_hours, hours, hours)) },
                modifier = Modifier.testTag("work-apps-pause-$hours"),
            )
        }
        AssistChip(
            onClick = { workApps.pauseUntilNextWorkStart("page") },
            label = { Text(stringResource(R.string.work_apps_pause_next_day)) },
            modifier = Modifier.testTag("work-apps-pause-next-day"),
        )
    }
}

private fun minuteText(minuteOfDay: Int): String =
    "%02d:%02d".format(minuteOfDay / DayHours.MINUTES_PER_HOUR, minuteOfDay % DayHours.MINUTES_PER_HOUR)

private fun timeOf(minuteOfDay: Int): LocalTime =
    LocalTime.ofSecondOfDay((minuteOfDay % DayHours.MINUTES_PER_DAY) * SECONDS_PER_MINUTE)

private const val SECONDS_PER_MINUTE = 60L
