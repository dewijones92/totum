package com.dewijones92.totum.audiobooks

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.podcast.PodcastRepository
import com.dewijones92.totum.data.podcast.PreviewResult
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayState
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.whereToResume
import com.dewijones92.totum.operations.OperationEnd
import com.dewijones92.totum.operations.OperationKind
import com.dewijones92.totum.operations.Operations
import com.dewijones92.totum.operations.tracking
import com.dewijones92.totum.queue.PlaybackQueue

class BookPlayer(
    private val podcasts: PodcastRepository,
    private val queue: PlaybackQueue,
    private val states: suspend () -> Map<MediaItemId, PlayState>,
    private val operations: Operations? = null,
) {

    suspend fun chapters(book: MediaSource.PodcastFeed): List<MediaItem>? =
        when (val preview = podcasts.preview(book.feedUrl)) {
            is PreviewResult.Loaded -> preview.episodes
            is PreviewResult.Failed -> {
                Diag.warn(TAG, "\"${book.title}\" would not load: ${preview.detail}")
                null
            }
        }

    suspend fun play(
        book: MediaSource.PodcastFeed,
        label: String,
        known: List<MediaItem>? = null,
        startAt: MediaItemId? = null,
    ): Boolean {
        var played = false
        operations.tracking("book:${book.id.value}", OperationKind.TASK, label) {
            val chapters = known ?: chapters(book) ?: return@tracking OperationEnd.Failed("the book would not load")
            if (chapters.isEmpty()) return@tracking OperationEnd.Nothing
            val now = states()
            val start = startAt ?: whereToResume(chapters.map { it.id }, now)
            val at = chapters.indexOfFirst { it.id == start }
            Diag.log(
                TAG,
                "\"${book.title}\": playing chapter ${at + 1} of ${chapters.size} " +
                    "(\"${chapters.getOrNull(at)?.title}\", " +
                    "${if (startAt != null) "chosen" else "where you were"}) — " +
                    "${chapters.count { now[it.id]?.isPlayed == true }} finished, " +
                    "${chapters.count { now[it.id] is PlayState.InProgress }} started",
            )
            queue.playAll(
                chapters.map { PlayableItem(it, PlayHandle.Podcast()) },
                QueueGroup(id = groupId(book), title = book.title),
                startAt = start,
            )
            played = true
            OperationEnd.Done(1)
        }
        return played
    }

    companion object {
        private const val TAG = "audiobook"

        fun groupId(book: MediaSource.PodcastFeed): String = "book:${book.id.value}"
    }
}
