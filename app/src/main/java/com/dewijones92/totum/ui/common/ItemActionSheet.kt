package com.dewijones92.totum.ui.common

import androidx.compose.runtime.Composable
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaKind

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
    sourcePillar: MediaKind = MediaKind.VIDEO,
) {
    val actions = LocalItemActions.current ?: return
    val local = LocalDownloadStates.current[item.id]
    ActionSheet(
        title = item.title,
        onPlayNext = { actions.playNext(item) },
        onAddToQueue = { actions.addToQueue(item) },
        onAddToPlaylist = { actions.addToPlaylist(item) },
        // Both belong to a list that has an index or a membership; nothing here has one.
        onRemoveFromPlaylist = null,
        onPeek = { actions.peek(item) },
        onDownloadVideo = { actions.download(item, audioOnly = false) }
            .takeIf { (local as? DownloadState.Downloaded)?.audioOnly == true },
        onDownload = { actions.download(item, audioOnly = true) }
            .takeIf { local !is DownloadState.Downloaded && local !is DownloadState.Downloading },
        onSwitchMode = { actions.switchMode(item) },
        audioMode = actions.audioMode,
        onGoToSource = { actions.goToSource(item) },
        sourcePillar = sourcePillar,
        onMoveToTop = null,
        onMoveToBottom = null,
        onSetPlayed = { played -> actions.setPlayed(item.id, played) },
        played = LocalPlayStates.current[item.id]?.isPlayed == true,
        onDismiss = onDismiss,
    )
}
