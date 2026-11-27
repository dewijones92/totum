package com.dewijones92.totum.ui.player

import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.domain.Listening
import com.dewijones92.totum.domain.searchableText
import com.dewijones92.totum.domain.withArtworkFrom
import com.dewijones92.totum.queue.SkipWhy
import com.dewijones92.totum.ui.common.FilterField
import com.dewijones92.totum.ui.common.FilterToggle
import com.dewijones92.totum.ui.common.ItemActionSheet
import com.dewijones92.totum.ui.common.LiveBadge
import com.dewijones92.totum.ui.common.LocalSourceArtwork
import com.dewijones92.totum.ui.common.MediaThumbnail
import com.dewijones92.totum.ui.common.NoFilterMatches
import com.dewijones92.totum.ui.common.QueuedRowActions
import com.dewijones92.totum.ui.common.SwitchListeningButton
import com.dewijones92.totum.ui.common.face
import com.dewijones92.totum.ui.common.filter
import com.dewijones92.totum.ui.common.rememberListFilter

/** The up-next queue: a titled list, tap an entry to jump to it, long-press for its menu, X to remove it. */
@Composable
internal fun UpNextSection(queue: QueueControls) {
    var menuFor by remember { mutableStateOf<Int?>(null) }
    val listFilter = rememberListFilter("up next")
    val indexed = remember(queue.upNext) { queue.upNext.mapIndexed { index, entry -> index to entry } }
    val matching = listFilter.filter(indexed, fields = { (_, entry) -> entry.item.item.searchableText })
    val rows = if (listFilter.filtering) matching else matching.take(UP_NEXT_SHOWN)
    menuFor?.let { index ->
        queue.upNext.getOrNull(index)?.let { entry ->
            ItemActionSheet(
                entry.item.item,
                onDismiss = { menuFor = null },
                pillar = entry.item.pillar,
                queued = QueuedRowActions(
                    onRemove = { queue.onRemove(index) },
                    onMoveToTop = queue.onMoveToEdge?.let { move -> { move(index, true) } },
                    onMoveToBottom = queue.onMoveToEdge?.takeIf { index < queue.upNext.lastIndex }
                        ?.let { move -> { move(index, false) } },
                    onPlayInstead = queue.onPlayInstead?.let { instead -> { instead(index) } },
                ),
            )
        }
    }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = upNextCountText(queue),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            FilterToggle(listFilter, queue.upNext.size)
            SwitchListeningButton("full player")
        }
        FilterField(listFilter, matching.size, queue.upNext.size, inset = 0.dp, hosted = true)
        if (listFilter.filtering && matching.isEmpty()) NoFilterMatches(listFilter.query)
        rows.forEach { (index, queued) ->
            UpNextRow(
                queued = queued,
                skip = queue.skips[queued.item.item.id],
                onPlay = { queue.onPlay(index) },
                onMenu = { menuFor = index },
                onRemove = { queue.onRemove(index) },
            )
        }
        if (!listFilter.filtering && queue.upNext.size > UP_NEXT_SHOWN) {
            Text(
                text = pluralStringResource(
                    R.plurals.player_up_next_more,
                    queue.upNext.size - UP_NEXT_SHOWN,
                    queue.upNext.size - UP_NEXT_SHOWN,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UpNextRow(
    queued: QueueEntry,
    skip: SkipWhy?,
    onPlay: () -> Unit,
    onMenu: () -> Unit,
    onRemove: () -> Unit,
) {
    val item = queued.item.item
    val face = item.face()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (skip == null) 1f else UP_NEXT_SKIPPED_ALPHA)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = UP_NEXT_ALPHA))
            .combinedClickable(onClick = onPlay, onLongClick = onMenu)
            .padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
    ) {
        MediaThumbnail(
            url = item.withArtworkFrom(LocalSourceArtwork.current).thumbnailUrl,
            contentDescription = null,
            modifier = Modifier.size(width = UP_NEXT_THUMB_WIDTH, height = UP_NEXT_THUMB_HEIGHT),
            shape = MaterialTheme.shapes.small,
            blurred = face.blurThumbnail,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            LiveBadge(item.id, item.contentKind, Modifier.padding(bottom = 2.dp))
            Text(text = face.title, style = MaterialTheme.typography.titleSmall)
            skip?.let {
                Text(
                    text = stringResource(it.labelRes()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(UP_NEXT_SKIP_TAG),
                )
            }
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.queue_remove))
        }
    }
}

@Composable
private fun upNextCountText(queue: QueueControls): String =
    if (queue.skips.isEmpty()) {
        pluralStringResource(R.plurals.player_up_next_count, queue.upNext.size, queue.upNext.size)
    } else {
        pluralStringResource(
            R.plurals.player_up_next_count_skipped,
            queue.upNext.size,
            queue.upNext.size,
            queue.skips.size,
        )
    }

private const val UP_NEXT_ALPHA = 0.7f
private const val UP_NEXT_SKIPPED_ALPHA = 0.5f
const val UP_NEXT_SKIP_TAG: String = "up-next-skip"

@StringRes
internal fun SkipWhy.labelRes(): Int = when (this) {
    is SkipWhy.PassedOver -> R.string.up_next_skip_finished
    is SkipWhy.OtherKind -> if (kind == Listening.MUSIC) R.string.up_next_skip_music else R.string.up_next_skip_talk
    SkipWhy.IsPlaying -> R.string.up_next_skip_playing
}

private val UP_NEXT_THUMB_WIDTH = 80.dp
private val UP_NEXT_THUMB_HEIGHT = 45.dp
private const val UP_NEXT_SHOWN = 8
