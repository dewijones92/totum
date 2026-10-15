package com.dewijones92.totum.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.playback.PlaybackController
import com.dewijones92.totum.playback.PlaybackState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

data class LivePosition(val positionMs: Long, val bufferedPositionMs: Long)

val LocalLivePosition = compositionLocalOf<State<LivePosition>?> { null }

fun PlaybackState.steady(): PlaybackState = copy(positionMs = 0, bufferedPositionMs = 0)

@Composable
fun PlaybackState.live(): PlaybackState {
    val live = LocalLivePosition.current?.value ?: return this
    return copy(positionMs = live.positionMs, bufferedPositionMs = live.bufferedPositionMs)
}

fun PlaybackState.progressAt(live: LivePosition?): Float? {
    val position = live?.positionMs ?: positionMs
    return durationMs?.let { (position.toFloat() / it).coerceIn(0f, 1f) }
}

@Composable
fun SteadyPlayback(controller: PlaybackController, content: @Composable (PlaybackState?) -> Unit) {
    val steady by controller.rememberField { it?.steady() }
    val live = controller.rememberField { LivePosition(it?.positionMs ?: 0, it?.bufferedPositionMs ?: 0) }
    CompositionLocalProvider(LocalLivePosition provides live) { content(steady) }
}

@Composable
fun <T> PlaybackController.rememberField(field: (PlaybackState?) -> T): State<T> {
    val latest by rememberUpdatedState(field)
    return remember(
        this
    ) { state.map { latest(it) }.distinctUntilChanged() }.collectAsStateWithLifecycle(currently(field))
}

private fun <T> PlaybackController.currently(field: (PlaybackState?) -> T): T = field(state.value)
