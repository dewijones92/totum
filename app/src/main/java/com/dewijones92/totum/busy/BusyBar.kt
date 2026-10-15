package com.dewijones92.totum.busy

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.dewijones92.totum.common.Busy
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * One thin indeterminate bar across the top of the app whenever something is loading.
 *
 * Dewi asked for a spinner "when ANYTHING is loading". The whole value is in distinguishing
 * *working* from *idle*, which is why the timing below matters as much as the bar itself:
 *
 * - **It waits [APPEAR_AFTER_MS] before appearing.** Most calls finish in tens of
 *   milliseconds, and a bar that flashed on every one would be noise the eye learns to
 *   ignore — the opposite of the goal.
 * - **Once shown it stays for [MIN_VISIBLE_MS].** Appearing and vanishing within a couple of
 *   frames reads as a glitch rather than as feedback.
 *
 * Drawn as an overlay rather than as a row, so nothing on screen moves when it appears.
 */
@Composable
fun BusyBar(still: Boolean, modifier: Modifier = Modifier) {
    val busy by produceState(initialValue = false) {
        Busy.work.map { it.isNotEmpty() }.distinctUntilChanged().collect { working ->
            if (working) {
                delay(APPEAR_AFTER_MS)
            } else if (value) {
                delay(MIN_VISIBLE_MS)
            }
            // Read again after waiting rather than trusting `working`: the wait exists
            // precisely so that work finishing inside it never shows the bar at all.
            value = Busy.work.value.isNotEmpty()
        }
    }

    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(visible = busy, enter = fadeIn(), exit = fadeOut()) {
            if (still) {
                LinearProgressIndicator(progress = { 1f }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

private const val APPEAR_AFTER_MS = 250L
private const val MIN_VISIBLE_MS = 400L
