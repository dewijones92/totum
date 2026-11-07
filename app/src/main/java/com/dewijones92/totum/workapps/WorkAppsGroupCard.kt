package com.dewijones92.totum.workapps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.dailyalarms.QuietGroup
import com.dewijones92.totum.dailyalarms.WorkAppsSettings
import com.dewijones92.totum.reminders.DayHours
import com.dewijones92.totum.reminders.kit.TimePickDialog
import com.dewijones92.totum.ui.settings.QuestCard
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle

@Composable
internal fun WorkAppsGroupCard(
    workApps: WorkAppsQuiet,
    group: QuietGroup,
    settings: WorkAppsSettings,
    update: SettingsUpdate,
    removals: GroupRemovals,
) {
    val context = LocalContext.current
    val line = remember(group, settings) {
        context.groupCardLine(workApps.status(), group, Instant.now(), ZoneId.systemDefault())
    }
    QuestCard(Icons.Outlined.Schedule, "${group.emoji} ${group.name}") {
        Text(
            line,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.testTag("work-apps-line-${group.id}")
        )
        NameAndEmoji(group, update)
        SectionTitle(R.string.work_apps_hours_title)
        HoursSection(group, update)
        SectionTitle(R.string.work_apps_apps_title)
        AppsSection(group, settings, update, removals)
        SectionTitle(R.string.work_apps_pause_title)
        PauseSection(workApps, group)
        TextButton(
            onClick = { removals.group(group.id) },
            modifier = Modifier.testTag("work-apps-delete-${group.id}"),
        ) { Text(stringResource(R.string.work_apps_delete_group, group.name)) }
    }
}

@Composable
private fun SectionTitle(title: Int) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun NameAndEmoji(group: QuietGroup, update: SettingsUpdate) {
    var name by rememberSaveable(group.id, group.name) { mutableStateOf(group.name) }
    val commit = {
        if (name != group.name) update("rename ${group.id}") { it.edited(group.id) { g -> g.copy(name = name) } }
    }
    OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text(stringResource(R.string.work_apps_group_name)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused) commit() }
            .testTag("work-apps-name-${group.id}"),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        QuietGroup.EMOJI_CHOICES.forEach { emoji ->
            FilterChip(
                selected = emoji == group.emoji,
                onClick = { update("emoji ${group.id}") { it.edited(group.id) { g -> g.copy(emoji = emoji) } } },
                label = { Text(emoji) },
                modifier = Modifier.testTag("work-apps-emoji-${group.id}-$emoji"),
            )
        }
    }
}

@Composable
private fun HoursSection(group: QuietGroup, update: SettingsUpdate) {
    Text(
        stringResource(R.string.work_apps_hours_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val locale = LocalConfiguration.current.locales[0]
    DayOfWeek.entries.forEach { day ->
        val name = day.getDisplayName(TextStyle.FULL, locale)
        DayRow(group.id, name, group.window.hoursOn(day)) { hours ->
            update("hours ${group.id} $day") { it.withDay(group.id, day, hours) }
        }
    }
}

@Composable
private fun DayRow(groupId: String, name: String, hours: DayHours?, onChange: (DayHours?) -> Unit) {
    var picking by rememberSaveable("$groupId-$name") { mutableStateOf<Boolean?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Switch(
            checked = hours != null,
            onCheckedChange = { on -> onChange(if (on) QuietGroup.DEFAULT_DAY else null) },
            modifier = Modifier.testTag("work-apps-day-$groupId-$name").padding(end = 12.dp),
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
private fun AppsSection(
    group: QuietGroup,
    settings: WorkAppsSettings,
    update: SettingsUpdate,
    removals: GroupRemovals
) {
    val context = LocalContext.current
    var adding by rememberSaveable(group.id) { mutableStateOf(false) }
    if (group.packages.isEmpty()) {
        Text(stringResource(R.string.work_apps_apps_none), style = MaterialTheme.typography.bodyMedium)
    }
    group.packages.forEach { packageName ->
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
                onClick = { removals.app(group.id to packageName) },
                modifier = Modifier.testTag("work-apps-remove-${group.id}-$packageName"),
            ) { Text(stringResource(R.string.work_apps_remove, label)) }
        }
    }
    Button(onClick = { adding = true }, modifier = Modifier.testTag("work-apps-add-${group.id}")) {
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
                update("add $packageName to ${group.id}") { it.withApp(group.id, packageName) }
            },
            onDismiss = { adding = false },
        )
    }
}

@Composable
private fun PauseSection(workApps: WorkAppsQuiet, group: QuietGroup) {
    val now = remember(group) { Instant.now() }
    Text(
        stringResource(R.string.work_apps_pause_summary),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val until = group.pausedUntil?.takeIf { group.isPausedAt(now) }
    if (until != null) {
        Text(
            stringResource(R.string.work_apps_paused, whenText(until, now, ZoneId.systemDefault())),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.testTag("work-apps-paused-${group.id}"),
        )
        Button(onClick = { workApps.resume(group.id, "page") }) { Text(stringResource(R.string.work_apps_resume)) }
        return
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WorkAppsSettings.PAUSE_CHOICES_HOURS.forEach { hours ->
            AssistChip(
                onClick = { workApps.pauseFor(group.id, hours, "page") },
                label = { Text(pluralStringResource(R.plurals.work_apps_pause_hours, hours, hours)) },
                modifier = Modifier.testTag("work-apps-pause-${group.id}-$hours"),
            )
        }
        AssistChip(
            onClick = { workApps.pauseUntilNextWorkStart(group.id, "page") },
            label = { Text(stringResource(R.string.work_apps_pause_next_day)) },
            modifier = Modifier.testTag("work-apps-pause-next-day-${group.id}"),
        )
    }
}

private fun minuteText(minuteOfDay: Int): String =
    "%02d:%02d".format(minuteOfDay / DayHours.MINUTES_PER_HOUR, minuteOfDay % DayHours.MINUTES_PER_HOUR)

private fun timeOf(minuteOfDay: Int): LocalTime =
    LocalTime.ofSecondOfDay((minuteOfDay % DayHours.MINUTES_PER_DAY) * SECONDS_PER_MINUTE)

private const val SECONDS_PER_MINUTE = 60L
