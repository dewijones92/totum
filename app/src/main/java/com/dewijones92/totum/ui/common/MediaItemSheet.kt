package com.dewijones92.totum.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.domain.MediaKind

/**
 * The actions for one media item, in a Material 3 bottom sheet.
 *
 * Lives here rather than inside `MediaItemRow` because the **full player** shows the same
 * sheet for whatever is playing. Dewi's requirement is that the player offer everything a
 * long-press does and never less — sharing the one composable is what makes that true by
 * construction, instead of two lists that drift.
 */
/**
 * Long-press / overflow action sheet — a Material 3 bottom sheet of the actions
 * available for the row (what apps like YouTube use). Only non-null actions show.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ActionSheet(
    title: String,
    onPlayNext: (() -> Unit)?,
    onAddToQueue: (() -> Unit)?,
    onAddToPlaylist: (() -> Unit)?,
    onRemoveFromPlaylist: (() -> Unit)?,
    onPeek: (() -> Unit)?,
    onDownloadVideo: (() -> Unit)?,
    onDownload: (() -> Unit)?,
    onSwitchMode: (() -> Unit)?,
    audioMode: Boolean,
    onGoToSource: (() -> Unit)?,
    /** Which pillar the item's maker belongs to — decides both the label and the glyph of "go to". */
    sourcePillar: MediaKind,
    onMoveToTop: (() -> Unit)?,
    onMoveToBottom: (() -> Unit)?,
    onSetPlayed: ((Boolean) -> Unit)?,
    played: Boolean,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        SheetAction(onPlayNext, Icons.AutoMirrored.Filled.PlaylistPlay, R.string.queue_play_next, onDismiss)
        SheetAction(onAddToQueue, Icons.AutoMirrored.Filled.QueueMusic, R.string.queue_add, onDismiss)
        SheetAction(onAddToPlaylist, Icons.AutoMirrored.Filled.PlaylistAdd, R.string.playlist_add_to, onDismiss)
        SheetAction(onRemoveFromPlaylist, Icons.Filled.Delete, R.string.playlist_remove_from, onDismiss)
        SheetAction(onPeek, Icons.Outlined.Visibility, R.string.queue_peek, onDismiss)
        SheetAction(onDownload, Icons.Outlined.Download, R.string.download, onDismiss)
        SheetAction(onDownloadVideo, Icons.Outlined.Download, R.string.download_video, onDismiss)
        SheetAction(
            onSwitchMode,
            if (audioMode) Icons.Outlined.SmartDisplay else Icons.Outlined.Headphones,
            if (audioMode) R.string.play_with_video else R.string.play_audio_only,
            onDismiss,
        )
        SheetAction(onMoveToTop, Icons.Filled.VerticalAlignTop, R.string.queue_move_to_top, onDismiss)
        SheetAction(onMoveToBottom, Icons.Filled.VerticalAlignBottom, R.string.queue_move_to_bottom, onDismiss)
        SheetAction(onGoToSource, pillarIcon(sourcePillar), goToSourceLabelRes(sourcePillar), onDismiss)
        SheetAction(
            onSetPlayed?.let { { it(!played) } },
            if (played) Icons.Outlined.RadioButtonUnchecked else Icons.Outlined.CheckCircle,
            if (played) R.string.mark_unplayed else R.string.mark_played,
            onDismiss,
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
internal fun SheetAction(action: (() -> Unit)?, icon: ImageVector, labelRes: Int, onDismiss: () -> Unit) {
    action?.let {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    onDismiss()
                    it()
                }
                .padding(horizontal = 24.dp, vertical = 14.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.padding(end = 24.dp))
            Text(stringResource(labelRes), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** "Go to channel" for a video, "Go to podcast" for an episode — the same rule the badge uses. */
internal fun goToSourceLabelRes(pillar: MediaKind): Int = when (pillar) {
    MediaKind.VIDEO -> R.string.go_to_channel
    MediaKind.PODCAST -> R.string.go_to_podcast
}
