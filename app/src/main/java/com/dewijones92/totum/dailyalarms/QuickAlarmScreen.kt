package com.dewijones92.totum.dailyalarms

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.reminders.kit.TimeDial
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

const val QUICK_SET_TAG = "quick-set"
const val QUICK_ADD_PRESET_TAG = "quick-add-preset"
const val QUICK_PRESET_NAME_TAG = "quick-preset-name"
const val QUICK_PRESET_SAVE_TAG = "quick-preset-save"
const val QUICK_PRESET_DELETE_TAG = "quick-preset-delete"

fun quickPresetTag(title: String) = "quick-preset-$title"

class QuickPresetActions(
    val add: (String) -> Unit,
    val rename: (String, String) -> Unit,
    val delete: (String) -> Unit,
)

private sealed interface Editing {
    data object New : Editing
    data class Existing(val title: String) : Editing
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickAlarmScreen(
    presets: List<String>,
    now: LocalDateTime,
    actions: QuickPresetActions,
    onSet: (title: String, time: LocalTime) -> Unit,
) {
    var chosen by rememberSaveable { mutableStateOf(presets.firstOrNull()) }
    var editing by remember { mutableStateOf<Editing?>(null) }
    val start = now.plusMinutes(START_AHEAD_MINUTES)
    val dial = rememberTimePickerState(start.hour, start.minute, is24Hour = true)
    val time = LocalTime.of(dial.hour, dial.minute)
    val at = OneOffWhen.at(now, time, null)
    val picked = chosen?.takeIf { it in presets } ?: presets.firstOrNull()
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        ) {
            Text(stringResource(R.string.quick_alarm), style = MaterialTheme.typography.headlineMedium)
            PresetChips(presets, picked, { chosen = it }, { editing = Editing.Existing(it) }) { editing = Editing.New }
            TimeDial(dial)
            Text(
                stringResource(
                    R.string.quick_alarm_rings,
                    DailyAlarmNotifications.hhmm(time),
                    LocalResources.current.dayPhrase(at.toLocalDate(), now.toLocalDate()),
                    inWords(Duration.between(now, at)),
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            Button(
                onClick = { onSet(picked.orEmpty(), time) },
                modifier = Modifier.fillMaxWidth().height(72.dp).testTag(QUICK_SET_TAG),
            ) {
                Text(stringResource(R.string.quick_alarm_set, DailyAlarmNotifications.hhmm(time)))
            }
        }
    }
    editing?.let { edit ->
        PresetDialog(edit, actions, onPicked = { chosen = it }, onDone = { editing = null })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetChips(
    presets: List<String>,
    chosen: String?,
    onChoose: (String) -> Unit,
    onEdit: (String) -> Unit,
    onAdd: () -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (presets.isEmpty()) Text(stringResource(R.string.quick_alarm_no_presets))
        presets.forEach { title ->
            val picked = title == chosen
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (picked) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                tonalElevation = if (picked) 0.dp else 2.dp,
                modifier = Modifier
                    .combinedClickable(onClick = { onChoose(title) }, onLongClick = { onEdit(title) })
                    .testTag(quickPresetTag(title)),
            ) {
                Text(
                    if (picked) "✅ $title" else title,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
        }
        TextButton(onClick = onAdd, modifier = Modifier.testTag(QUICK_ADD_PRESET_TAG)) {
            Text(stringResource(R.string.quick_alarm_add_preset))
        }
    }
}

@Composable
private fun PresetDialog(edit: Editing, actions: QuickPresetActions, onPicked: (String) -> Unit, onDone: () -> Unit) {
    val original = (edit as? Editing.Existing)?.title
    var name by rememberSaveable { mutableStateOf(original.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDone,
        title = {
            Text(
                stringResource(
                    if (original == null) R.string.quick_alarm_new_preset else R.string.quick_alarm_edit_preset
                )
            )
        },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.quick_alarm_preset_name)) },
                singleLine = true,
                modifier = Modifier.testTag(QUICK_PRESET_NAME_TAG),
            )
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    if (original == null) actions.add(name) else actions.rename(original, name)
                    onPicked(name.trim())
                    onDone()
                },
                modifier = Modifier.testTag(QUICK_PRESET_SAVE_TAG),
            ) { Text(stringResource(R.string.quick_alarm_save)) }
        },
        dismissButton = {
            if (original != null) {
                TextButton(
                    onClick = {
                        actions.delete(original)
                        onDone()
                    },
                    modifier = Modifier.testTag(QUICK_PRESET_DELETE_TAG),
                ) { Text(stringResource(R.string.quick_alarm_delete)) }
            }
        },
    )
}

@Composable
private fun inWords(gap: Duration): String {
    val minutes = gap.toMinutes().coerceAtLeast(0)
    return if (minutes >= MINUTES_AN_HOUR) {
        stringResource(R.string.quick_alarm_in_hours_minutes, minutes / MINUTES_AN_HOUR, minutes % MINUTES_AN_HOUR)
    } else {
        stringResource(R.string.quick_alarm_in_minutes, minutes)
    }
}

private const val START_AHEAD_MINUTES = 15L
private const val MINUTES_AN_HOUR = 60L
