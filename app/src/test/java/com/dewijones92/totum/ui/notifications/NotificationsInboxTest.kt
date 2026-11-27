package com.dewijones92.totum.ui.notifications

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.common.Page
import com.dewijones92.totum.common.PageToken
import com.dewijones92.totum.data.content.fake.InMemorySeenItemsTracker
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.queue.fake.InMemoryQueueStore
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.innertube.feeds.AccountFeed
import com.dewijones92.totum.innertube.feeds.FeedResult
import com.dewijones92.totum.innertube.feeds.FeedVideo
import com.dewijones92.totum.innertube.feeds.fake.FakeYouTubeFeeds
import com.dewijones92.totum.innertube.history.fake.FakeYouTubeWatchHistory
import com.dewijones92.totum.playback.fake.FakePlaybackController
import com.dewijones92.totum.queue.PlaybackQueue
import com.dewijones92.totum.video.VideoPlaybackLauncher
import com.dewijones92.totum.video.VideoResolver
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The bell as an INBOX.
 *
 * It used to hold only unseen uploads and empty itself the moment you opened it, so it could
 * never answer "what did I already look at". Dewi asked for the history to stay with the
 * unread floated to the top.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationsInboxTest {

    private val dispatcher = StandardTestDispatcher()
    private val feeds = FakeYouTubeFeeds()
    private val tracker = InMemorySeenItemsTracker()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun video(id: String) = FeedVideo(
        videoId = id,
        title = id,
        author = "A channel",
        durationSeconds = 60,
        thumbnailUrl = null,
        watchUrl = HttpUrl.of("https://www.youtube.com/watch?v=$id"),
    )

    private fun feedOf(vararg ids: String) {
        feeds.results[AccountFeed.SUBSCRIPTIONS] = FeedResult.Success(Page(ids.map(::video), null))
    }

    private fun model(scope: CoroutineScope) = NotificationsViewModel(
        feeds = feeds,
        tracker = tracker,
        queue = PlaybackQueue(
            FakePlaybackController(),
            VideoPlaybackLauncher(
                VideoResolver(FakeYtDlpEngine(), SkipSegmentSource { emptyList() }),
                FakePlaybackController(),
                FakeYouTubeWatchHistory(),
                InMemoryPlayHistoryStore(),
            ),
            scope,
            InMemoryQueueStore(),
        ),
    )

    @Test
    fun `everything is listed, not just what is new`() = runTest(dispatcher) {
        feedOf("a", "b", "c")
        val model = model(backgroundScope)
        testScheduler.advanceUntilIdle()
        model.markAllSeen()

        // A later refresh with one genuinely new upload keeps the old ones.
        feedOf("d", "a", "b", "c")
        model.refresh()
        testScheduler.advanceUntilIdle()

        assertEquals(4, model.snapshotUploads().size)
    }

    @Test
    fun `unread float to the top, seen stay below`() = runTest(dispatcher) {
        feedOf("a", "b")
        val model = model(backgroundScope)
        testScheduler.advanceUntilIdle()
        model.markAllSeen()

        // YouTube returns newest first, so the new one arrives ahead of the seen pair anyway;
        // put it last to prove the ORDERING is ours and not just the feed's.
        feedOf("a", "b", "new")
        model.refresh()
        testScheduler.advanceUntilIdle()

        val inbox = model.snapshotUploads()
        assertEquals("new", inbox.first().item.id.value)
        assertEquals(listOf(true, false, false), inbox.map { it.unread })
    }

    @Test
    fun `opening the inbox clears the badge but keeps the rows`() = runTest(dispatcher) {
        // The first sighting of a source is deliberately all-seen — SeenItemsTracker's
        // contract, so a fresh install does not open on sixty unread. Unread only exists
        // once something arrives AFTER the source is known.
        feedOf("a", "b")
        val model = model(backgroundScope)
        testScheduler.advanceUntilIdle()

        feedOf("new", "a", "b")
        model.refresh()
        testScheduler.advanceUntilIdle()
        // The badge counts unread rows; asserted on the rows rather than the derived
        // StateFlow, which only publishes while something is collecting it.
        assertEquals(1, model.snapshotUploads().count { it.unread })

        model.markAllSeen()
        model.refresh()
        testScheduler.advanceUntilIdle()

        assertEquals(0, model.snapshotUploads().count { it.unread })
        assertEquals(3, model.snapshotUploads().size)
    }

    private fun pagedFeed(first: List<String>, second: List<String>, token: String = "page-2") {
        feeds.results[AccountFeed.SUBSCRIPTIONS] = FeedResult.Success(Page(first.map(::video), PageToken(token)))
        feeds.pages[token] = FeedResult.Success(Page.last(second.map(::video)))
    }

    @Test
    fun `reaching the end of the list brings the second page, behind the first, as already seen`() =
        runTest(dispatcher) {
            pagedFeed(first = listOf("a", "b"), second = listOf("c", "d"))
            val model = model(backgroundScope)
            testScheduler.advanceUntilIdle()
            assertEquals(listOf("a", "b"), model.uploads.value.map { it.item.id.value })
            assertEquals(true, model.hasMore.value)

            model.loadMore()
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("a", "b", "c", "d"), model.uploads.value.map { it.item.id.value })
            assertEquals("older rows do not inflate the bell", 0, model.uploads.value.count { it.unread })
            assertEquals(false, model.hasMore.value)
            assertEquals(listOf(null, PageToken("page-2")), feeds.requested.map { it.second })
        }

    @Test
    fun `asking again while a page is loading, or after the last page, asks nothing more`() = runTest(dispatcher) {
        pagedFeed(first = listOf("a"), second = listOf("b"))
        val model = model(backgroundScope)
        testScheduler.advanceUntilIdle()

        model.loadMore()
        model.loadMore()
        testScheduler.advanceUntilIdle()
        model.loadMore()
        testScheduler.advanceUntilIdle()

        assertEquals(2, feeds.requested.size)
        assertEquals(listOf("a", "b"), model.uploads.value.map { it.item.id.value })
    }

    @Test
    fun `a page that fails keeps the rows and can be asked for again`() = runTest(dispatcher) {
        pagedFeed(first = listOf("a"), second = listOf("b"))
        feeds.pages["page-2"] = FeedResult.Failure("offline")
        val model = model(backgroundScope)
        testScheduler.advanceUntilIdle()

        model.loadMore()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("a"), model.uploads.value.map { it.item.id.value })
        assertEquals(true, model.hasMore.value)

        feeds.pages["page-2"] = FeedResult.Success(Page.last(listOf(video("b"))))
        model.loadMore()
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("a", "b"), model.uploads.value.map { it.item.id.value })
    }

    @Test
    fun `a video that comes round again on the next page is listed once`() = runTest(dispatcher) {
        pagedFeed(first = listOf("a", "b"), second = listOf("b", "c"))
        val model = model(backgroundScope)
        testScheduler.advanceUntilIdle()

        model.loadMore()
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("a", "b", "c"), model.uploads.value.map { it.item.id.value })
    }
}
