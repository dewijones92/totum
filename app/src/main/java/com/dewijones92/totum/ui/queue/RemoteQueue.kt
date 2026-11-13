package com.dewijones92.totum.ui.queue

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.backup.titleOf
import com.dewijones92.totum.data.targets.SoundSettings
import com.dewijones92.totum.data.targets.TargetAction
import com.dewijones92.totum.data.targets.TargetColours
import com.dewijones92.totum.data.targets.TargetControl
import com.dewijones92.totum.data.targets.TargetEntry
import com.dewijones92.totum.data.targets.TargetPower
import com.dewijones92.totum.data.targets.TargetState
import com.dewijones92.totum.data.targets.isLive
import com.dewijones92.totum.data.targets.onATarget
import com.dewijones92.totum.data.targets.shownLengthMs
import com.dewijones92.totum.data.targets.showsPicture
import com.dewijones92.totum.data.targets.toQueueEntry
import com.dewijones92.totum.domain.RepeatMode
import com.dewijones92.totum.playback.VolumeBoost
import com.dewijones92.totum.targets.TargetsRepository
import com.dewijones92.totum.ui.common.EmptyState
import com.dewijones92.totum.ui.common.LocalItemShield
import com.dewijones92.totum.ui.common.MediaItemRow
import com.dewijones92.totum.ui.common.TagBadge
import com.dewijones92.totum.ui.common.formatClock
import com.dewijones92.totum.ui.common.liveTagColor
import com.dewijones92.totum.ui.common.liveTagLabel
import com.dewijones92.totum.ui.common.rowFacts
import com.dewijones92.totum.ui.player.LocalCompactTiles
import com.dewijones92.totum.ui.player.RepeatTile
import com.dewijones92.totum.video.LiveTag
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun RemoteQueue(targets: TargetsRepository, id: String, modifier: Modifier = Modifier) {
    val states by targets.targets.collectAsStateWithLifecycle()
    val colours by targets.colours.collectAsStateWithLifecycle()
    val state = states.firstOrNull { it.target.id == id }
    val colour = (colours[id] ?: TargetColours.PHONE).asColour()
    if (state == null) {
        EmptyState(
            icon = Icons.AutoMirrored.Filled.QueueMusic,
            headline = stringResource(R.string.target_waiting_headline, targets.targetOf(id)?.label ?: id),
            supportingText = stringResource(R.string.target_waiting_hint),
            modifier = modifier,
        )
        return
    }
    Column(modifier = modifier) {
        RemoteHeader(targets, state, colour)
        if (state.entries.isEmpty()) {
            EmptyState(
                icon = Icons.AutoMirrored.Filled.QueueMusic,
                headline = stringResource(R.string.target_empty_headline, state.target.label),
                supportingText = stringResource(R.string.target_empty_hint),
            )
        } else {
            RemoteEntries(targets, state, colour)
        }
    }
}

@Composable
private fun RemoteHeader(targets: TargetsRepository, state: TargetState, colour: androidx.compose.ui.graphics.Color) {
    val id = state.target.id
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = when {
                state.current == null -> stringResource(R.string.target_idle, state.target.label)
                state.playing -> stringResource(R.string.target_now_playing, state.target.label)
                else -> stringResource(R.string.target_paused, state.target.label)
            },
            style = MaterialTheme.typography.titleMedium,
            color = colour,
        )
        state.current?.let { current ->
            Text(LocalItemShield.current.titleOf(current.item), style = MaterialTheme.typography.bodyLarge)
            if (state.isLive) {
                TagBadge(liveTagLabel(LiveTag.Live), liveTagColor(LiveTag.Live), Modifier.padding(vertical = 4.dp))
            }
            state.shownLengthMs?.let { duration -> RemoteSeek(targets, state, duration, colour) }
        }
        RemotePower(targets, state)
        RemoteTrouble(targets, state)
        RemoteControls(targets, state)
        RemoteRepeat(targets, state)
        RemoteSound(targets, state)
        RemoteColours(targets, id)
    }
}

