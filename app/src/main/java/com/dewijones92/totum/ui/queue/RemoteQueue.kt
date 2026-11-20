package com.dewijones92.totum.ui.queue

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.backup.titleOf
import com.dewijones92.totum.data.targets.KeepAwake
import com.dewijones92.totum.data.targets.TargetAction
import com.dewijones92.totum.data.targets.TargetColours
import com.dewijones92.totum.data.targets.TargetControl
import com.dewijones92.totum.data.targets.TargetEntry
import com.dewijones92.totum.data.targets.TargetPower
import com.dewijones92.totum.data.targets.TargetState
import com.dewijones92.totum.data.targets.VolumeMode
import com.dewijones92.totum.data.targets.connection
import com.dewijones92.totum.data.targets.isLive
import com.dewijones92.totum.data.targets.shownLengthMs
import com.dewijones92.totum.data.targets.toQueueEntry
import com.dewijones92.totum.playback.SeekSteps
import com.dewijones92.totum.targets.TargetsRepository
import com.dewijones92.totum.ui.common.EmptyState
import com.dewijones92.totum.ui.common.LocalItemShield
import com.dewijones92.totum.ui.common.MediaItemRow
import com.dewijones92.totum.ui.common.TagBadge
import com.dewijones92.totum.ui.common.formatClock
import com.dewijones92.totum.ui.common.liveTagColor
import com.dewijones92.totum.ui.common.liveTagLabel
import com.dewijones92.totum.ui.common.rowFacts
import com.dewijones92.totum.video.LiveTag
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun RemoteQueue(targets: TargetsRepository, id: String, modifier: Modifier = Modifier) {
    val states by targets.targets.collectAsStateWithLifecycle()
    val colours by targets.colours.collectAsStateWithLifecycle()
    val state = states.firstOrNull { it.target.id == id }
    val colourValue = TargetColours.of(colours, id)
    val colour = colourValue.asColour()
    if (state == null) {
        EmptyState(
            icon = Icons.AutoMirrored.Filled.QueueMusic,
            headline = stringResource(R.string.target_waiting_headline, targets.targetOf(id)?.label ?: id),
            supportingText = stringResource(R.string.target_waiting_hint),
            modifier = modifier,
        )
        return
    }
    var controlsOpen by rememberSaveable(id) { mutableStateOf(false) }
    LazyColumn(modifier = modifier.fillMaxWidth().testTag(REMOTE_LIST_TAG)) {
        item(key = "now-playing") { RemoteNowPlaying(targets, state, colour) }
        item(key = "controls") {
            RemoteControls(targets, state, colourValue, open = controlsOpen) {
                Diag.log("targets", "${state.target.id}: the remote's controls are now ${if (it) "open" else "closed"}")
                controlsOpen = it
            }
        }
        if (state.entries.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    icon = Icons.AutoMirrored.Filled.QueueMusic,
                    headline = stringResource(R.string.target_empty_headline, state.target.label),
                    supportingText = stringResource(R.string.target_empty_hint),
                    modifier = Modifier.heightIn(min = EMPTY_MIN_HEIGHT),
                )
            }
        } else {
            item(key = "queue-heading") {
                Text(
                    stringResource(R.string.target_queue_heading, state.target.label, state.entries.size),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 4.dp)
                        .testTag(REMOTE_QUEUE_HEADING_TAG),
                )
            }
            itemsIndexed(state.entries, key = { index, entry -> "${entry.item.itemId}#$index" }) { index, entry ->
                val previous = state.entries.getOrNull(index - 1)
                val group = entry.groupId
                if (group != null && group != previous?.groupId) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 4.dp),
                    ) {
                        Box(Modifier.size(GROUP_DOT).background(colour, CircleShape))
                        Text(
                            text = entry.groupTitle ?: group,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                val playing = index == state.at
                RemoteRow(targets, state.target.id, index, entry, playing = playing, deckPlaying = state.playing)
            }
        }
    }
}

