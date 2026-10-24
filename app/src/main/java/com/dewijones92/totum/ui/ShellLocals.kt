package com.dewijones92.totum.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.navigation.TopLevelDestination
import com.dewijones92.totum.playback.SkipDirection
import com.dewijones92.totum.ui.cast.LocalScreenControl
import com.dewijones92.totum.ui.cast.rememberCastingTo
import com.dewijones92.totum.ui.common.LocalCastingTo
import com.dewijones92.totum.ui.common.LocalExpandPlayer
import com.dewijones92.totum.ui.common.LocalOpenMusicPage
import com.dewijones92.totum.ui.common.LocalOpenSearch
import com.dewijones92.totum.ui.common.LocalQueueSkip
import com.dewijones92.totum.ui.common.LocalRecentSearches
import com.dewijones92.totum.ui.common.QueueSkip
import com.dewijones92.totum.ui.player.LocalVideoBounds
import com.dewijones92.totum.ui.player.VideoBounds
import kotlinx.coroutines.launch

@Composable
internal fun ProvideShellLocals(
    container: AppContainer,
    videoBounds: VideoBounds,
    openPlayer: () -> Unit,
    tabs: TabNavigation,
    selected: TopLevelDestination,
    closeFullPlayer: () -> Unit,
    content: @Composable () -> Unit,
) {
    SideEffect { tabs.onSearchOpened = { warmTorrentSearch(container) } }
    CompositionLocalProvider(
        LocalVideoBounds provides videoBounds,
        LocalQueueSkip provides rememberQueueSkip(container),
        LocalScreenControl provides container.playbackController,
        // Peeking opens the player, and the shell is what owns "open".
        LocalExpandPlayer provides openPlayer,
        LocalOpenSearch provides rememberSearchOpener(tabs, selected),
        LocalCastingTo provides rememberCastingTo(container.playbackController),
        LocalOpenMusicPage provides remember(tabs) { tabs.pageOpener(closeFullPlayer) },
        LocalRecentSearches provides container.recentSearches,
        content = content,
    )
}

@Composable
private fun rememberQueueSkip(container: AppContainer): QueueSkip {
    return remember(container) {
        QueueSkip(
            previous = { container.queueSkips.skip(SkipDirection.PREVIOUS, "the app") },
            next = { container.queueSkips.skip(SkipDirection.NEXT, "the app") },
        )
    }
}

private fun warmTorrentSearch(container: AppContainer) {
    val server = container.homeTorrentServer ?: return
    container.applicationScope.launch { server.warmSearch() }
}
