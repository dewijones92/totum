package com.dewijones92.totum.notifications

import com.dewijones92.totum.data.download.DownloadEvent
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId

/** What the shade should currently say about downloads. Rendered by [DownloadNotifier]. */
internal data class DownloadNotice(
    val active: List<MediaItem> = emptyList(),
    /** Aggregate progress across active downloads; null while no download reports any. */
    val percent: Int? = null,
    val running: List<RunningDownload> = emptyList(),
    val waiting: Int = 0,
    val completed: List<MediaItem> = emptyList(),
    val failed: List<FailedDownload> = emptyList(),
) {
    val isIdle: Boolean get() = active.isEmpty() && completed.isEmpty() && failed.isEmpty()
}

internal data class FailedDownload(val item: MediaItem, val reason: String)

internal data class RunningDownload(val item: MediaItem, val downloadedBytes: Long, val totalBytes: Long?) {
    val percent: Int? get() = totalBytes?.takeIf { it > 0 }?.let { (downloadedBytes * PERCENT_OF / it).toInt() }
}

private const val PERCENT_OF = 100L

/**
 * Turns a stream of [DownloadEvent]s into what should be on screen.
 *
 * Pure and synchronous on purpose: this is where all the judgement lives — what counts as
 * a batch, when to forget finished work, how to aggregate progress — so it can be tested
 * on the JVM instead of by squinting at a phone. [DownloadNotifier] only renders.
 *
 * **One notification per RUNNING download, under a summary that counts the rest.** Totum
 * auto-downloads the audio of everything in the queue, so one per queued item would mean thirty
 * notifications for one playlist; but only four ever run at once (the download slots), so a bar
 * each for those, with "N waiting" on the summary, stays small (Dewi, 2026-10-31: "I can only see
 * like one at a time").
 */
internal class DownloadNoticeTracker {

    private val active = LinkedHashMap<MediaItemId, Pair<MediaItem, DownloadState.Downloading>>()
    private val completed = LinkedHashMap<MediaItemId, MediaItem>()
    private val failed = LinkedHashMap<MediaItemId, FailedDownload>()

    fun onEvent(event: DownloadEvent): DownloadNotice {
        val id = event.item.id
        when (val state = event.state) {
            is DownloadState.Downloading -> {
                // A download starting while nothing is active begins a new batch, so the
                // previous batch's results stop accumulating. Without this the completed
                // list would grow for the whole session.
                if (active.isEmpty()) {
                    completed.clear()
                    failed.clear()
                }
                completed -= id
                failed -= id
                active[id] = event.item to state
            }

            is DownloadState.Downloaded -> {
                active -= id
                completed[id] = event.item
            }

            is DownloadState.Failed -> {
                active -= id
                failed[id] = FailedDownload(event.item, state.reason)
            }

            // A delete, or the startup reset of an interrupted download, is not news.
            DownloadState.NotDownloaded -> {
                active -= id
                completed -= id
                failed -= id
            }
        }
        return notice()
    }

    private fun notice(): DownloadNotice {
        val known = active.values.mapNotNull { it.second.fraction?.let { f -> (f * PERCENT).toInt() } }
        val (started, queued) = active.values.partition { (_, state) ->
            state.downloadedBytes > 0 || state.totalBytes != null
        }
        return DownloadNotice(
            active = active.values.map { it.first },
            percent = known.takeIf { it.size == active.size && it.isNotEmpty() }?.average()?.toInt(),
            running = started.map { (item, state) -> RunningDownload(item, state.downloadedBytes, state.totalBytes) },
            waiting = queued.size,
            completed = completed.values.toList(),
            failed = failed.values.toList(),
        )
    }

    private companion object {
        const val PERCENT = 100
    }
}