@Composable
private fun RemoteNowPlaying(targets: TargetsRepository, state: TargetState, colour: Color) {
    val id = state.target.id
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag(REMOTE_CARD_TAG)) {
        RemoteHeading(targets, state, colour)
        state.current?.let { current ->
            Text(LocalItemShield.current.titleOf(current.item), style = MaterialTheme.typography.bodyLarge)
            if (state.isLive) {
                TagBadge(liveTagLabel(LiveTag.Live), liveTagColor(LiveTag.Live), Modifier.padding(vertical = 4.dp))
            }
            state.shownLengthMs?.let { duration -> RemoteSeek(targets, state, duration, colour) }
        }
        RemotePower(targets, state)
        RemoteKeepAwake(state)
        RemoteTrouble(targets, state)
        RemoteTransport(targets, state, colour)
        RemoteVolume(targets, state, colour)
    }
    LaunchedEffect(id, state.connection, state.playing, state.current == null) {
        Diag.log(
            "targets",
            "$id: remote card shows ${state.connection} playing=${state.playing} at=${state.at} " +
                "of ${state.entries.size} power=${state.power} linked=${state.linked} keepAwake=${state.keepAwake}",
        )
    }
}

@Composable
private fun RemoteHeading(targets: TargetsRepository, state: TargetState, colour: Color) {
    val words = when {
        state.current == null -> stringResource(R.string.target_idle, state.target.label)
        state.playing -> stringResource(R.string.target_now_playing, state.target.label)
        else -> stringResource(R.string.target_paused, state.target.label)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(HEADING_DOT).background(colour, CircleShape))
        Text(
            text = stringResource(R.string.target_heading_with_link, words, state.connection.words()),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 8.dp).weight(1f).testTag(REMOTE_HEADING_TAG),
        )
        IconButton(onClick = { targets.command(state.target.id, TargetAction.STOP) }) {
            Icon(Icons.Filled.Stop, stringResource(R.string.target_stop))
        }
    }
}

@Composable
private fun RemoteSeek(targets: TargetsRepository, state: TargetState, duration: Long, colour: Color) {
    val id = state.target.id
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = dragging ?: state.positionMs.toFloat().coerceIn(0f, duration.toFloat())
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(formatClock(shown.toLong().milliseconds), style = MaterialTheme.typography.bodySmall)
        Slider(
            value = shown,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { targets.act(id, TargetControl(TargetAction.SEEK, positionMs = it.toLong())) }
                dragging = null
            },
            valueRange = 0f..duration.toFloat(),
            colors = remoteSliderColours(colour),
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
        )
        Text(formatClock(duration.milliseconds), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun remoteSliderColours(colour: Color) = SliderDefaults.colors(
    thumbColor = colour,
    activeTrackColor = colour,
    inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
)

@Composable
private fun RemoteTransport(targets: TargetsRepository, state: TargetState, colour: Color) {
    val id = state.target.id
    val duration = state.shownLengthMs
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
        modifier = Modifier.fillMaxWidth(),
    ) {
        IconButton(onClick = { targets.command(id, TargetAction.PREVIOUS) }) {
            Icon(Icons.Filled.SkipPrevious, stringResource(R.string.target_previous))
        }
        IconButton(onClick = { seekBy(targets, state, -SeekSteps.BACK_MS, duration) }, enabled = duration != null) {
            Icon(Icons.Filled.Replay10, stringResource(R.string.target_seek_back))
        }
        if (state.starting) {
            val starting = stringResource(R.string.target_bar_starting)
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier.size(PLAY_SIZE).padding(PLAY_SPINNER_PAD)
                    .semantics { contentDescription = starting },
            )
        } else {
            FilledIconButton(
                onClick = { targets.command(id, if (state.playing) TargetAction.PAUSE else TargetAction.PLAY) },
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = colour,
                    contentColor = colour.readableOn(),
                ),
                modifier = Modifier.size(PLAY_SIZE).testTag(REMOTE_PLAY_TAG),
            ) {
                Icon(
                    if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    stringResource(if (state.playing) R.string.target_pause else R.string.target_play),
                    modifier = Modifier.size(PLAY_ICON),
                )
            }
        }
        IconButton(onClick = { seekBy(targets, state, SeekSteps.FORWARD_MS, duration) }, enabled = duration != null) {
            Icon(Icons.Filled.Forward30, stringResource(R.string.target_seek_forward))
        }
        IconButton(onClick = { targets.command(id, TargetAction.NEXT) }) {
            Icon(Icons.Filled.SkipNext, stringResource(R.string.target_next))
        }
    }
}

