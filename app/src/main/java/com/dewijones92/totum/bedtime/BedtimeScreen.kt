package com.dewijones92.totum.bedtime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.VolumeDown
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.reminders.BedtimeSettings
import com.dewijones92.totum.reminders.BedtimeStatus
import com.dewijones92.totum.reminders.DayHours
import com.dewijones92.totum.reminders.kit.KeepScreenAwake
import com.dewijones92.totum.reminders.kit.TimePickDialog
import com.dewijones92.totum.ui.common.BackHeader
import com.dewijones92.totum.ui.settings.QuestCard
import com.dewijones92.totum.ui.settings.SnappingSlider
import com.dewijones92.totum.workapps.whenText
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle

@Composable
fun BedtimeScreen(bedtime: Bedtime, onBack: () -> Unit, modifier: Modifier = Modifier) {
    KeepScreenAwake("bedtime page")
    val settings by bedtime.settings.collectAsStateWithLifecycle()
    val update: (String, (BedtimeSettings) -> BedtimeSettings) -> Unit = { source, change ->
        bedtime.update("page: $source", change)
    }
    Surface(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            BackHeader(stringResource(R.string.bedtime_name), onBack)
            Text(
                stringResource(R.string.bedtime_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            StatusCard(bedtime, settings, update)
            QuestCard(Icons.Outlined.Schedule, stringResource(R.string.bedtime_when_title)) {
                WhenSection(settings, update)
            }
            QuestCard(Icons.Outlined.VolumeDown, stringResource(R.string.bedtime_fade_title)) {
                FadeSection(settings, update)
            }
            Spacer(Modifier.size(32.dp))
        }
    }
}

@Composable
private fun StatusCard(
    bedtime: Bedtime,
    settings: BedtimeSettings,
    update: (String, (BedtimeSettings) -> BedtimeSettings) -> Unit,
) {
    val timer by bedtime.timerState.collectAsStateWithLifecycle()
    val line = remember(settings, timer) { bedtime.status() }
    QuestCard(Icons.Outlined.Bedtime, stringResource(R.string.bedtime_status_title)) {
        Text(
            statusText(line),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.testTag("bedtime-status"),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.bedtime_switch), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.bedtime_switch_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.enabled,
                onCheckedChange = { on -> update("switch") { it.copy(enabled = on) } },
                modifier = Modifier.testTag("bedtime-switch"),
            )
        }
    }
}

@Composable
private fun statusText(status: BedtimeStatus): String {
    val now = remember(status) { Instant.now() }
    val zone = ZoneId.systemDefault()
    return when (status) {
        BedtimeStatus.Off -> stringResource(R.string.bedtime_status_off)
        is BedtimeStatus.Upcoming ->
            stringResource(R.string.bedtime_status_upcoming, whenText(status.at, now, zone))
        is BedtimeStatus.Tonight -> {
            val until = whenText(status.until, now, zone)
            when {
                status.declined -> stringResource(R.string.bedtime_status_declined, until)
                status.armed -> stringResource(R.string.bedtime_status_armed, until)
                else -> stringResource(R.string.bedtime_status_waiting, until)
            }
        }
    }
}

@Composable
private fun WhenSection(settings: BedtimeSettings, update: (String, (BedtimeSettings) -> BedtimeSettings) -> Unit) {
    var picking by rememberSaveable { mutableStateOf<Boolean?>(null) }
    Text(
        stringResource(R.string.bedtime_when_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.bedtime_starts),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = { picking = true }, modifier = Modifier.testTag("bedtime-start")) {
            Text(minuteText(settings.startMinuteOfDay))
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.bedtime_wakes),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = { picking = false }, modifier = Modifier.testTag("bedtime-wake")) {
            Text(minuteText(settings.wakeMinuteOfDay))
        }
    }
    Text(stringResource(R.string.bedtime_nights), style = MaterialTheme.typography.titleSmall)
    val locale = LocalConfiguration.current.locales[0]
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DayOfWeek.entries.forEach { day ->
            FilterChip(
                selected = day in settings.nights,
                onClick = {
                    update("night $day") {
                        it.copy(nights = if (day in it.nights) it.nights - day else it.nights + day)
                    }
                },
                label = { Text(day.getDisplayName(TextStyle.SHORT, locale)) },
                modifier = Modifier.testTag("bedtime-night-$day"),
            )
        }
    }
    val choosingStart = picking
    if (choosingStart != null) {
        val initial = (if (choosingStart) settings.startMinuteOfDay else settings.wakeMinuteOfDay).let(::timeOf)
        TimePickDialog(
            title = stringResource(if (choosingStart) R.string.bedtime_start_title else R.string.bedtime_wake_title),
            initial = initial,
            onPick = { time ->
                picking = null
                val minute = time.hour * DayHours.MINUTES_PER_HOUR + time.minute
                update(if (choosingStart) "start" else "wake") {
                    if (choosingStart) it.copy(startMinuteOfDay = minute) else it.copy(wakeMinuteOfDay = minute)
                }
            },
            onDismiss = { picking = null },
        )
    }
}

@Composable
private fun FadeSection(settings: BedtimeSettings, update: (String, (BedtimeSettings) -> BedtimeSettings) -> Unit) {
    val resources = LocalResources.current
    Text(
        stringResource(R.string.bedtime_fade_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SnappingSlider(
        label = stringResource(R.string.bedtime_fade_label),
        value = settings.fadeSeconds,
        range = 0..FADE_SLIDER_MAX_SECONDS,
        step = BedtimeSettings.FADE_STEP_SECONDS,
        shown = { seconds ->
            if (seconds == 0) {
                resources.getString(R.string.bedtime_fade_off)
            } else {
                resources.getQuantityString(R.plurals.bedtime_fade_seconds, seconds, seconds)
            }
        },
        tag = "bedtime-fade",
        onCommit = { seconds -> update("fade") { it.copy(fadeSeconds = seconds) } },
    )
}

private fun minuteText(minuteOfDay: Int): String =
    "%02d:%02d".format(minuteOfDay / DayHours.MINUTES_PER_HOUR, minuteOfDay % DayHours.MINUTES_PER_HOUR)

private fun timeOf(minuteOfDay: Int): LocalTime =
    LocalTime.ofSecondOfDay((minuteOfDay % DayHours.MINUTES_PER_DAY) * SECONDS_PER_MINUTE)

private const val SECONDS_PER_MINUTE = 60L
private const val FADE_SLIDER_MAX_SECONDS = 60
