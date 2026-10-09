package com.dewijones92.totum.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.youTubeVideoId
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.innertube.music.YouTubeMusicCatalogue
import com.dewijones92.totum.playback.SleepTimer
import com.dewijones92.totum.ui.common.LocalItemActions

internal data class PlayerLinks(
    val onArtist: (() -> Unit)? = null,
    val album: String? = null,
    val onAlbum: (() -> Unit)? = null,
    val lyrics: LyricsLoader? = null,
    val endOfGroup: EndOfGroupSleep? = null,
)

internal data class EndOfGroupSleep(val title: String, val songs: Int, val start: () -> Unit)

internal fun endOfGroupSleep(entries: List<QueueEntry>, currentIndex: Int, timer: SleepTimer): EndOfGroupSleep? {
    val group = entries.getOrNull(currentIndex)?.group ?: return null
    val rest = entries.drop(currentIndex).takeWhile { it.group?.id == group.id }.map { it.item.item.id }
    if (rest.size < 2) return null
    return EndOfGroupSleep(group.title, rest.size) {
        Diag.log("sleep", "stop at the end of \"${group.title}\": ${rest.size} songs, last ${rest.last().value}")
        timer.stopAtEndOf(rest, group.title)
    }
}

internal val LocalPlayerLinks = compositionLocalOf { PlayerLinks() }

@Composable
internal fun playerLinksFor(
    item: MediaItem?,
    catalogue: YouTubeMusicCatalogue,
    queue: List<QueueEntry>,
    currentIndex: Int,
    timer: SleepTimer,
): PlayerLinks {
    val actions = LocalItemActions.current
    return remember(item, actions, catalogue, queue, currentIndex, timer) {
        val endOfGroup = endOfGroupSleep(queue, currentIndex, timer)
        if (item == null || actions == null) {
            PlayerLinks(endOfGroup = endOfGroup)
        } else {
            PlayerLinks(
                onArtist = actions.sourceLink(item),
                album = item.album?.title,
                onAlbum = actions.albumLink(item),
                lyrics = item.takeIf { it.contentKind == MediaContentKind.MUSIC }
                    ?.let { it.mediaUrl?.youTubeVideoId() ?: it.id.value }
                    ?.let { LyricsLoader(it, catalogue) },
                endOfGroup = endOfGroup,
            )
        }
    }
}