@Composable
private fun RemoteVolume(targets: TargetsRepository, state: TargetState, colour: Color) {
    val id = state.target.id
    var volume by remember(state.volume) { mutableStateOf(state.volume?.toFloat()) }
    var why by remember { mutableStateOf(false) }
    val label = when (state.volumeMode) {
        VolumeMode.HARDWARE -> R.string.target_volume_label_speaker
        VolumeMode.SOFTWARE -> R.string.target_volume_label_software
        null -> R.string.target_volume_label_unknown
    }
    val reason = when (state.volumeMode) {
        VolumeMode.HARDWARE -> R.string.target_volume_why_speaker
        VolumeMode.SOFTWARE -> R.string.target_volume_why_software
        null -> R.string.target_volume_why_unknown
    }
    val sliderName = stringResource(R.string.target_volume_slider)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.AutoMirrored.Outlined.VolumeUp,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = volume ?: 0f,
            onValueChange = { volume = it },
            onValueChangeFinished = {
                volume?.let { targets.act(id, TargetControl(TargetAction.VOLUME, volume = it.toInt())) }
            },
            valueRange = 0f..VOLUME_MAX,
            colors = remoteSliderColours(colour),
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp).semantics { contentDescription = sliderName },
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.semantics(mergeDescendants = true) {}.testTag(REMOTE_VOLUME_TAG),
        ) {
            Text(
                volume?.let { stringResource(R.string.target_volume_percent, it.toInt()) } ?: "—",
                style = MaterialTheme.typography.labelLarge,
            )
            Text(stringResource(label), style = MaterialTheme.typography.labelSmall)
        }
        IconButton(onClick = { why = true }) {
            Icon(Icons.Outlined.Info, stringResource(R.string.target_volume_why))
        }
    }
    if (why) {
        AlertDialog(
            onDismissRequest = { why = false },
            text = { Text(stringResource(reason)) },
            confirmButton = {
                TextButton(onClick = { why = false }) { Text(stringResource(R.string.target_volume_why_ok)) }
            },
        )
    }
}

private fun seekBy(targets: TargetsRepository, state: TargetState, deltaMs: Long, duration: Long?) {
    val top = duration ?: return
    val to = (state.positionMs + deltaMs).coerceIn(0, top)
    targets.act(state.target.id, TargetControl(TargetAction.SEEK, positionMs = to))
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
        TargetPower.ASLEEP -> if (state.switchedOnByHand) {
            Text(stringResource(R.string.target_asleep_by_hand, state.target.label))
        } else {
            TextButton(onClick = { targets.command(state.target.id, TargetAction.WAKE) }) {
                Text(stringResource(R.string.target_asleep_wake))
            }
        }
        TargetPower.WAKING -> Text(state.powerNote ?: stringResource(R.string.target_waking, state.target.label))
    }
    if (state.canSwitchOff && state.power != TargetPower.ASLEEP) {
        TextButton(onClick = { targets.command(state.target.id, TargetAction.SWITCH_OFF) }) {
            Text(stringResource(R.string.target_switch_off))
        }
    }
}

@Composable
private fun RemoteKeepAwake(state: TargetState) {
    val line = when (state.keepAwake) {
        KeepAwake.KEPT -> R.string.target_keep_awake_kept
        KeepAwake.RECONNECTING -> R.string.target_keep_awake_reconnecting
        KeepAwake.OUTSIDE_WINDOW -> R.string.target_keep_awake_outside
        null -> null
    }
    if (line != null) Text(stringResource(line, state.target.label), style = MaterialTheme.typography.bodySmall)
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
const val REMOTE_LIST_TAG = "remote-queue-list"
const val REMOTE_CARD_TAG = "remote-card"
const val REMOTE_HEADING_TAG = "remote-heading"
const val REMOTE_PLAY_TAG = "remote-play"
const val REMOTE_VOLUME_TAG = "remote-volume"
const val REMOTE_CONTROLS_TAG = "remote-controls"
const val REMOTE_QUEUE_HEADING_TAG = "remote-queue-heading"
private const val VOLUME_MAX = 100f
private val PLAY_SIZE = 56.dp
private val PLAY_ICON = 30.dp
private val PLAY_SPINNER_PAD = 14.dp
private val HEADING_DOT = 12.dp
private val GROUP_DOT = 10.dp
private val EMPTY_MIN_HEIGHT = 240.dp
