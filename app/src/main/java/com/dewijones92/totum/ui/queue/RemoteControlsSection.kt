package com.dewijones92.totum.ui.queue

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.targets.SoundSettings
import com.dewijones92.totum.data.targets.TargetAction
import com.dewijones92.totum.data.targets.TargetControl
import com.dewijones92.totum.data.targets.TargetState
import com.dewijones92.totum.data.targets.offersBoost
import com.dewijones92.totum.data.targets.offersSkipSilence
import com.dewijones92.totum.data.targets.onATarget
import com.dewijones92.totum.data.targets.showsPicture
import com.dewijones92.totum.data.targets.toSnapshot
import com.dewijones92.totum.domain.RepeatMode
import com.dewijones92.totum.playback.VolumeBoost
import com.dewijones92.totum.targets.TargetsRepository
import com.dewijones92.totum.targets.asTimerState
import com.dewijones92.totum.ui.common.LabelledCheckbox
import com.dewijones92.totum.ui.player.ControlTile
import com.dewijones92.totum.ui.player.LocalCompactTiles
import com.dewijones92.totum.ui.player.LocalPlayerLinks
import com.dewijones92.totum.ui.player.LocalTileAccent
import com.dewijones92.totum.ui.player.PlayerLinks
import com.dewijones92.totum.ui.player.RepeatTile
import com.dewijones92.totum.ui.player.SkipSilenceTile
import com.dewijones92.totum.ui.player.SleepTimerControl
import com.dewijones92.totum.ui.player.ToggleTile
import com.dewijones92.totum.ui.player.endOfGroupSleep

@Composable
internal fun RemoteControls(
    targets: TargetsRepository,
    state: TargetState,
    colourValue: Long,
    open: Boolean,
    onOpen: (Boolean) -> Unit,
) {
    val id = state.target.id
    var switchOff by rememberSaveable(id) { mutableStateOf(false) }
    val switchesOff = switchOff && state.canSwitchOff
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().heightIn(min = ROW_MIN).clickable { onOpen(!open) }
                .testTag(REMOTE_CONTROLS_TAG),
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.target_controls), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.target_controls_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null)
        }
        if (open) {
            CompositionLocalProvider(LocalCompactTiles provides true, LocalTileAccent provides colourValue.asColour()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 8.dp),
                ) {
                    RemoteRepeat(targets, state)
                    RemoteSleep(targets, state, switchesOff)
                    RemoteSound(targets, state)
                    RemoteCaptions(targets, state)
                    RemoteColourTile(targets, id, state.target.label, colourValue)
                }
            }
            RemoteSleepNotes(state, switchOff) {
                Diag.log("targets", "$id: stop and switch off when the sleep timer ends is now $it")
                switchOff = it
            }
        }
    }
}

@Composable
internal fun RemoteRepeat(targets: TargetsRepository, state: TargetState) {
    RepeatTile(
        mode = state.repeat,
        onSetMode = { targets.act(state.target.id, TargetControl(TargetAction.REPEAT, repeat = it)) },
        options = RepeatMode.entries.filter { it.onATarget },
    )
}

@Composable
internal fun RemoteSleep(targets: TargetsRepository, state: TargetState, switchesOff: Boolean) {
    val id = state.target.id
    val snapshot = remember(state.entries, state.at) { state.entries.toSnapshot(state.at) }
    val endOfGroup = remember(snapshot, switchesOff) {
        endOfGroupSleep(snapshot.entries, snapshot.currentIndex) { ids, title ->
            targets.sleepAfterItems(id, ids.map { it.value }, title, switchesOff)
        }
    }
    CompositionLocalProvider(LocalPlayerLinks provides PlayerLinks(endOfGroup = endOfGroup)) {
        SleepTimerControl(
            state = state.sleep.asTimerState(),
            onStart = { targets.startSleep(id, it, switchesOff) },
            onStopAfterItem = {
                state.current?.item?.itemId?.let { current ->
                    targets.sleepAfterItems(id, listOf(current), null, switchesOff)
                }
            },
            onCancel = { targets.cancelSleep(id) },
        )
    }
}

@Composable
internal fun RemoteSleepNotes(state: TargetState, switchOff: Boolean, onSwitchOff: (Boolean) -> Unit) {
    if (state.canSwitchOff) {
        LabelledCheckbox(
            checked = switchOff,
            label = stringResource(R.string.target_sleep_switch_off, state.target.label),
            onChange = onSwitchOff,
        )
    }
    if (state.sleep?.switchesOff == true) {
        Text(
            stringResource(R.string.target_sleep_will_switch_off, state.target.label),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (state.showsPicture) {
        Text(
            stringResource(R.string.target_sound_picture_note, state.target.label),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
internal fun RemoteSound(targets: TargetsRepository, state: TargetState) {
    val id = state.target.id
    if (state.offersSkipSilence) {
        SkipSilenceTile(
            checked = state.sound.skipSilence,
            onChange = {
                targets.act(id, TargetControl(TargetAction.SOUND, sound = state.sound.copy(skipSilence = it)))
            },
        )
    }
    if (!state.offersBoost) return
    ToggleTile(
        icon = Icons.AutoMirrored.Outlined.VolumeUp,
        label = stringResource(R.string.volume_boost),
        checked = state.sound.boost != VolumeBoost.OFF,
        onCheckedChange = { on ->
            val next: SoundSettings = state.sound.copy(boost = if (on) VolumeBoost.AUTO else VolumeBoost.OFF)
            targets.act(id, TargetControl(TargetAction.SOUND, sound = next))
        },
        shortLabel = stringResource(R.string.quick_boost),
    )
}

@Composable
internal fun RemoteColourTile(targets: TargetsRepository, id: String, label: String, colour: Long) {
    var open by remember { mutableStateOf(false) }
    Box {
        ControlTile(
            icon = Icons.Outlined.Palette,
            label = stringResource(R.string.target_colour),
            value = null,
            active = false,
            onClick = { open = true },
        )
        TargetColourMenu(
            expanded = open,
            onDismiss = { open = false },
            label = label,
            current = colour,
            onPick = { targets.recolour(id, it) },
        )
    }
}

private val ROW_MIN = 56.dp
