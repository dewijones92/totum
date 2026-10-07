package com.dewijones92.totum.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.playback.PlaybackController
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

@Composable
internal fun AnswerOpenPlayerRequests(request: Int, controller: PlaybackController, open: () -> Unit) {
    var answered by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(request) {
        if (request == answered) return@LaunchedEffect
        answered = request
        val loaded = withTimeoutOrNull(OPEN_PLAYER_WAIT_MS) { controller.state.first { it != null } }
        if (loaded == null) {
            Diag.log("nav", "dewidebug open-player request $request dropped: nothing loaded")
            return@LaunchedEffect
        }
        Diag.log("nav", "dewidebug open-player request $request -> full player on ${loaded.itemId}")
        open()
    }
}

private const val OPEN_PLAYER_WAIT_MS = 3_000L
