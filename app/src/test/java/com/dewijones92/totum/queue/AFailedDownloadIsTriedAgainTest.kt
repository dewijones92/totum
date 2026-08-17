package com.dewijones92.totum.queue

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.download.DownloadManager
import com.dewijones92.totum.data.download.fake.FakeDownloadManager
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueSnapshot
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A download that fails is tried again by itself, rather than waiting for the queue to change.
 *
 * Dewi, on report 0.1.390: *"downloading delayed????"* — the tennis podcast. What his phone
 * recorded:
 *
 * ```
 * 20:58:28.723 download start audioOnly=true Cincinnati - Will beaten Djokovic contend…
 * 20:58:31.462 download failed …: Network(detail=ERROR: unable to download video data:
 *                                  HTTP Error 403: Forbidden)
 * 20:58:37.549 queue move 1->0                          ← he happened to reorder the queue
 * 20:58:37.578 download start audioOnly=true Cincinnati - Will beaten Djokovic contend…
 * 20:58:49.830 download done                            ← the very next attempt worked
 * ```
 *
 * The retry budget existed and was never the problem — three transient attempts, and 403 is not
 * one of the permanent markers. What was missing is anything to *spend* it: the whole class was
 * driven by `queue.collect`, so a failure could only be reconsidered when the queue itself
 * changed. Reordering an entry is what got the tennis podcast downloaded, six seconds later and
 * entirely by accident. Left alone it would have sat failed until the next launch, which from the
 * outside is a download that silently never happens.
 *
 * [QueueAutoDownloaderTest] covers a failure that is *already* recorded when a pass begins; that
 * is a different moment and it passed throughout.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AFailedDownloadIsTriedAgainTest {

    private val dispatcher = StandardTestDispatcher()
    private val queue = MutableStateFlow(QueueSnapshot())

    /** His failure, verbatim, so the permanent-marker judgement is exercised on the real text. */
    private val forbidden =
        "Network(detail=ERROR: unable to download video data: HTTP Error 403: Forbidden)"

    /**
     * Fails the first [failFirst] attempts on each item and then succeeds, which is what a 403
     * from a signed stream URL actually does — the retry six seconds later worked.
     */
    private class Flaky(
        private val failFirst: Int,
        private val reason: String,
        val real: FakeDownloadManager = FakeDownloadManager(),
    ) : DownloadManager by real {
        private val attempts = mutableMapOf<MediaItemId, Int>()

        /** Every attempt, in order, whether it went on to succeed or fail. */
        val asked: MutableList<String> = mutableListOf()

        override suspend fun download(item: PlayableItem, audioOnly: Boolean) {
            val id = item.item.id
            asked += id.value
            val used = attempts.getOrDefault(id, 0) + 1
            attempts[id] = used
            if (used <= failFirst) real.setFailed(id, reason) else real.download(item, audioOnly)
        }
    }

    private fun entry(id: String) = QueueEntry(
        PlayableItem(
            MediaItem(
                id = MediaItemId(id),
                sourceId = SourceId("feed"),
                title = id,
                publishedAt = null,
                duration = null,
                mediaUrl = HttpUrl.of("https://example.test/$id.mp3"),
            ),
            PlayHandle.Podcast(),
        ),
    )

    private fun downloader(downloads: DownloadManager, maxAttempts: Int = 3) = QueueAutoDownloader(
        queue = queue,
        downloads = downloads,
        scope = CoroutineScope(dispatcher),
        isEnabled = { true },
        isAllowedOnThisNetwork = { true },
        maxAttempts = maxAttempts,
        settleTimeoutMs = SETTLE_MS,
    )

    /** The reported bug: one 403, and nothing ever asks again. */
    @Test
    fun `a transient failure is retried without the queue changing`() = runTest(dispatcher) {
        val downloads = Flaky(failFirst = 1, reason = forbidden)
        downloader(downloads).start()

        queue.value = QueueSnapshot(listOf(entry("tennis")))
        advanceUntilIdle()

        assertEquals("the 403 should have been followed by another go", 2, downloads.asked.size)
    }

    /** And it ends up on disk, which is the outcome he was actually missing. */
    @Test
    fun `after the retry it is downloaded`() = runTest(dispatcher) {
        val downloads = Flaky(failFirst = 1, reason = forbidden)
        downloader(downloads).start()

        queue.value = QueueSnapshot(listOf(entry("tennis")))
        advanceUntilIdle()

        val state = downloads.observeDownloads().first()[MediaItemId("tennis")]
        assertTrue("expected a finished download, got $state", state is DownloadState.Downloaded)
    }

    /** Once it works, it stops. A retry loop that cannot see success would re-fetch forever. */
    @Test
    fun `a success is not followed by another attempt`() = runTest(dispatcher) {
        val downloads = Flaky(failFirst = 0, reason = forbidden)
        downloader(downloads).start()

        queue.value = QueueSnapshot(listOf(entry("tennis")))
        advanceUntilIdle()

        assertEquals(listOf("tennis"), downloads.asked)
    }

    /** The budget still bounds it: a genuinely broken item does not spin. */
    @Test
    fun `retrying stops at the attempt limit`() = runTest(dispatcher) {
        val downloads = Flaky(failFirst = Int.MAX_VALUE, reason = forbidden)
        downloader(downloads, maxAttempts = 2).start()

        queue.value = QueueSnapshot(listOf(entry("tennis")))
        advanceUntilIdle()

        assertEquals("first attempt plus two retries", 3, downloads.asked.size)
    }

    /** A permanent failure gets no retry at all — the 2026-07-28 members-only regression. */
    @Test
    fun `a permanent failure is not retried`() = runTest(dispatcher) {
        val downloads = Flaky(
            failFirst = Int.MAX_VALUE,
            reason = "ERROR: [youtube] x: Join this channel to get access to members-only content",
        )
        downloader(downloads).start()

        queue.value = QueueSnapshot(listOf(entry("members")))
        advanceUntilIdle()

        assertEquals(listOf("members"), downloads.asked)
    }

    /** The item behind it is not held up by the retries, which run on its own turn. */
    @Test
    fun `the rest of the queue is still fetched`() = runTest(dispatcher) {
        val downloads = Flaky(failFirst = 1, reason = forbidden)
        downloader(downloads).start()

        queue.value = QueueSnapshot(listOf(entry("tennis"), entry("next")))
        advanceUntilIdle()

        assertTrue("the next item should have been fetched too", "next" in downloads.asked)
    }

    /**
     * A retry has to be visible in a report, or "it downloaded eventually" and "it downloaded
     * first time" read identically — and the whole complaint was about the delay.
     */
    @Test
    fun `the retry says why and which attempt it is`() = runTest(dispatcher) {
        val lines = mutableListOf<String>()
        val previous = Diag.sink
        Diag.sink = Diag.Sink { _, tag, message, _ -> if (tag == "download") lines += message }

        val downloads = Flaky(failFirst = 1, reason = forbidden)
        downloader(downloads).start()
        queue.value = QueueSnapshot(listOf(entry("tennis")))
        advanceUntilIdle()

        Diag.sink = previous
        assertTrue(
            "no line explains the retry: $lines",
            lines.any { "retrying" in it && "403" in it },
        )
    }

    private companion object {
        const val SETTLE_MS = 10_000L
    }
}
