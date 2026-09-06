package com.dewijones92.totum.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaSource
import kotlinx.coroutines.launch

/**
 * Makes play state available to every row in the app, from one place — installed around
 * the whole shell in `MainActivity`. This is what lets [MediaItemRow] default to it: no
 * screen, and no view model, has to know that lists show played/part-way status.
 */
@Composable
internal fun ProvidePlayStates(
    container: AppContainer,
    onOpenChannel: (MediaSource.VideoChannel) -> Unit,
    content: @Composable () -> Unit,
) {
    val store = container.playbackProgressStore
    // The account's watched positions count too, merged by the same rule resume uses — so a video
    // half-watched on the website shows half-watched in every list here (report 0.1.477, 22 Aug).
    val states by remember(container) { container.rowPlayStates }.collectAsStateWithLifecycle(emptyMap())
    val downloads by remember(container) { container.downloadManager.observeDownloads() }
        .collectAsStateWithLifecycle(emptyMap())
    val scope = rememberCoroutineScope()
    val setPlayed = remember(store, scope) {
        {
                id: MediaItemId, played: Boolean ->
            scope.launch { store.setPlayed(id, played) }
            Unit
        }
    }
    // Row capabilities are provided together, in one place: what a row SHOWS (play and
    // offline state) and everything it can DO. All of it exists so no screen has to
    // remember to wire them — the screens that forgot are why these are defaults.
    CompositionLocalProvider(
        LocalPlayStates provides states,
        LocalDownloadStates provides downloads,
        LocalSetPlayed provides setPlayed,
    ) {
        ProvideItemActions(container, onOpenChannel, content)
    }
}
