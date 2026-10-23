package com.dewijones92.totum.data.download

import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.domain.PlayableItem
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * Keeps the [DownloadManager] on one strategy while letting each pillar bring its own
 * mechanics. This is the single place pillar-specific routing lives — everything
 * downstream sees one [DownloadStrategy].
 *
 * One strategy per pillar, chosen by an exhaustive `when` rather than a list of
 * predicates, so a third pillar cannot be added without this failing to compile. The
 * predicate version sniffed the item's URL for a YouTube host, which meant the router
 * and the queue disagreed about what a video was.
 */
public class RoutedDownloadStrategy(
    private val video: DownloadStrategy,
    private val podcast: DownloadStrategy,
) : DownloadStrategy {

    override fun download(item: PlayableItem, target: File, audioOnly: Boolean): Flow<DownloadState> =
        download(item, target, audioOnly, maxHeight = null)

    override fun download(
        item: PlayableItem,
        target: File,
        audioOnly: Boolean,
        maxHeight: Int?,
    ): Flow<DownloadState> =
        when (item.handle.pillar) {
            MediaKind.VIDEO -> video
            MediaKind.PODCAST -> podcast
        }.download(item, target, audioOnly, maxHeight)
}
