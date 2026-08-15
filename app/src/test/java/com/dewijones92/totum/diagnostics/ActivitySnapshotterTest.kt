package com.dewijones92.totum.diagnostics

import com.dewijones92.totum.common.Breadcrumbs
import com.dewijones92.totum.data.download.fake.FakeDownloadManager
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.innertube.history.fake.FakeYouTubeWatchHistory
import com.dewijones92.totum.playback.fake.FakePlaybackController
import com.dewijones92.totum.queue.PlaybackQueue
import com.dewijones92.totum.video.VideoPlaybackLauncher
import com.dewijones92.totum.video.VideoResolver
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ActivitySnapshotterTest {

    private val downloads = FakeDownloadManager()
    private val controller = FakePlaybackController()

    @Before
    fun reset() = Breadcrumbs.clear()

    @After
    fun tidy() = Breadcrumbs.clear()

    private fun snapshots() = Breadcrumbs.snapshot().filter { it.tag == "snapshot" }

    /** An idle app in the background must not spend the retention window on nothing. */
    @Test
    fun `an idle app records nothing`() = runTest {
        val queue = PlaybackQueue(controller, launcher(), backgroundScope)
        ActivitySnapshotter(controller, downloads, queue, backgroundScope, INTERVAL).start()

        advanceTimeBy(INTERVAL * 5)

        assertEquals(emptyList<String>(), snapshots().map { it.message })
    }

    /**
     * The point of the whole thing: a download in flight produces no transitions, so
     * without a periodic sample it is invisible for as long as it takes.
     */
    @Test
    fun `a download in flight is recorded even with nothing playing`() = runTest {
        val queue = PlaybackQueue(controller, launcher(), backgroundScope)
        downloads.setDownloading(MediaItemId("ep-1"), downloadedBytes = 4_000_000, totalBytes = 10_000_000)
        ActivitySnapshotter(controller, downloads, queue, backgroundScope, INTERVAL).start()

        advanceTimeBy(INTERVAL + 1)

        val line = snapshots().firstOrNull()?.message
        assertTrue("expected a snapshot naming the download, got: $line", line?.contains("downloading=1") == true)
        assertTrue("expected its progress, got: $line", line?.contains("40%") == true)
    }

    /**
     * A paused player says the same thing every thirty seconds forever. Report 0.1.385 carried 52
     * byte-identical lines and half its four-hundred-entry buffer was heartbeats — which is how a
     * chatty log destroys the evidence it was added to provide.
     */
    @Test
    fun `an unchanged state is recorded once, not on every tick`() = runTest {
        val queue = PlaybackQueue(controller, launcher(), backgroundScope)
        downloads.setDownloading(MediaItemId("ep-1"), downloadedBytes = 4_000_000, totalBytes = 10_000_000)
        ActivitySnapshotter(controller, downloads, queue, backgroundScope, INTERVAL).start()

        advanceTimeBy(INTERVAL * 6)

        assertEquals("six identical ticks should be one line", 1, snapshots().size)
    }

    /**
     * But NOT dropped silently. A player frozen at one position for twenty minutes is a finding,
     * and it must not look like a gap in the trail — so the stretch is stated once, in seconds,
     * when something finally changes.
     */
    @Test
    fun `how long it stayed unchanged is said out loud when it changes`() = runTest {
        val queue = PlaybackQueue(controller, launcher(), backgroundScope)
        downloads.setDownloading(MediaItemId("ep-1"), downloadedBytes = 4_000_000, totalBytes = 10_000_000)
        ActivitySnapshotter(controller, downloads, queue, backgroundScope, INTERVAL).start()
        // +1 so the fourth tick has unambiguously fired; landing exactly on the boundary makes
        // the count depend on scheduler ordering rather than on the behaviour under test.
        advanceTimeBy(INTERVAL * 4 + 1)

        downloads.setDownloading(MediaItemId("ep-1"), downloadedBytes = 9_000_000, totalBytes = 10_000_000)
        advanceTimeBy(INTERVAL * 2)

        val messages = snapshots().map { it.message }
        assertTrue(
            "the unchanged stretch must be stated, got:\n${messages.joinToString("\n")}",
            messages.any { it.contains("unchanged for the next 3 snapshot(s), 3s") },
        )
        assertTrue("and the change itself recorded", messages.any { it.contains("90%") })
    }

    /** A change still gets its own line immediately — collapsing must not delay real news. */
    @Test
    fun `a change is recorded on the tick it happens`() = runTest {
        val queue = PlaybackQueue(controller, launcher(), backgroundScope)
        downloads.setDownloading(MediaItemId("ep-1"), downloadedBytes = 1_000_000, totalBytes = 10_000_000)
        ActivitySnapshotter(controller, downloads, queue, backgroundScope, INTERVAL).start()
        advanceTimeBy(INTERVAL + 1)

        downloads.setDownloading(MediaItemId("ep-1"), downloadedBytes = 5_000_000, totalBytes = 10_000_000)
        advanceTimeBy(INTERVAL)

        assertEquals(2, snapshots().size)
    }

    private fun launcher() = VideoPlaybackLauncher(
        VideoResolver(FakeYtDlpEngine(), SkipSegmentSource { emptyList() }),
        controller,
        FakeYouTubeWatchHistory(),
        InMemoryPlayHistoryStore(),
    )

    private companion object {
        const val INTERVAL = 1_000L
    }
}