@Composable
private fun RemoteSeek(targets: TargetsRepository, state: TargetState, duration: Long, colour: Color) {
    val id = state.target.id
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = dragging ?: state.positionMs.toFloat().coerceIn(0f, duration.toFloat())
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = {
                val to = (state.positionMs - SEEK_BACK_MS).coerceAtLeast(0)
                targets.act(id, TargetControl(TargetAction.SEEK, positionMs = to))
            },
        ) { Icon(Icons.Filled.Replay10, stringResource(R.string.target_seek_back)) }
        Slider(
            value = shown,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { targets.act(id, TargetControl(TargetAction.SEEK, positionMs = it.toLong())) }
                dragging = null
            },
            valueRange = 0f..duration.toFloat(),
            colors = SliderDefaults.colors(thumbColor = colour, activeTrackColor = colour),
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = {
                val to = (state.positionMs + SEEK_FORWARD_MS).coerceAtMost(duration)
                targets.act(id, TargetControl(TargetAction.SEEK, positionMs = to))
            },
        ) { Icon(Icons.Filled.Forward30, stringResource(R.string.target_seek_forward)) }
    }
    Text(
        stringResource(
            R.string.target_position,
            formatClock(shown.toLong().milliseconds),
            formatClock(duration.milliseconds),
        ),
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun RemoteTrouble(targets: TargetsRepository, state: TargetState) {
    val note by targets.problem.collectAsStateWithLifecycle()
    val line = targets.troubleLine(state)
    if (line != null) Text(line, color = MaterialTheme.colorScheme.error)
    note?.takeIf { it != line }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun RemotePower(targets: TargetsRepository, state: TargetState) {
    when (state.power) {
        TargetPower.ON -> Unit
        TargetPower.ASLEEP -> TextButton(onClick = { targets.command(state.target.id, TargetAction.WAKE) }) {
            Text(stringResource(R.string.target_asleep_wake))
        }
        TargetPower.WAKING -> Text(stringResource(R.string.target_waking, state.target.label))
    }
}

@Composable
private fun RemoteControls(targets: TargetsRepository, state: TargetState) {
    val id = state.target.id
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { targets.command(id, TargetAction.PREVIOUS) }) {
            Icon(Icons.Filled.SkipPrevious, stringResource(R.string.target_previous))
        }
        IconButton(onClick = { targets.command(id, if (state.playing) TargetAction.PAUSE else TargetAction.PLAY) }) {
            Icon(
                if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                stringResource(if (state.playing) R.string.target_pause else R.string.target_play),
            )
        }
        IconButton(onClick = { targets.command(id, TargetAction.NEXT) }) {
            Icon(Icons.Filled.SkipNext, stringResource(R.string.target_next))
        }
        TextButton(
            onClick = { targets.command(id, TargetAction.STOP) }
        ) { Text("⏹️ " + stringResource(R.string.target_stop)) }
    }
    var volume by remember(state.volume) { mutableStateOf(state.volume?.toFloat()) }
    Text(
        volume?.let { stringResource(R.string.target_volume, it.toInt()) }
            ?: stringResource(R.string.target_volume_unknown),
        style = MaterialTheme.typography.bodySmall,
    )
    Slider(
        value = volume ?: 0f,
        onValueChange = { volume = it },
        onValueChangeFinished = {
            volume?.let { targets.act(id, TargetControl(TargetAction.VOLUME, volume = it.toInt())) }
        },
        valueRange = 0f..VOLUME_MAX,
    )
}

@Composable
private fun RemoteRepeat(targets: TargetsRepository, state: TargetState) {
    CompositionLocalProvider(LocalCompactTiles provides true) {
        RepeatTile(
            mode = state.repeat,
            onSetMode = { targets.act(state.target.id, TargetControl(TargetAction.REPEAT, repeat = it)) },
            modifier = Modifier.padding(vertical = 4.dp),
            options = RepeatMode.entries.filter { it.onATarget },
        )
    }
}

