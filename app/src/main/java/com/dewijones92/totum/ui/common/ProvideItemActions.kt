package com.dewijones92.totum.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.pillar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Builds the app-wide [ItemActions] and provides it to every row beneath.
 *
 * [onOpenSource] is the one genuinely screen-shaped piece — where "go to channel/podcast" lands —
 * so the shell supplies it once instead of each screen hosting its own channel overlay.
 */
@Composable
internal fun ProvideItemActions(
    container: AppContainer,
    onOpenSource: (MediaSource) -> Unit,
    content: @Composable () -> Unit,
) {
    val rowActions = rememberMediaItemActions(container)
    val scope = rememberCoroutineScope()
    val actions = remember(container, rowActions, scope, onOpenSource) {
        ContainerItemActions(container, rowActions, scope, onOpenSource)
    }
    val openSource: (MediaSource) -> Unit = remember(onOpenSource) {
        {
                source ->
            Diag.log("nav", "open source \"${source.title}\" [pillar=${source.pillar} id=${source.id.value}]")
            onOpenSource(source)
        }
    }
    CompositionLocalProvider(
        LocalItemActions provides actions,
        LocalOpenSource provides openSource,
        content = content,
    )
}

private class ContainerItemActions(
    private val container: AppContainer,
    private val rows: MediaItemActions,
    private val scope: CoroutineScope,
    private val onOpenSource: (MediaSource) -> Unit,
) : ItemActions {
    override fun queue(items: List<MediaItem>, next: Boolean) = rows.queueAll(items, next)
    override fun addToPlaylist(items: List<MediaItem>) = rows.addToPlaylist(items)
    override fun peek(item: MediaItem) = rows.peek(item)

    override fun download(item: MediaItem, audioOnly: Boolean) {
        scope.launch { container.downloadManager.download(item, audioOnly) }
    }

    override fun deleteDownload(id: MediaItemId) {
        scope.launch { container.downloadManager.delete(id) }
    }

    override fun setPlayed(id: MediaItemId, played: Boolean) {
        scope.launch { container.playbackProgressStore.setPlayed(id, played) }
    }

    override fun goToSource(item: MediaItem) {
        rows.goToSource(item, onOpenSource)
    }

    override fun canGoToSource(item: MediaItem): Boolean = container.sourceLocator.canLocate(item)

    override val audioMode: Boolean get() = rows.audioMode

    override fun switchMode(item: MediaItem) {
        // Labels come from the row; the mode change and its announcement live in MediaItemActions.
        rows.switchMode(item, toAudio = !rows.audioMode, audioOnMessage = "", videoOnMessage = "")
    }
}
