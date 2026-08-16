package com.dewijones92.totum.ui.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.ui.common.MediaItemRow
import com.dewijones92.totum.ui.common.mediaItemFacts
import com.dewijones92.totum.ui.player.WatchViewModel.RelatedState

/**
 * The "up next" list on the watch page: the video's related videos, tappable to
 * play. Sits below the description, above comments. When the current video ends
 * the top entry autoplays (driven from the shell), so this is both a picker and
 * the visible queue.
 *
 * It renders the shared [MediaItemRow] like every other list. It used to have a
 * bespoke row, because it was handed InnerTube's `FeedVideo` rather than a
 * [MediaItem] and the shared row simply did not fit — so long-press, play state,
 * offline status and the pillar label were all silently missing here alone. The
 * conversion now happens in the view-model, where every other list already does it.
 */
@Composable
internal fun RelatedSection(
    related: RelatedState,
    onPlayRelated: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.related_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        when (related) {
            RelatedState.Loading -> PlayerNote(stringResource(R.string.related_loading))
            RelatedState.Error -> PlayerNote(stringResource(R.string.related_error))
            is RelatedState.Loaded ->
                if (related.videos.isEmpty()) {
                    PlayerNote(stringResource(R.string.related_empty))
                } else {
                    related.videos.forEach { video ->
                        MediaItemRow(
                            item = video,
                            subtitleLines = mediaItemFacts(video, MediaKind.VIDEO),
                            pillar = MediaKind.VIDEO,
                            onPlay = { onPlayRelated(video) },
                            onDownload = { },
                            onDeleteDownload = { },
                        )
                    }
                }
        }
    }
}
