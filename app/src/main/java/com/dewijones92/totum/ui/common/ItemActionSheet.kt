package com.dewijones92.totum.ui.common

import androidx.compose.runtime.Composable
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.domain.pillar

/**
 * The long-press menu for an item, built entirely from the app-wide capabilities —
 * [LocalItemActions], [LocalPlayStates] and [LocalDownloadStates].
 *
 * Anywhere that isn't a list can show the same menu by calling this: the full player and
 * the shorts reel both do. Without it each such surface assembled [ActionSheet]'s
 * seventeen arguments itself, which is how surfaces ended up offering different subsets
 * of the same actions — the shorts reel offered none at all.
 *
 * Renders nothing where no actions are provided (previews, tests).
 */
@Composable
internal fun ItemActionSheet(
    item: MediaItem,
    onDismiss: () -> Unit,
    pillar: MediaKind = item.pillar,
) {
    val actions = LocalItemActions.current ?: return
    val local = LocalDownloadStates.current[item.id]
    val video = pillar == MediaKind.VIDEO
    ActionSheet(
        title = item.title,
        onPlayNext = { actions.playNext(item) },
        onAddToQueue = { actions.addToQueue(item) },
        onAddToPlaylist = { actions.addToPlaylist(item) },
        // All three belong to a list that has an index or a membership; nothing here has one.
        onRemoveFromPlaylist = null,
        onRemoveFromQueue = null,
        onPeek = { actions.peek(item) },
        onDownloadVideo = { actions.download(item, audioOnly = false) }
            .takeIf { video && (local as? DownloadState.Downloaded)?.audioOnly == true },
        onDownload = { actions.download(item, audioOnly = true) }
            .takeIf { local !is DownloadState.Downloaded && local !is DownloadState.Downloading },
        onDeleteDownload = { actions.deleteDownload(item.id) }.takeIf { local is DownloadState.Downloaded },
        onSwitchMode = { actions.switchMode(item) }.takeIf { video },
        audioMode = actions.audioMode,
        onGoToSource = { actions.goToSource(item) }.takeIf { actions.canGoToSource(item) },
        sourcePillar = pillar,
        onMoveToTop = null,
        onMoveToBottom = null,
        onSetPlayed = { played -> actions.setPlayed(item.id, played) },
        played = LocalPlayStates.current[item.id]?.isPlayed == true,
        onDismiss = onDismiss,
    )
}
