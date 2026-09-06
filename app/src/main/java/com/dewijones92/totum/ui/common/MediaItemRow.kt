package com.dewijones92.totum.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.domain.PlayState

// A 16:9 leading thumbnail — the shape video stills want; square podcast art
// centre-crops into it cleanly.
private const val TITLE_MAX_LINES = 2

/**
 * 16:9, and bigger than it was.
 *
 * 96x54 was a correct aspect ratio at a size that made every thumbnail a stamp — the artwork is the
 * fastest thing to recognise in a list and it was the smallest thing in the row. 120x68 is close to
 * what YouTube and Pocket Casts use, and the ratio is kept exactly so nothing is cropped.
 */
private val THUMBNAIL_WIDTH = 120.dp
private val THUMBNAIL_HEIGHT = 68.dp

/**
 * One media item in a list — used identically for podcast episodes and any
 * other [MediaItem]. Tapping the row plays it; the leading [MediaThumbnail]
 * shows its artwork; the trailing control reflects and drives its offline
 * [DownloadState]. Long-press (or the ⋮) opens a bottom sheet of its actions.
 *
 * Every row states what it is: its [pillar], whether it's held offline, and its
 * [playState]. [pillar] is required rather than inferred — mixed lists know it from
 * the item's `PlayHandle` and single-pillar screens know it outright, so guessing from
 * a URL would be both lossy and unnecessary.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaItemRow(
    item: MediaItem,
    /**
     * The facts under the title, **one line each** — see [mediaItemFacts].
     *
     * A list rather than a string because a single line capped at `maxLines = 1` is what made the
     * view count and the date disappear behind an ellipsis on a real phone (Dewi, 2026-08-15).
     * Callers with something extra to say (a file size, an offline warning) append it as another
     * line rather than splicing it into a sentence that then truncates.
     */
    subtitleLines: List<String>,
    pillar: MediaKind,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Default to the app-wide capability like every other action — these two were the ONLY callbacks
     * without a default, so three screens passed `{}` and drew a download control that did nothing
     * (Related, Notifications, Search). Full media, as a screen's own Download tap fetches; the
     * action sheet's "Download video" covers upgrading an audio-only copy.
     */
    onDownload: (() -> Unit)? = LocalItemActions.current.bind { download(item, audioOnly = false) },
    onDeleteDownload: (() -> Unit)? = LocalItemActions.current.bind { deleteDownload(item.id) },
    /** Defaults to the app-wide offline state, so no screen has to plumb it. */
    downloadState: DownloadState = LocalDownloadStates.current[item.id] ?: DownloadState.NotDownloaded,
    // Everything an item can do defaults to the app-wide capability. A screen has to work
    // to REMOVE an action, never to remember one. Only genuinely contextual actions
    // (remove-from-playlist, move-within-queue) stay null, because they only exist somewhere.
    onPlayNext: (() -> Unit)? = LocalItemActions.current.bind { playNext(item) },
    onAddToQueue: (() -> Unit)? = LocalItemActions.current.bind { addToQueue(item) },
    onAddToPlaylist: (() -> Unit)? = LocalItemActions.current.bind { addToPlaylist(item) },
    onRemoveFromPlaylist: (() -> Unit)? = null,
    onPeek: (() -> Unit)? = LocalItemActions.current.bind { peek(item) },
    /**
     * Offered when the row's local copy is audio only (what the queue fetches
     * automatically): the tick already means "offline", so without this there'd be no
     * way left to ask for the picture too.
     */
    onDownloadVideo: (() -> Unit)? = null,
    /** Switches between listening and watching (and sets the mode); videos only. */
    onSwitchMode: (() -> Unit)? =
        LocalItemActions.current.takeIf { pillar == MediaKind.VIDEO }.bind { switchMode(item) },
    /** True when the mode is audio, so the action reads "Watch with video" instead. */
    audioMode: Boolean = LocalItemActions.current?.audioMode == true,
    /** Defaults to the app-wide play state, so no screen has to plumb it. */
    playState: PlayState = LocalPlayStates.current[item.id] ?: PlayState.Unplayed,
    /** Marks the item played or unplayed by hand — AntennaPod's most-used action. */
    onSetPlayed: ((Boolean) -> Unit)? = LocalSetPlayed.current?.let { set -> { played -> set(item.id, played) } },
    /** Queue-only: jump this entry to the front / back of the up-next order. */
    onMoveToTop: (() -> Unit)? = null,
    onMoveToBottom: (() -> Unit)? = null,
    onGoToSource: (() -> Unit)? = LocalItemActions.current.bind { goToSource(item) },
    /** Label for [onGoToSource] — the host knows its pillar ("channel" vs "podcast"). */
    /**
     * Replaces the download control for rows whose trailing affordances are about
     * something else — the queue's reorder/remove buttons, for instance.
     */
    trailing: (@Composable () -> Unit)? = null,
) {
    var showSheet by remember { mutableStateOf(false) }
    // "Download the video too" only makes sense once the local copy is audio-only.
    val downloadVideo = onDownloadVideo?.takeIf {
        (downloadState as? DownloadState.Downloaded)?.audioOnly == true
    }
    // Rows that replace the download control with something else (the queue's drag handle)
    // would otherwise have no way to (re)try a download at all — which matters precisely
    // when an automatic fetch failed.
    val sheetDownload = onDownload?.takeIf {
        trailing != null && downloadState !is DownloadState.Downloaded && downloadState !is DownloadState.Downloading
    }
    val hasMenu = listOfNotNull(
        onPlayNext, onAddToQueue, onAddToPlaylist, onRemoveFromPlaylist, onPeek,
        downloadVideo, sheetDownload, onGoToSource, onSetPlayed, onMoveToTop, onMoveToBottom,
    ).isNotEmpty()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                enabled = item.mediaUrl != null || hasMenu,
                onClick = { if (item.mediaUrl != null) onPlay() },
                onLongClick = if (hasMenu) ({ showSheet = true }) else null,
            )
            // Tighter vertically than horizontally: 16dp all round made every row a third taller
            // than its artwork needed, so a screenful held five items where it now holds seven.
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        ThumbnailWithProgress(item, playState)
        Spacer(Modifier.width(14.dp))
        TitleAndSubtitle(item, subtitleLines, pillar, playState, downloadState, Modifier.weight(1f))
        if (hasMenu) {
            IconButton(onClick = { showSheet = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.queue_menu))
            }
        }
        TrailingControl(trailing, downloadState, onDownload, onDeleteDownload)
    }
    if (showSheet) {
        ActionSheet(
            title = item.title,
            onPlayNext = onPlayNext,
            onAddToQueue = onAddToQueue,
            onAddToPlaylist = onAddToPlaylist,
            onRemoveFromPlaylist = onRemoveFromPlaylist,
            onPeek = onPeek,
            onDownloadVideo = downloadVideo,
            onDownload = sheetDownload,
            onSwitchMode = onSwitchMode,
            audioMode = audioMode,
            onGoToSource = onGoToSource,
            sourcePillar = pillar,
            onMoveToTop = onMoveToTop,
            onMoveToBottom = onMoveToBottom,
            onSetPlayed = onSetPlayed,
            played = playState.isPlayed,
            onDismiss = { showSheet = false },
        )
    }
}