@Composable
private fun RemoteSound(targets: TargetsRepository, state: TargetState) {
    val id = state.target.id
    val picture = state.showsPicture
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            enabled = !picture,
            selected = state.sound.skipSilence,
            onClick = {
                targets.act(
                    id,
                    TargetControl(TargetAction.SOUND, sound = state.sound.copy(skipSilence = !state.sound.skipSilence))
                )
            },
            label = { Text(stringResource(R.string.target_skip_silence)) },
        )
        val boosted = state.sound.boost != VolumeBoost.OFF
        FilterChip(
            enabled = !picture,
            selected = boosted,
            onClick = {
                val next: SoundSettings = state.sound.copy(boost = if (boosted) VolumeBoost.OFF else VolumeBoost.AUTO)
                targets.act(id, TargetControl(TargetAction.SOUND, sound = next))
            },
            label = { Text(stringResource(R.string.target_boost)) },
        )
    }
    if (picture) {
        Text(
            stringResource(R.string.target_sound_picture_note, state.target.label),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun RemoteColours(targets: TargetsRepository, id: String) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) { Text(stringResource(R.string.target_colour)) }
    if (open) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TargetColours.palette.forEach { swatch ->
                Box(
                    Modifier.size(SWATCH).clip(CircleShape).background(swatch.asColour())
                        .clickable { targets.recolour(id, swatch) },
                )
            }
        }
    }
}

@Composable
private fun RemoteEntries(targets: TargetsRepository, state: TargetState, colour: androidx.compose.ui.graphics.Color) {
    val id = state.target.id
    LazyColumn(Modifier.fillMaxWidth()) {
        itemsIndexed(state.entries, key = { index, entry -> "${entry.item.itemId}#$index" }) { index, entry ->
            val previous = state.entries.getOrNull(index - 1)
            val group = entry.groupId
            if (group != null && group != previous?.groupId) {
                Text(
                    text = entry.groupTitle ?: group,
                    style = MaterialTheme.typography.titleSmall,
                    color = colour,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 4.dp),
                )
            }
            RemoteRow(targets, id, index, entry, playing = index == state.at, deckPlaying = state.playing)
        }
    }
}

@Composable
private fun RemoteRow(
    targets: TargetsRepository,
    id: String,
    index: Int,
    entry: TargetEntry,
    playing: Boolean,
    deckPlaying: Boolean,
) {
    val playable = entry.toQueueEntry()?.item ?: return
    Column {
        if (playing) {
            Text(
                stringResource(R.string.target_row_playing),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
        MediaItemRow(
            item = playable.item,
            subtitleLines = rowFacts(playable.item, playable.pillar) +
                listOfNotNull(
                    entry.addedBy?.let { stringResource(R.string.target_added_by, it) },
                    stringResource(R.string.target_loops).takeIf { entry.loop },
                ),
            pillar = playable.pillar,
            modifier = Modifier.testTag("$REMOTE_ROW_TAG-$index"),
            onPlay = {
                if (!playing) {
                    targets.act(id, TargetControl(TargetAction.JUMP, index = index, itemId = entry.item.itemId))
                } else if (!deckPlaying) {
                    targets.act(id, TargetControl(TargetAction.PLAY))
                } else {
                    Diag.log("targets", "$id: the playing row ${entry.item.itemId} was tapped; it keeps playing")
                }
            },
            onPlayNext = { targets.playNextOn(id, entry) },
            onAddToQueue = null,
            onPeek = null,
            onRemoveFromQueue = {
                targets.act(id, TargetControl(TargetAction.REMOVE, index = index, itemId = entry.item.itemId))
            },
        )
    }
}

const val REMOTE_ROW_TAG = "remote-queue-row"
private const val VOLUME_MAX = 100f
private const val SEEK_BACK_MS = 10_000L
private const val SEEK_FORWARD_MS = 30_000L
private val SWATCH = 28.dp
