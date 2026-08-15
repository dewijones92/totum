package com.dewijones92.totum.diagnostics

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.download.DownloadManager
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.playback.PlaybackController
import com.dewijones92.totum.playback.PlaybackVitals
import com.dewijones92.totum.queue.PlaybackQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Writes a periodic line describing what the app is *currently* doing — what is playing
 * and where, what is downloading and how far along, how long the queue is.
 *
 * The event trail records transitions: started, finished, failed. That answers "what
 * happened" but not "what was it in the middle of", and a download that has been stuck at
 * 40% for ten minutes produces no events at all — it is invisible precisely when it is
 * the problem. These snapshots turn the trail into a timeline you can scan.
 *
 * Silent when nothing is happening, so an idle app in the background writes nothing and
 * the retention window is spent on the parts that matter.
 *
 * And silent when nothing has CHANGED, which is a different thing and cost 13% of a real report.
 * Report 0.1.385 carried 52 byte-identical lines — 28 of `at 3033844ms (stopped)` and 24 of
 * `at 1441250ms (stopped)` — because a paused player produces an identical description every
 * thirty seconds forever. Half that report's four-hundred-entry buffer was heartbeats. A repeat
 * is counted and stated once when it ends, per the counted-never-silent rule the rest of the
 * trail follows: "nothing has changed" is worth knowing, and worth exactly one line.
 */
internal class ActivitySnapshotter(
    private val playback: PlaybackController,
    private val downloads: DownloadManager,
    private val queue: PlaybackQueue,
    private val scope: CoroutineScope,
    private val intervalMs: Long = SNAPSHOT_INTERVAL_MS,
) {
    fun start() {
        scope.launch {
            while (isActive) {
                delay(intervalMs)
                runCatching { snapshot() }.onFailure {
                    Diag.warn("snapshot", "could not sample app state", it)
                }
            }
        }
    }

    private var lastLine: String? = null
    private var repeats = 0

    private suspend fun snapshot() {
        val line = describe() ?: run {
            flushRepeats()
            lastLine = null
            return
        }
        if (line == lastLine) {
            repeats++
            return
        }
        flushRepeats()
        lastLine = line
        Diag.log("snapshot", line)
    }

    /**
     * Says how long the unchanged stretch lasted, once, when something finally changes.
     *
     * Dropping repeats silently would be the opposite mistake: a player frozen at one position for
     * twenty minutes is a finding, and it would look identical to a gap in the trail. The duration
     * is spelled out rather than left as a count, because "×40" means nothing without the interval.
     */
    private fun flushRepeats() {
        if (repeats == 0) return
        Diag.log(
            "snapshot",
            "...and unchanged for the next $repeats snapshot(s), ${repeats * intervalMs / MILLIS_PER_SEC}s",
        )
        repeats = 0
    }

    /** Null when there is nothing worth recording, which keeps an idle app quiet. */
    private suspend fun describe(): String? {
        val playing = playback.state.value
        val states = downloads.observeDownloads().first()
        val active = states.values.filterIsInstance<DownloadState.Downloading>()
        val failed = states.values.count { it is DownloadState.Failed }
        val queued = queue.state.value.entries.size
        if (playing == null && active.isEmpty() && failed == 0) return null

        return buildString {
            if (playing != null) {
                append(
                    "playing \"${playing.title}\" at ${playing.positionMs}ms" +
                        " (${if (playing.isPlaying) "running" else "stopped"}" +
                        "${if (playing.isBuffering) ", buffering" else ""})",
                )
            } else {
                append("nothing playing")
            }
            append("; queue=$queued")
            // Throughput alongside position: a snapshot showing a stalled item and the
            // rate it is being fed at is what turns "buffering" into a cause.
            PlaybackVitals.kbps()?.let { append("; ~${it}kbps") }
            append("; downloading=${active.size}")
            active.take(MAX_LISTED_DOWNLOADS).forEach { append(" [${it.percent()}]") }
            if (failed > 0) append("; failed=$failed")
        }
    }

    /** Percent where the total is known, bytes where it isn't — yt-dlp often omits it. */
    private fun DownloadState.Downloading.percent(): String =
        totalBytes?.takeIf { it > 0 }
            ?.let { "${downloadedBytes * PERCENT / it}%" }
            ?: "${downloadedBytes / BYTES_PER_MB}MB"

    private companion object {
        const val SNAPSHOT_INTERVAL_MS = 30_000L
        const val MAX_LISTED_DOWNLOADS = 3
        const val PERCENT = 100
        const val BYTES_PER_MB = 1024 * 1024
        const val MILLIS_PER_SEC = 1_000
    }
}
