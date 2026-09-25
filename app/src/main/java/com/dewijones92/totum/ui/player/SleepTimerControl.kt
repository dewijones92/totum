package com.dewijones92.totum.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.dewijones92.totum.R
import com.dewijones92.totum.playback.SleepTimerState
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Sleep-timer control on the player: while off, a moon button opens a menu of
 * durations; while running, it shows the time left and tapping cancels it.
 * Drives the one [com.dewijones92.totum.playback.SleepTimer].
 */
@Composable
internal fun SleepTimerControl(
    state: SleepTimerState,
    onStart: (Duration) -> Unit,
    onStopAfterItem: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val armed = state != SleepTimerState.Off
    val value = when (state) {
        SleepTimerState.Off -> stringResource(R.string.control_off)
        is SleepTimerState.Running -> formatTime(state.remaining.inWholeMilliseconds)
        // No countdown to show — it ends when the item does.
        SleepTimerState.AfterCurrentItem -> stringResource(R.string.sleep_after_item_short)
    }
    Box(modifier) {
        ControlTile(
            icon = Icons.Outlined.Bedtime,
            label = stringResource(R.string.sleep_timer),
            value = value,
            active = armed,
            onClick = { if (armed) onCancel() else menuOpen = true },
            modifier = Modifier.fillMaxSize(),
        )
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            shape = MaterialTheme.shapes.medium
        ) {
            // First, because it is the one you actually want falling asleep to a podcast:
            // a fixed 30 minutes either cuts the episode off or runs on into the next.
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sleep_after_item)) },
                onClick = {
                    onStopAfterItem()
                    menuOpen = false
                },
            )
            SLEEP_OPTIONS.forEach { minutes ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.duration_minutes, minutes)) },
                    onClick = {
                        onStart(minutes.minutes)
                        menuOpen = false
                    },
                )
            }
        }
    }
}

private val SLEEP_OPTIONS = listOf(5, 10, 15, 30, 45, 60)
