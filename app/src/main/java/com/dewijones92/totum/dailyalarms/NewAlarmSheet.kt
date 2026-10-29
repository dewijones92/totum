package com.dewijones92.totum.dailyalarms

import android.content.res.Resources
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.reminders.kit.TimePickDialog
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

const val NEW_ALARM_TAG = "oneoff-new"
const val NEW_ALARM_TITLE_TAG = "oneoff-title"
const val NEW_ALARM_SET_TAG = "oneoff-set"
const val NEW_ALARM_PICK_TIME_TAG = "new-pick-time"
private const val MINUTES_AN_HOUR = 60L

fun Resources.dayPhrase(date: LocalDate, today: LocalDate): String = when (date) {
    today -> getString(R.string.dailyalarm_board_today)
    today.plusDays(1) -> getString(R.string.dailyalarm_board_tomorrow)
    else -> date.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
}

@Composable
internal fun NewAlarmButton(onClick: () -> Unit) {
    Button(
        onClick = {
            Diag.log(DailyAlarmController.TAG, "dewidebug dailyalarm new alarm sheet opened from the page")
            onClick()
        },
        modifier = Modifier.testTag(NEW_ALARM_TAG),
    ) {
        Icon(Icons.Filled.Add, contentDescription = null)
        Text(stringResource(R.string.oneoff_new), modifier = Modifier.padding(start = 4.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NewAlarmSheet(
    alarms: DailyAlarmController,
    recentTitles: List<String>,
    onDone: () -> Unit,
) {
    var title by rememberSaveable { mutableStateOf("") }
    var day by rememberSaveable { mutableStateOf<String?>(null) }
    var time by rememberSaveable { mutableStateOf<String?>(null) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var pickingDate by rememberSaveable { mutableStateOf(false) }
    var kindName by rememberSaveable { mutableStateOf(AlarmKind.ALARM.name) }
    var repeatName by rememberSaveable { mutableStateOf(Repeat.ONCE.name) }
    var customDays by rememberSaveable { mutableStateOf("") }
    val kind = AlarmKind.valueOf(kindName)
    val repeat = Repeat.valueOf(repeatName)
    val days = repeat.days(customDays)
    val now = alarms.localNow()
    val chosenDay = day?.let(LocalDate::parse)
    val target = time?.let { OneOffWhen.at(now, LocalTime.parse(it), chosenDay) }
    val choice = NewAlarmChoice(title, kind, repeat, days, target, time?.let(LocalTime::parse))
    ModalBottomSheet(
        onDismissRequest = onDone,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(stringResource(R.string.oneoff_new), style = MaterialTheme.typography.titleLarge)
            TitleField(title, recentTitles) { title = it }
            KindChips(kind, "new-kind") { kindName = it.name }
            RepeatChips(repeat, days, onRepeat = { repeatName = it.name }) { toggled ->
                customDays = Repeat.toggle(customDays, toggled)
            }
            WhenPickers(
                repeat,
                chosenDay,
                now.toLocalDate(),
                onIn = { minutes ->
                    val at = OneOffWhen.after(alarms.localNow(), minutes)
                    day = at.toLocalDate().toString()
                    time = at.toLocalTime().toString()
                },
                onPickTime = { picking = true },
                onDay = { day = it.toString() },
                onPickDate = { pickingDate = true },
            )
            WhenItRings(choice, now.toLocalDate())
            SetOrCancel(alarms, choice, onDone)
        }
    }
    if (picking) {
        TimePickDialog(stringResource(R.string.oneoff_pick_time), choice.time ?: now.toLocalTime().withNano(0), {
            time = it.toString()
            picking = false
            Diag.log(DailyAlarmController.TAG, "dewidebug dailyalarm new sheet picked $it (day=$day repeat=$repeat)")
        }) { picking = false }
    }
    if (pickingDate) {
        DayPicker(chosenDay ?: now.toLocalDate(), onPick = { day = it.toString() }) { pickingDate = false }
    }
}

@Composable
private fun WhenPickers(
    repeat: Repeat,
    chosenDay: LocalDate?,
    today: LocalDate,
    onIn: (Long) -> Unit,
    onPickTime: () -> Unit,
    onDay: (LocalDate) -> Unit,
    onPickDate: () -> Unit,
) {
    if (repeat == Repeat.ONCE) {
        WhenChips(onIn, onPickTime)
        DayChips(chosenDay, today, onDay, onPickDate)
    } else {
        SuggestionChip(
            onClick = onPickTime,
            label = { Text(stringResource(R.string.oneoff_pick_time)) },
            modifier = Modifier.testTag(NEW_ALARM_PICK_TIME_TAG),
        )
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TitleField(title: String, recentTitles: List<String>, onTitle: (String) -> Unit) {
    OutlinedTextField(
        value = title,
        onValueChange = onTitle,
        label = { Text(stringResource(R.string.oneoff_title_hint)) },
        modifier = Modifier.fillMaxWidth().testTag(NEW_ALARM_TITLE_TAG),
    )
    if (recentTitles.isEmpty()) return
    Text(stringResource(R.string.oneoff_recent), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        recentTitles.forEach { recent -> SuggestionChip(onClick = { onTitle(recent) }, label = { Text(recent) }) }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun WhenChips(onIn: (Long) -> Unit, onPickTime: () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OneOffWhen.OFFSETS_MINUTES.forEach { minutes ->
            val label = if (minutes < MINUTES_AN_HOUR) {
                stringResource(R.string.oneoff_in_minutes, minutes.toInt())
            } else {
                stringResource(R.string.oneoff_in_hours, (minutes / MINUTES_AN_HOUR).toInt())
            }
            SuggestionChip(onClick = { onIn(minutes) }, label = { Text(label) })
        }
        SuggestionChip(onClick = onPickTime, label = { Text(stringResource(R.string.oneoff_pick_time)) })
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun DayChips(chosen: LocalDate?, today: LocalDate, onDay: (LocalDate) -> Unit, onPickDate: () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = chosen == today,
            onClick = { onDay(today) },
            label = { Text(stringResource(R.string.oneoff_today)) },
        )
        FilterChip(
            selected = chosen == today.plusDays(1),
            onClick = { onDay(today.plusDays(1)) },
            label = { Text(stringResource(R.string.oneoff_tomorrow)) },
        )
        FilterChip(
            selected = chosen != null && chosen > today.plusDays(1),
            onClick = onPickDate,
            label = { Text(stringResource(R.string.oneoff_pick_date)) },
        )
    }
}

@Composable
private fun WhenItRings(choice: NewAlarmChoice, today: LocalDate) {
    val resources = LocalResources.current
    val target = choice.target
    val time = choice.time
    Text(
        when {
            choice.repeat == Repeat.ONCE && target != null -> stringResource(
                R.string.oneoff_when,
                DailyAlarmNotifications.hhmm(target.toLocalTime()),
                resources.dayPhrase(target.toLocalDate(), today),
            )
            choice.repeat != Repeat.ONCE && time != null && choice.days.isNotEmpty() -> stringResource(
                R.string.new_alarm_repeats,
                DailyAlarmNotifications.hhmm(time),
                resources.repeatPhrase(choice.repeat, choice.days),
            )
            else -> stringResource(R.string.oneoff_when_unset)
        },
        style = MaterialTheme.typography.titleMedium,
    )
}

@Composable
private fun SetOrCancel(alarms: DailyAlarmController, choice: NewAlarmChoice, onDone: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 16.dp)) {
        OutlinedButton(onClick = onDone) { Text(stringResource(R.string.oneoff_cancel)) }
        Button(
            enabled = choice.ready,
            onClick = {
                val message = choice.set(alarms, resources)
                Toast.makeText(context, message.text, Toast.LENGTH_LONG).show()
                if (message.ok) onDone()
            },
            modifier = Modifier.testTag(NEW_ALARM_SET_TAG),
        ) {
            Text(
                stringResource(
                    if (choice.kind == AlarmKind.REMINDER) R.string.new_reminder_set else R.string.oneoff_set
                )
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayPicker(initial: LocalDate, onPick: (LocalDate) -> Unit, onClose: () -> Unit) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onClose,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                onClose()
            }) { Text(stringResource(R.string.oneoff_ok)) }
        },
    ) { DatePicker(state) }
}
