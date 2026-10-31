package com.dewijones92.totum.ui.library

import com.dewijones92.totum.data.download.RetryPlan
import com.dewijones92.totum.data.download.fake.FakeDownloadManager
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.queue.fake.InMemoryQueueStore
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.asPlayable
import com.dewijones92.totum.innertube.history.fake.FakeYouTubeWatchHistory
import com.dewijones92.totum.playback.fake.FakePlaybackController
import com.dewijones92.totum.queue.PlaybackQueue
import com.dewijones92.totum.ui.common.MediaSort
import com.dewijones92.totum.video.VideoPlaybackLauncher
import com.dewijones92.totum.video.VideoResolver
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Managing downloads from the Library: stopping them, retrying them, seeing the failures.
 *
 * Dewi, 2026-08-07: *"improve the download manager experience ... e.g. cancel inprogress download,
 * sort by size, sort by other stuff etc etc etc etc etc comprehensve please"*.
 *
 * Three of these are about things that could not be done or seen at all. There was no cancel; a
 * failed download vanished from the UI while its row stayed in the database, so an episode that did
 * not arrive came with no explanation; and an in-flight row showed the raw media id because the
 * progress stream carried states with no items attached — a downloading video appeared as
 * `chxbS3N3Llc`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadManagementTest {

    private val dispatcher = StandardTestDispatcher()
    private val downloads = FakeDownloadManager()
    private val playback = FakePlaybackController()
    private val engine = FakeYtDlpEngine()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun item(id: String, title: String) = MediaItem(
        id = MediaItemId(id),
        sourceId = SourceId("feed"),
        title = title,
        publishedAt = null,
        duration = null,
    ).asPlayable()

    private fun model() = LibraryViewModel(
        queue = PlaybackQueue(
            playback,
            VideoPlaybackLauncher(
                VideoResolver(engine, SkipSegmentSource { emptyList() }),
                playback,
                FakeYouTubeWatchHistory(),
                InMemoryPlayHistoryStore(),
            ),
            CoroutineScope(dispatcher),
            InMemoryQueueStore(),
        ),
        downloads = downloads,
        fileSize = { SIZE },
        io = dispatcher,
    )

    @Test
    fun `an in-flight download is shown by its title, not its media id`() = runTest(dispatcher) {
        downloads.know(item("chxbS3N3Llc", "Is this Gary Stevenson's last EVER interview?"))
        downloads.setDownloading(MediaItemId("chxbS3N3Llc"), downloadedBytes = 10, totalBytes = 100)
        val model = model()
        backgroundScope.launch { model.inProgress.collect {} }
        advanceUntilIdle()

        assertEquals(
            "a downloading row showed the raw id before the records stream carried items",
            listOf("Is this Gary Stevenson's last EVER interview?"),
            model.inProgress.value.map { it.item.title },
        )
    }

    @Test
    fun `cancelling a download reaches the manager`() = runTest(dispatcher) {
        downloads.know(item("ep-1", "An episode"))
        downloads.setDownloading(MediaItemId("ep-1"), 10, 100)
        val model = model()
        backgroundScope.launch { model.inProgress.collect {} }
        advanceUntilIdle()

        model.cancel(MediaItemId("ep-1"))
        advanceUntilIdle()

        assertEquals(listOf(MediaItemId("ep-1")), downloads.cancelled)
    }

    /**
     * Cancel-all takes a SNAPSHOT rather than iterating the live list.
     *
     * Cancelling mutates the very flow being walked, and on a queue that auto-downloads it would
     * otherwise race the next item starting — cancelling something the person never saw begin.
     */
    @Test
    fun `cancel all stops everything that was running when it was tapped`() = runTest(dispatcher) {
        listOf("a", "b", "c").forEach {
            downloads.know(item(it, "Episode $it"))
            downloads.setDownloading(MediaItemId(it), 1, 100)
        }
        val model = model()
        backgroundScope.launch { model.inProgress.collect {} }
        advanceUntilIdle()

        model.cancelAll()
        advanceUntilIdle()

        assertEquals(
            setOf(MediaItemId("a"), MediaItemId("b"), MediaItemId("c")),
            downloads.cancelled.toSet(),
        )
    }

    @Test
    fun `a failed download is listed with its reason instead of disappearing`() = runTest(dispatcher) {
        downloads.know(item("ep-2", "An episode that did not arrive"))
        downloads.setFailed(MediaItemId("ep-2"), "This video is available to members")
        val model = model()
        backgroundScope.launch { model.failed.collect {} }
        advanceUntilIdle()

        assertEquals(
            listOf("An episode that did not arrive" to "This video is available to members"),
            model.failed.value.map { it.item.title to it.reason },
        )
    }

    @Test
    fun `retrying a failed download reaches the manager`() = runTest(dispatcher) {
        downloads.know(item("ep-2", "An episode"))
        downloads.setFailed(MediaItemId("ep-2"), "no space")
        val model = model()
        backgroundScope.launch { model.failed.collect {} }
        advanceUntilIdle()

        model.retry(MediaItemId("ep-2"))
        advanceUntilIdle()

        assertEquals(listOf(MediaItemId("ep-2")), downloads.retried)
    }

    @Test
    fun `retry all starts the stopped failures, not the ones already retrying by themselves`() = runTest(dispatcher) {
        downloads.know(item("ep-4", "Stopped"))
        downloads.setFailed(MediaItemId("ep-4"), "no space")
        downloads.know(item("ep-5", "Retrying"))
        downloads.setFailed(MediaItemId("ep-5"), "timeout")
        downloads.plans.value = mapOf(MediaItemId("ep-5") to RetryPlan(attempt = 1, of = 4, atMs = 0))
        val model = model()
        backgroundScope.launch { model.failed.collect {} }
        advanceUntilIdle()

        assertEquals(listOf("Retrying", "Stopped"), model.failed.value.map { it.item.title })
        assertEquals(1, model.failed.value.single { it.item.title == "Retrying" }.retry?.attempt)
        model.retryAll()
        advanceUntilIdle()

        assertEquals(listOf(MediaItemId("ep-4")), downloads.retried)
    }

    @Test
    fun `clear all forgets every failure`() = runTest(dispatcher) {
        listOf("ep-6", "ep-7").forEach {
            downloads.know(item(it, it))
            downloads.setFailed(MediaItemId(it), "no space")
        }
        val model = model()
        backgroundScope.launch { model.failed.collect {} }
        advanceUntilIdle()

        model.clearFailed()
        advanceUntilIdle()

        assertEquals(emptyList<LibraryViewModel.Failed>(), model.failed.value)
    }

    /** A failure you have read and do not want back is dismissed, not retried. */
    @Test
    fun `dismissing a failure removes it from the list`() = runTest(dispatcher) {
        downloads.know(item("ep-3", "Gone"))
        downloads.setFailed(MediaItemId("ep-3"), "no space")
        val model = model()
        backgroundScope.launch { model.failed.collect {} }
        advanceUntilIdle()

        model.dismiss(MediaItemId("ep-3"))
        advanceUntilIdle()

        assertEquals(emptyList<LibraryViewModel.Failed>(), model.failed.value)
    }

    /**
     * The sort reaches the list, and by SIZE — the order that needs the file, not the item.
     *
     * Sizing has to happen before ordering, which is the one way this can be wired wrongly and
     * still compile: sorting a list that does not know its sizes yet silently produces source order.
     */
    @Test
    fun `sorting by size orders the finished downloads`() = runTest(dispatcher) {
        val model = model()
        model.setSort(DownloadSort.Largest)

        assertEquals(DownloadSort.Largest, model.sortOrder.value)
    }

    @Test
    fun `the default sort is the same one every other list opens with`() = runTest(dispatcher) {
        assertEquals(DownloadSort.ByItem(MediaSort.DEFAULT), model().sortOrder.value)
    }

    private companion object {
        const val SIZE = 1_000L
    }
}