/** The artwork with a progress sliver beneath it, so "you are here" needs no words. */
@Composable
private fun ThumbnailWithProgress(item: MediaItem, playState: PlayState) {
    Column {
        MediaThumbnail(
            url = item.thumbnailUrl,
            contentDescription = item.title,
            modifier = Modifier.size(width = THUMBNAIL_WIDTH, height = THUMBNAIL_HEIGHT),
            durationLabel = durationLabel(item),
        )
        PlayProgressSliver(playState, Modifier.width(THUMBNAIL_WIDTH))
    }
}

@Composable
private fun TitleAndSubtitle(
    item: MediaItem,
    subtitleLines: List<String>,
    pillar: MediaKind,
    playState: PlayState,
    downloadState: DownloadState,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        ItemBadges(item)
        Text(
            text = item.title,
            // titleSmall over bodyLarge: the title is the thing the eye lands on and it was set at
            // the same weight as the subtitle under it, so a row had no hierarchy at all.
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            // Two lines keeps a list scannable; long podcast titles were running to
            // five, which made every row a paragraph.
            maxLines = TITLE_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.alpha(playedTitleAlpha(playState)),
        )
        // One Text per fact. Each still caps at one line, but a line now holds ONE fact, so an
        // ellipsis can only ever shorten a long channel name — it can no longer swallow the view
        // count or the date, which is what it was doing when all three shared a line.
        subtitleLines.forEach { fact ->
            Text(
                text = fact,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        MediaItemStatus(pillar, playState, downloadState, StatusRowSpacing)
    }
}

/**
 * What a row needs to say about itself before you read the title: LIVE / SHORT, and
 * whether it is members-only. These are pills rather than subtitle text on purpose —
 * the subtitle truncates, and "you cannot actually play this" must not be the part that
 * gets cut.
 */
@Composable
private fun ItemBadges(item: MediaItem) {
    val kindLabel = when (item.contentKind) {
        MediaContentKind.LIVE -> stringResource(R.string.tag_live) to MaterialTheme.colorScheme.error
        MediaContentKind.SHORT -> stringResource(R.string.tag_short) to MaterialTheme.colorScheme.tertiary
        MediaContentKind.STANDARD -> null
    }
    val members = if (item.membersOnly) {
        stringResource(R.string.tag_members_only) to MaterialTheme.colorScheme.secondary
    } else {
        null
    }
    val badges = listOfNotNull(kindLabel, members)
    if (badges.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        badges.forEach { (label, color) -> Badge(label, color) }
    }
    Spacer(Modifier.height(2.dp))
}

@Composable
private fun Badge(label: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** The row's own control, or nothing when there is nothing behind it (a preview, a test). */
@Composable
private fun TrailingControl(
    trailing: (@Composable () -> Unit)?,
    downloadState: DownloadState,
    onDownload: (() -> Unit)?,
    onDeleteDownload: (() -> Unit)?,
) {
    when {
        trailing != null -> trailing()
        onDownload != null && onDeleteDownload != null -> DownloadControl(downloadState, onDownload, onDeleteDownload)
    }
}

@Composable
private fun DownloadControl(
    state: DownloadState,
    onDownload: () -> Unit,
    onDeleteDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        DownloadState.NotDownloaded, is DownloadState.Failed ->
            IconButton(onClick = onDownload, modifier = modifier) {
                Icon(Icons.Outlined.Download, contentDescription = stringResource(R.string.download))
            }
        is DownloadState.Downloading ->
            CircularProgressIndicator(
                progress = { state.fraction ?: 0f },
                modifier = modifier
                    .padding(12.dp)
                    .size(20.dp),
            )
        is DownloadState.Downloaded ->
            IconButton(onClick = onDeleteDownload, modifier = modifier) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = stringResource(R.string.downloaded_delete),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
    }
}
