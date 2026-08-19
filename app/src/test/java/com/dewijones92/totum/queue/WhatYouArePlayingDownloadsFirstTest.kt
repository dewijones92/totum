package com.dewijones92.totum.queue

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.download.fake.FakeDownloadManager
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueSnapshot
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The item you are LISTENING to is downloaded first.
 *
 * From Dewi's Pixel, build 0.1.435: *"also it should have downloaded the audio???"* about a Ms Rachel
 * video he was 52 minutes into. It was first in a 47-item queue and had **no download event at all**,
 * while `downloads.queueWaiting = 5` and the downloader ground through a podcast that was 403ing and
 * retrying.
 *
 * The cause is the loop: `snapshot.entries.forEach { download(it) }` fetches strictly in queue order,
 * one at a time, awaiting each for up to `settleTimeoutMs` (ten minutes). So one slow or retrying item
 * blocks everything behind it — including the thing actually playing, which is the one item where a
 * download has immediate value: it is what the recovery ladder falls back to when the stream stalls,
 * which is exactly what was happening to him over SABR.
 *
 * Ordering only. Nothing is skipped and no budget changes — the playing item simply goes to the front
 * of the pass.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WhatYouArePlayingDownloadsFirstTest {

    private val dispatcher = StandardTestDispatcher()
    private val downloads = FakeDownloadManager()

    private fun item(id: String) = PlayableItem(
        item = MediaItem(
            id = MediaItemId(id),
            sourceId = SourceId("youtube"),
            title = id,
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://www.youtube.com/watch?v=$id"),
        ),
        handle = PlayHandle.Video(HttpUrl.of("https://www.youtube.com/watch?v=$id")),
    )

    private val queue = MutableStateFlow(QueueSnapshot())

    private fun downloader(playing: String?) = QueueAutoDownloader(
        queue = queue,
        downloads = downloads,
        scope = CoroutineScope(dispatcher),
        isEnabled = { true },
        isAllowedOnThisNetwork = { true },
        settleTimeoutMs = 10_000L,
        playingNow = { playing?.let(::MediaItemId) },
    )

    /** Starts the collector FIRST, then delivers the queue — the order the app runs in. */
    private fun startOver(ids: List<String>, playing: String?) {
        downloader(playing).start()
        queue.value = QueueSnapshot(ids.map { QueueEntry(item(it)) })
    }

    /** THE case: the playing item is last in the queue and must still be fetched first. */
    @Test
    fun `the playing item is fetched before the rest of the queue`() = runTest(dispatcher) {
        startOver(listOf("first", "second", "playing-this"), playing = "playing-this")
        advanceUntilIdle()

        assertEquals(
            "the item being listened to is the one a download helps soonest — it is what the rescue " +
                "ladder falls back to when the stream stalls",
            "playing-this",
            downloads.requested.firstOrNull()?.first?.value,
        )
    }

    /** With nothing playing, plain queue order is unchanged. */
    @Test
    fun `with nothing playing the queue order is untouched`() = runTest(dispatcher) {
        startOver(listOf("first", "second"), playing = null)
        advanceUntilIdle()

        assertEquals(listOf("first", "second"), downloads.requested.map { it.first.value })
    }

    /** Every item is still fetched — this reorders, it does not skip. */
    @Test
    fun `nothing is dropped by the reordering`() = runTest(dispatcher) {
        startOver(listOf("a", "b", "c"), playing = "c")
        advanceUntilIdle()

        assertEquals(setOf("a", "b", "c"), downloads.requested.map { it.first.value }.toSet())
    }
}
