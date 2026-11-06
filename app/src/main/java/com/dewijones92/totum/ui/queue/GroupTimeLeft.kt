package com.dewijones92.totum.ui.queue

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.groupTimesLeft
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.PlayState
import com.dewijones92.totum.domain.TimeLeft
import com.dewijones92.totum.ui.common.LocalPlayStates
import com.dewijones92.totum.ui.common.minutesLabel
import com.dewijones92.totum.ui.player.speedLabel
import kotlin.time.Duration

@Composable
internal fun rememberGroupTimesLeft(
    container: AppContainer,
    entries: List<QueueEntry>,
    currentIndex: Int,
): State<Map<String, TimeLeft>> {
    val states = LocalPlayStates.current
    val speed by container.chosenSpeed.collectAsStateWithLifecycle()
    val passOver = container.playbackQueue.passOver
    val timesLeft = remember(entries, currentIndex, states, speed) {
        derivedStateOf {
            groupTimesLeft(
                entries,
                from = currentIndex.coerceAtLeast(0),
                chosenSpeed = speed,
                countsAsLeft = { passOver(it) == null },
                stateOf = { states.value[it] ?: PlayState.Unplayed },
            )
        }
    }
    LaunchedEffect(timesLeft.value, speed, currentIndex) {
        Diag.log(
            "queue",
            "dewidebug time left per group from place ${currentIndex + 1} of ${entries.size} at the chosen " +
                "${speedLabel(speed)}: " +
                timesLeft.value.entries.joinToString(" | ") { (id, left) ->
                    "$id=${left.duration} (${left.unknown} of unknown length, speed applied=${left.speed})"
                }.ifEmpty { "none to show" },
        )
    }
    return timesLeft
}

@Composable
internal fun timeLeftWords(left: TimeLeft): String {
    val length = durationWords(left.duration)
    val speed = left.speed?.let(::speedLabel)
    return when {
        left.atLeast && speed != null -> stringResource(R.string.queue_group_time_left_at_least_at, length, speed)
        left.atLeast -> stringResource(R.string.queue_group_time_left_at_least, length)
        speed != null -> stringResource(R.string.queue_group_time_left_at, length, speed)
        else -> stringResource(R.string.queue_group_time_left, length)
    }
}

@Composable
private fun durationWords(duration: Duration): String {
    val minutes = duration.inWholeMinutes
    return if (minutes >= MINUTES_PER_HOUR) {
        stringResource(R.string.duration_hours_minutes, minutes / MINUTES_PER_HOUR, minutes % MINUTES_PER_HOUR)
    } else {
        minutesLabel(minutes)
    }
}

private const val MINUTES_PER_HOUR = 60
