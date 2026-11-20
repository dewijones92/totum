package com.dewijones92.totum.ui.queue

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.targets.TargetAction
import com.dewijones92.totum.data.targets.TargetColours
import com.dewijones92.totum.data.targets.TargetControl
import com.dewijones92.totum.data.targets.TargetState
import com.dewijones92.totum.data.targets.barFor
import com.dewijones92.totum.data.targets.shownLengthMs
import com.dewijones92.totum.data.targets.toQueueEntry
import com.dewijones92.totum.playback.SeekSteps
import com.dewijones92.totum.targets.TargetsRepository
import com.dewijones92.totum.ui.common.ArtworkWithBadge
import com.dewijones92.totum.ui.common.NowPlayingBarFrame
import com.dewijones92.totum.ui.common.dockColor
import com.dewijones92.totum.ui.common.face

internal data class SelectedRemote(val state: TargetState, val colour: Color)

@Composable
internal fun rememberSelectedRemote(targets: TargetsRepository): SelectedRemote? {
    if (!targets.available) return null
    val listed by targets.targets.collectAsStateWithLifecycle()
    val selected by targets.selected.collectAsStateWithLifecycle()
    val colours by targets.colours.collectAsStateWithLifecycle()
    val state = listed.barFor(selected)
    val why = when {
        selected == null -> "this phone is the chosen device"
        state != null -> "${state.target.label} is chosen and has ${state.entries.size} queued, at=${state.at}"
        listed.none { it.target.id == selected } -> "$selected is chosen but the Pi does not list it"
        else -> "$selected is chosen but has nothing playing or queued to show"
    }
    LaunchedEffect(state?.target?.id, selected) {
        val owner = if (state != null) "the remote for ${state.target.label}" else "the phone's"
        Diag.log("targets", "dock bar is $owner: $why")
    }
    return state?.let { SelectedRemote(it, TargetColours.of(colours, it.target.id).asColour()) }
}

@Composable
internal fun TargetNowPlayingBar(
    targets: TargetsRepository,
    remote: SelectedRemote,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = remote.state
    val colour = remote.colour
    val id = state.target.id
    val item = state.current?.toQueueEntry()?.item?.item
    val face = item?.face()
    val duration = state.shownLengthMs
    Surface(
        shape = RoundedCornerShape(BAR_CORNER),
        color = colour.copy(alpha = BAR_TINT).compositeOver(dockColor()),
        border = BorderStroke(BAR_BORDER, colour),
        modifier = modifier.padding(BAR_MARGIN).testTag(TARGET_BAR_TAG),
    ) {
        NowPlayingBarFrame(
            onClick = onOpen,
            artwork = {
                ArtworkWithBadge(
                    url = face?.thumbnailUrl,
                    playing = state.playing,
                    glyph = state.target.kind.icon(),
                    blurred = face?.blurThumbnail == true,
                )
            },
            text = { text ->
                Column(text.padding(start = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(DOT).background(colour, CircleShape))
                        Text(
                            text = stringResource(
                                R.string.target_bar_line,
                                state.target.chipLabel(state.power),
                                stringResource(
                                    when {
                                        state.starting -> R.string.target_status_starting
                                        state.playing -> R.string.target_status_playing
                                        else -> R.string.target_status_paused
                                    },
                                ),
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(start = 6.dp).testTag(TARGET_BAR_DEVICE_TAG),
                        )
                    }
                    Text(face?.title.orEmpty(), style = MaterialTheme.typography.titleSmall)
                    item?.author?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            controls = {
                IconButton(
                    onClick = { seekBy(targets, state, -SeekSteps.BACK_MS, duration) },
                    enabled = duration != null,
                    modifier = Modifier.size(CONTROL),
                ) { Icon(Icons.Filled.Replay10, stringResource(R.string.target_seek_back)) }
                BarPlayPause(targets, state, colour)
                IconButton(
                    onClick = { seekBy(targets, state, SeekSteps.FORWARD_MS, duration) },
                    enabled = duration != null,
                    modifier = Modifier.size(CONTROL),
                ) { Icon(Icons.Filled.Forward30, stringResource(R.string.target_seek_forward)) }
                IconButton(
                    onClick = { targets.command(id, TargetAction.NEXT) },
                    modifier = Modifier.size(CONTROL),
                ) { Icon(Icons.Filled.SkipNext, stringResource(R.string.target_next)) }
            },
            progress = duration?.let { length -> { (state.positionMs.toFloat() / length).coerceIn(0f, 1f) } },
            progressColor = colour,
        )
    }
}

@Composable
private fun BarPlayPause(targets: TargetsRepository, state: TargetState, colour: Color) {
    val starting = stringResource(R.string.target_bar_starting)
    if (state.starting) {
        CircularProgressIndicator(
            strokeWidth = 2.dp,
            modifier = Modifier.padding(horizontal = 16.dp).size(20.dp).semantics { contentDescription = starting },
        )
        return
    }
    FilledIconButton(
        onClick = {
            targets.command(state.target.id, if (state.playing) TargetAction.PAUSE else TargetAction.PLAY)
        },
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = colour, contentColor = colour.readableOn()),
        modifier = Modifier.testTag(TARGET_BAR_PLAY_TAG),
    ) {
        Icon(
            if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            stringResource(if (state.playing) R.string.target_pause else R.string.target_play),
        )
    }
}

private fun seekBy(targets: TargetsRepository, state: TargetState, deltaMs: Long, duration: Long?) {
    val top = duration ?: Long.MAX_VALUE
    val to = (state.positionMs + deltaMs).coerceIn(0, top)
    targets.act(state.target.id, TargetControl(TargetAction.SEEK, positionMs = to))
}

const val TARGET_BAR_TAG = "target-bar"
const val TARGET_BAR_PLAY_TAG = "target-bar-play"
const val TARGET_BAR_DEVICE_TAG = "target-bar-device"
private val BAR_CORNER = 22.dp
private val BAR_BORDER = 2.dp
private val BAR_MARGIN = 6.dp
private const val BAR_TINT = 0.2f
private val CONTROL = 48.dp
private val DOT = 10.dp
