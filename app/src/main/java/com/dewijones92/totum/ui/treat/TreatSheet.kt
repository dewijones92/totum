package com.dewijones92.totum.ui.treat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.targets.TargetState
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.pins.Pin
import com.dewijones92.totum.pins.titleThrough
import com.dewijones92.totum.treat.Treat
import com.dewijones92.totum.treat.TreatChoice
import com.dewijones92.totum.treat.TreatEnd
import com.dewijones92.totum.treat.TreatRules
import com.dewijones92.totum.ui.common.LocalItemShield

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TreatSheet(container: AppContainer, treat: Treat, onDismiss: () -> Unit) {
    val saved by treat.choice.collectAsStateWithLifecycle()
    val states by container.targets.targets.collectAsStateWithLifecycle()
    val pins by container.pinStore.pins.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf(saved) }
    var savedNote by remember { mutableStateOf(false) }
    val tvs = remember(states) { states.filter(TreatRules::canBeChosen) }
    val treatable = remember(pins) { pins.filter(TreatRules::treatable) }
    val change = { next: TreatChoice ->
        draft = next
        savedNote = false
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag(SHEET_TAG),
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.treat_sheet_title), style = MaterialTheme.typography.titleLarge)
            TvChoices(tvs, draft, change)
            WhatChoices(treatable, draft, change)
            MinutesChoice(draft, change)
            EndChoices(draft, tvs.firstOrNull { it.target.id == draft.targetId }, change)
            FadeChoice(draft, change)
            Text(stringResource(R.string.treat_sheet_this_time), style = MaterialTheme.typography.bodySmall)
            TreatActions(
                draft = draft,
                onStart = {
                    Diag.log("treat", "dewidebug the treat sheet started a treat for this time only: $draft")
                    treat.start("the treat sheet", override = draft)
                    onDismiss()
                },
                onSave = {
                    treat.choose(draft, "the treat sheet")
                    savedNote = true
                },
            )
            if (savedNote && saved == draft.clamped) {
                Text(stringResource(R.string.treat_sheet_saved), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TvChoices(tvs: List<TargetState>, draft: TreatChoice, change: (TreatChoice) -> Unit) {
    Text(stringResource(R.string.treat_sheet_tv), style = MaterialTheme.typography.titleSmall)
    if (tvs.isEmpty() && draft.targetId == null) {
        Text(stringResource(R.string.treat_sheet_tv_none), style = MaterialTheme.typography.bodyMedium)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        tvs.forEach { state ->
            FilterChip(
                selected = draft.targetId == state.target.id,
                onClick = { change(draft.copy(targetId = state.target.id, targetLabel = state.target.label)) },
                label = { Text(state.target.label) },
            )
        }
        val remembered = draft.targetId?.takeIf { id -> tvs.none { it.target.id == id } }
        if (remembered != null) {
            FilterChip(
                selected = true,
                onClick = {},
                label = { Text(stringResource(R.string.treat_sheet_tv_remembered, draft.targetLabel ?: remembered)) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WhatChoices(treatable: List<Pin>, draft: TreatChoice, change: (TreatChoice) -> Unit) {
    val shield = LocalItemShield.current
    Text(stringResource(R.string.treat_sheet_what), style = MaterialTheme.typography.titleSmall)
    if (treatable.isEmpty()) {
        Text(stringResource(R.string.treat_sheet_what_none), style = MaterialTheme.typography.bodyMedium)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        treatable.forEach { pin ->
            FilterChip(
                selected = draft.pinKey == pin.key,
                onClick = { change(draft.copy(pinKey = pin.key)) },
                label = { Text(pin.titleThrough(shield)) },
            )
        }
    }
}

@Composable
private fun MinutesChoice(draft: TreatChoice, change: (TreatChoice) -> Unit) {
    Text(stringResource(R.string.treat_sheet_minutes, draft.minutes), style = MaterialTheme.typography.titleSmall)
    Slider(
        value = draft.minutes.toFloat(),
        onValueChange = { change(draft.copy(minutes = it.toInt())) },
        valueRange = TreatChoice.MIN_MINUTES.toFloat()..TreatChoice.MAX_MINUTES.toFloat(),
        steps = (TreatChoice.MAX_MINUTES - TreatChoice.MIN_MINUTES) / TreatChoice.STEP_MINUTES - 1,
        modifier = Modifier.testTag(SLIDER_TAG),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EndChoices(draft: TreatChoice, tv: TargetState?, change: (TreatChoice) -> Unit) {
    Text(stringResource(R.string.treat_sheet_end), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        END_CHOICES.forEach { (end, label) ->
            FilterChip(
                selected = draft.end == end,
                onClick = { change(draft.copy(end = end)) },
                label = { Text(stringResource(label)) },
            )
        }
    }
    if (draft.end == TreatEnd.SWITCH_OFF && tv != null && !tv.canSwitchOff) {
        Text(
            stringResource(R.string.treat_sheet_cannot_switch_off, tv.target.label),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun FadeChoice(draft: TreatChoice, change: (TreatChoice) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.treat_sheet_fade),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = draft.fade, onCheckedChange = { change(draft.copy(fade = it)) })
    }
}

@Composable
private fun TreatActions(draft: TreatChoice, onStart: () -> Unit, onSave: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Button(
            enabled = draft.complete,
            onClick = onStart,
            modifier = Modifier.weight(1f).testTag(START_TAG),
        ) { Text(stringResource(R.string.treat_sheet_start)) }
        OutlinedButton(
            onClick = onSave,
            modifier = Modifier.weight(1f).testTag(SAVE_TAG),
        ) { Text(stringResource(R.string.treat_sheet_save)) }
    }
}

private val END_CHOICES = listOf(
    TreatEnd.SWITCH_OFF to R.string.treat_end_switch_off,
    TreatEnd.PAUSE to R.string.treat_end_pause,
    TreatEnd.STOP to R.string.treat_end_stop,
)

const val SHEET_TAG = "treat-sheet"
const val SLIDER_TAG = "treat-minutes"
const val START_TAG = "treat-start"
const val SAVE_TAG = "treat-save"
