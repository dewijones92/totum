package com.dewijones92.totum.queue

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.data.queue.QueueSnapshot
import com.dewijones92.totum.data.queue.QueueStore
import com.dewijones92.totum.data.queue.fake.InMemoryQueueStore
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.placeholderTitleFor
import com.dewijones92.totum.innertube.history.fake.FakeYouTubeWatchHistory
import com.dewijones92.totum.playback.fake.FakePlaybackController
import com.dewijones92.totum.video.VideoPlaybackLauncher
import com.dewijones92.totum.video.VideoResolver
import com.dewijones92.totum.ytdlp.ExtractionResult
import com.dewijones92.totum.ytdlp.MediaFormat
import com.dewijones92.totum.ytdlp.MediaMetadata
import com.dewijones92.totum.ytdlp.YtDlpEngine
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackQueueTest {

    private val dispatcher = StandardTestDispatcher()
    private val controller = FakePlaybackController()
    private val launcher = VideoPlaybackLauncher(
        VideoResolver(FakeYtDlpEngine(), SkipSegmentSource { emptyList() }),
        controller,
        FakeYouTubeWatchHistory(),
        InMemoryPlayHistoryStore(),
    )

    private val store = InMemoryQueueStore()

    /** Items the mirror was told about — how the Watch Later signal is asserted. */
    private val mirrored = mutableListOf<String>()

    private var mirrorFails = false

    /** Drives the repeat guard's clock; tests advance it rather than sleeping. */
    private var nowMs = 1_000L

    private fun queue(withStore: QueueStore = store) =
        PlaybackQueue(
            controller,
            launcher,
            CoroutineScope(dispatcher),
            withStore,
            onQueuedByUser = { item ->
                if (mirrorFails) error("network down")
                mirrored += item.item.id.value
            },
            clock = { nowMs },
        )

    /**
     * Dewi's report: "when I click play next on something already in the queue, it dups it."
     * Every add-path must move rather than duplicate — playNow already did, and the others
     * disagreed with it.
     */
    @Test
    fun `a row queued by its id learns title, date and author when it resolves`() = runTest(dispatcher) {
        val q = queue()
        val shared = PlayableItem(
            MediaItem(
                id = MediaItemId("aqz-KE-bpKQ"),
                sourceId = SourceId("shared"),
                title = placeholderTitleFor(MediaItemId("aqz-KE-bpKQ")),
                publishedAt = null,
                duration = null,
                mediaUrl = HttpUrl.of("https://www.youtube.com/watch?v=aqz-KE-bpKQ"),
            ),
            PlayHandle.Video(HttpUrl.of("https://www.youtube.com/watch?v=aqz-KE-bpKQ")),
        )
        q.enqueue(shared)
        q.enqueue(podcast("b"))
        advanceUntilIdle()

        q.adoptFacts(
            shared.item.copy(
                title = "Big Buck Bunny 60fps 4K",
                author = "Blender",
                publishedAt = Instant.parse("2014-11-10T00:00:00Z"),
            ),
        )
        advanceUntilIdle()

        val row = q.state.value.entries.first().item.item
        assertEquals("Big Buck Bunny 60fps 4K", row.title)
        assertEquals("Blender", row.author)
        assertEquals(Instant.parse("2014-11-10T00:00:00Z"), row.publishedAt)
        // Persisted, not just shown: the row must still know it after a restart.
        assertEquals("Big Buck Bunny 60fps 4K", store.load().entries.first().item.item.title)
        // The other row is untouched.
        assertEquals("b", q.state.value.entries[1].item.item.id.value)
    }

    @Test
    fun `a row that already knew its facts keeps them when something else resolves`() = runTest(dispatcher) {
        val q = queue()
        val known = podcast("a")
        q.enqueue(PlayableItem(known.item.copy(author = "The author it was queued with"), known.handle))
        advanceUntilIdle()
        val before = q.state.value.entries.first().item.item

        q.adoptFacts(before.copy(title = "A different title", author = "Someone else"))
        advanceUntilIdle()

        assertEquals(before.title, q.state.value.entries.first().item.item.title)
        assertEquals(before.author, q.state.value.entries.first().item.item.author)
    }

    @Test
    fun `a swiped-away entry is put back exactly where it was by undo`() = runTest(dispatcher) {
        val q = queue()
        q.playAll(listOf(podcast("a"), podcast("b"), podcast("c")))
        advanceUntilIdle()
        q.jumpTo(2)
        advanceUntilIdle()
        val removed = q.state.value.entries[1]

        q.removeAt(1)
        assertEquals(listOf("a", "c"), q.state.value.entries.map { it.item.item.id.value })
        assertEquals(1, q.state.value.currentIndex)

        q.restoreAt(1, removed)
        advanceUntilIdle()
        assertEquals(listOf("a", "b", "c"), q.state.value.entries.map { it.item.item.id.value })
        // Still playing "c", which moved back down a slot.
        assertEquals(2, q.state.value.currentIndex)
        assertEquals("c", q.state.value.current?.item?.item?.id?.value)
    }

    @Test
    fun `undo after the queue shrank still lands, at the end`() = runTest(dispatcher) {
        val q = queue()
        q.playAll(listOf(podcast("a"), podcast("b"), podcast("c")))
        advanceUntilIdle()
        val removed = q.state.value.entries[2]
        q.removeAt(2)
        q.removeAt(1)
        q.restoreAt(2, removed)
        advanceUntilIdle()
        assertEquals(listOf("a", "c"), q.state.value.entries.map { it.item.item.id.value })
    }

    @Test
    fun `play next moves an already-queued item instead of duplicating it`() = runTest(dispatcher) {
        val q = queue()
        q.enqueue(podcast("a"))
        q.enqueue(podcast("b"))
        q.enqueue(podcast("c"))

        q.playNext(podcast("c"))
        advanceUntilIdle()

        assertEquals(listOf("c", "a", "b"), q.state.value.entries.map { it.item.item.id.value })
    }

    @Test
    fun `add to queue moves an already-queued item to the end`() = runTest(dispatcher) {
        val q = queue()
        q.enqueue(podcast("a"))
        q.enqueue(podcast("b"))

        q.enqueue(podcast("a"))
        advanceUntilIdle()

        assertEquals(listOf("b", "a"), q.state.value.entries.map { it.item.item.id.value })
    }

    /**
     * The playing entry is exempt: removing it would drop the cursor and the queue would forget
     * where it was, so "play next" on what is already playing does nothing.
     */
    @Test
    fun `play next on the playing item leaves the queue and cursor alone`() = runTest(dispatcher) {
        val q = queue()
        q.playNow(podcast("a"))
        q.enqueue(podcast("b"))
        advanceUntilIdle()
        val cursorBefore = q.state.value.currentIndex

        q.playNext(podcast("a"))
        advanceUntilIdle()

        assertEquals(listOf("a", "b"), q.state.value.entries.map { it.item.item.id.value })
        assertEquals(cursorBefore, q.state.value.currentIndex)
    }

    @Test
    fun `play all does not duplicate items already queued`() = runTest(dispatcher) {
        val q = queue()
        q.enqueue(podcast("a"))
        q.enqueue(podcast("b"))
        advanceUntilIdle()

        q.playAll(listOf(podcast("b"), podcast("c")))
        advanceUntilIdle()

        // b moved into the run rather than being duplicated; c is new; a is left where it was.
        val ids = q.state.value.entries.map { it.item.item.id.value }
        assertEquals(listOf("b", "c", "a"), ids)
        assertEquals("no duplicates", ids.size, ids.distinct().size)
    }

    /** A caller can hand over a list with repeats; re-opening the shorts reel does exactly that. */
    @Test
    fun `play all drops repeats within its own run`() = runTest(dispatcher) {
        val q = queue()

        q.playAll(listOf(podcast("a"), podcast("b"), podcast("a")))
        advanceUntilIdle()

        assertEquals(listOf("a", "b"), q.state.value.entries.map { it.item.item.id.value })
    }

    /** A queue already polluted by the old behaviour repairs itself rather than staying broken. */
    @Test
    fun `a saved queue containing duplicates is repaired on load`() = runTest(dispatcher) {
        val polluted = InMemoryQueueStore()
        polluted.save(
            QueueSnapshot(
                entries = listOf(podcast("a"), podcast("b"), podcast("a"), podcast("b")).map { QueueEntry(it) },
                currentIndex = 1,
            ),
        )

        val q = queue(polluted)
        advanceUntilIdle()

        assertEquals(listOf("a", "b"), q.state.value.entries.map { it.item.item.id.value })
        // The cursor still points at the entry it pointed at, not at whatever landed on index 1.
        assertEquals("b", q.state.value.current?.item?.item?.id?.value)
    }

    /**
     * Dewi's ask: queueing something should tell YouTube he likes it. The two deliberate
     * add-paths mirror; the bulk ones must not.
     */
    @Test
    fun `adding to the queue mirrors the choice`() = runTest(dispatcher) {
        val q = queue()

        q.enqueue(podcast("a"))
        q.playNext(podcast("b"))
        advanceUntilIdle()

        assertEquals(listOf("a", "b"), mirrored)
    }

    /** playAll is a bulk run — the shorts reel hands over fifty at once and would bury it. */
    @Test
    fun `play all does not mirror`() = runTest(dispatcher) {
        val q = queue()

        q.playAll(listOf(podcast("a"), podcast("b")))
        advanceUntilIdle()

        assertTrue("a bulk run must not touch Watch Later", mirrored.isEmpty())
    }

    /** playNow means "watching it now", which the watch-history sync already reports. */
    @Test
    fun `play now does not mirror`() = runTest(dispatcher) {
        val q = queue()

        q.playNow(podcast("a"))
        advanceUntilIdle()

        assertTrue(mirrored.isEmpty())
    }

    /** The queue is local and must be instant; the network is not allowed to break it. */
    @Test
    fun `a failing mirror still queues the item`() = runTest(dispatcher) {
        mirrorFails = true
        val q = queue()

        q.enqueue(podcast("a"))
        advanceUntilIdle()

        assertEquals(listOf("a"), q.state.value.entries.map { it.item.item.id.value })
    }

    /**
     * The loop from Dewi's 0.1.199 report. He PEEKED a video that was also in the queue; peek
     * clears the cursor to -1 by design, so `currentIndex + 1` was 0 — and entry 0 was the very
     * video playing, so "advancing" replayed it. The trail said `advance=true` the whole time.
     */
    @Test
    fun `advancing never replays the item already playing`() = runTest(dispatcher) {
        val q = queue()
        q.enqueue(podcast("a"))
        q.enqueue(podcast("b"))
        advanceUntilIdle()
        // Peek the item that sits at index 0: cursor goes to -1 while "a" is what plays.
        q.peek(podcast("a"))
        advanceUntilIdle()
        assertEquals(-1, q.state.value.currentIndex)

        val advanced = q.playNextInQueue()
        advanceUntilIdle()

        assertTrue("should have advanced", advanced)
        assertEquals("b", q.state.value.current?.item?.item?.id?.value)
    }

    /** Advancing is relative to what is PLAYING, not to a cursor that may point elsewhere. */
    @Test
    fun `advancing follows the playing item even when the cursor is stale`() = runTest(dispatcher) {
        val q = queue()
        q.enqueue(podcast("a"))
        q.enqueue(podcast("b"))
        q.enqueue(podcast("c"))
        advanceUntilIdle()
        q.peek(podcast("b"))
        advanceUntilIdle()

        q.playNextInQueue()
        advanceUntilIdle()

        // From "b", the next is "c" — not "a", which is where a -1 cursor would have gone.
        assertEquals("c", q.state.value.current?.item?.item?.id?.value)
    }

    /** The return value is the truth now: it used to say true before trying anything. */
    @Test
    fun `advancing past the last item reports false`() = runTest(dispatcher) {
        val q = queue()
        q.enqueue(podcast("a"))
        advanceUntilIdle()
        q.peek(podcast("a"))
        advanceUntilIdle()

        assertFalse("nothing follows the only entry", q.playNextInQueue())
    }

    private fun podcast(id: String) = PlayableItem(
        MediaItem(
            id = MediaItemId(id),
            sourceId = SourceId("feed"),
            title = id,
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://feeds.example.com/$id.mp3"),
        ),
        PlayHandle.Podcast(),
    )

    @Test
    fun `enqueue adds to the end, playNext to the front`() {
        val q = queue()
        q.enqueue(podcast("a"))
        q.enqueue(podcast("b"))
        q.playNext(podcast("c"))

        assertEquals(listOf("c", "a", "b"), q.state.value.upNext.map { it.item.item.id.value })
    }

    @Test
    fun `removeAt drops that entry`() {
        val q = queue()
        listOf("a", "b", "c").forEach { q.enqueue(podcast(it)) }
        q.removeAt(1)

        assertEquals(listOf("a", "c"), q.state.value.upNext.map { it.item.item.id.value })
    }

    @Test
    fun `move reorders an entry and ignores out-of-range indices`() {
        val q = queue()
        listOf("a", "b", "c").forEach { q.enqueue(podcast(it)) }
        q.move(2, 0)
        assertEquals(listOf("c", "a", "b"), q.state.value.upNext.map { it.item.item.id.value })

        q.move(0, 9) // out of range → no change
        assertEquals(listOf("c", "a", "b"), q.state.value.upNext.map { it.item.item.id.value })
    }

    @Test
    fun `advancing moves the cursor without consuming entries`() = runTest(dispatcher) {
        val q = queue()
        advanceUntilIdle()
        q.enqueue(podcast("a"))
        q.enqueue(podcast("b"))

        assertTrue(q.playNextInQueue())
        advanceUntilIdle()

        assertEquals("a", controller.state.value?.itemId?.value)
        // Both entries remain; only the cursor moved, so you can go back to "a".
        assertEquals(listOf("a", "b"), q.state.value.entries.map { it.item.item.id.value })
        assertEquals(0, q.state.value.currentIndex)
        assertEquals(listOf("b"), q.state.value.upNext.map { it.item.item.id.value })
    }

    @Test
    fun `playNextInQueue skips an unplayable item and plays the next`() = runTest(dispatcher) {
        val q = queue()
        // A podcast with neither a downloaded file nor a stream URL can't play.
        val unplayable = PlayableItem(
            MediaItem(
                id = MediaItemId("bad"),
                sourceId = SourceId("feed"),
                title = "bad",
                publishedAt = null,
                duration = null,
                mediaUrl = null,
            ),
            PlayHandle.Podcast(),
        )
        q.enqueue(unplayable)
        q.enqueue(podcast("good"))

        assertTrue(q.playNextInQueue())
        advanceUntilIdle()

        assertEquals("good", controller.state.value?.itemId?.value)
        assertTrue(q.state.value.upNext.isEmpty())
    }

    @Test
    fun `playNextInQueue on an empty queue returns false and plays nothing`() = runTest(dispatcher) {
        val q = queue()
        assertFalse(q.playNextInQueue())
        advanceUntilIdle()
        assertEquals(null, controller.state.value)
    }

    @Test
    fun `jumping plays that entry and keeps everything before it`() = runTest(dispatcher) {
        val q = queue()
        advanceUntilIdle()
        listOf("a", "b", "c").forEach { q.enqueue(podcast(it)) }

        q.jumpTo(1)
        advanceUntilIdle()

        assertEquals("b", controller.state.value?.itemId?.value)
        // "a" survives: jumping is navigation, not consumption.
        assertEquals(listOf("a", "b", "c"), q.state.value.entries.map { it.item.item.id.value })
        assertEquals(listOf("c"), q.state.value.upNext.map { it.item.item.id.value })
    }

    @Test
    fun `the queue is saved on change and hydrated back`() = runTest(dispatcher) {
        val first = queue()
        advanceUntilIdle() // let hydration of the (empty) store settle
        first.enqueue(podcast("a"))
        first.enqueue(podcast("b"))
        advanceUntilIdle()

        // A fresh queue over the same store comes back with the same entries.
        val restored = queue()
        advanceUntilIdle()
        assertEquals(listOf("a", "b"), restored.state.value.upNext.map { it.item.item.id.value })
    }

    @Test
    fun `hydration does not wipe a saved queue`() = runTest(dispatcher) {
        val saved = InMemoryQueueStore(QueueSnapshot(listOf(QueueEntry(podcast("kept")))))

        val q = queue(saved)
        advanceUntilIdle()

        assertEquals(listOf("kept"), q.state.value.upNext.map { it.item.item.id.value })
        assertEquals(listOf("kept"), saved.load().entries.map { it.item.item.id.value })
    }

    @Test
    fun `playAll tags its entries with the group so they can be dropped together`() = runTest(dispatcher) {
        val q = queue()
        advanceUntilIdle()
        val group = QueueGroup("pl-1", "Mix")

        q.playAll(listOf(podcast("a"), podcast("b"), podcast("c")), group)
        advanceUntilIdle()

        // The first plays now; the rest are queued, all tagged.
        assertEquals(listOf("b", "c"), q.state.value.upNext.map { it.item.item.id.value })
        assertTrue(q.state.value.upNext.all { it.group == group })

        q.removeGroup("pl-1")
        assertTrue(q.state.value.upNext.isEmpty())
    }

    @Test
    fun `removeGroup leaves ungrouped entries and other groups alone`() = runTest(dispatcher) {
        val q = queue()
        advanceUntilIdle()
        q.enqueue(podcast("loose"))
        q.enqueue(podcast("a"), QueueGroup("g1", "One"))
        q.enqueue(podcast("b"), QueueGroup("g2", "Two"))

        q.removeGroup("g1")

        assertEquals(listOf("loose", "b"), q.state.value.upNext.map { it.item.item.id.value })
    }

    @Test
    fun `queueing during startup wins over the restored queue`() = runTest(dispatcher) {
        // Loading is suspending, so the user can act before it lands. Their action
        // must not be silently replaced by the saved queue.
        val saved = InMemoryQueueStore(QueueSnapshot(listOf(QueueEntry(podcast("old")))))
        val q = queue(saved)

        q.enqueue(podcast("just-added")) // before advanceUntilIdle, i.e. pre-hydration
        advanceUntilIdle()

        assertEquals(listOf("just-added"), q.state.value.upNext.map { it.item.item.id.value })
    }

    @Test
    fun `playNow joins the queue at the current position and keeps the rest`() = runTest(dispatcher) {
        val q = queue()
        advanceUntilIdle()
        q.enqueue(podcast("lined-up"))

        q.playNow(podcast("tapped"))
        advanceUntilIdle()

        assertEquals("tapped", controller.state.value?.itemId?.value)
        // The tapped item is a queue member now, and what was lined up follows it.
        assertEquals("tapped", q.state.value.current?.item?.item?.id?.value)
        assertEquals(listOf("lined-up"), q.state.value.upNext.map { it.item.item.id.value })
    }

    @Test
    fun `playNow on an already-queued item moves it rather than duplicating`() = runTest(dispatcher) {
        val q = queue()
        advanceUntilIdle()
        q.enqueue(podcast("a"))
        q.enqueue(podcast("b"))

        q.playNow(podcast("b"))
        advanceUntilIdle()

        assertEquals("b", controller.state.value?.itemId?.value)
        assertEquals(listOf("a"), q.state.value.upNext.map { it.item.item.id.value })
    }

    @Test
    fun `peek plays without joining the queue`() = runTest(dispatcher) {
        val q = queue()
        advanceUntilIdle()
        q.enqueue(podcast("a"))
        q.enqueue(podcast("b"))

        q.peek(podcast("one-off"))
        advanceUntilIdle()

        assertEquals("one-off", controller.state.value?.itemId?.value)
        // Untouched queue, and the peeked item is not a member of it.
        assertEquals(listOf("a", "b"), q.state.value.entries.map { it.item.item.id.value })
        assertEquals(QueueSnapshot.NOTHING_PLAYING, q.state.value.currentIndex)
        assertEquals(listOf("a", "b"), q.state.value.upNext.map { it.item.item.id.value })
    }

    @Test
    fun `playAll inserts its run ahead of the existing queue instead of replacing it`() = runTest(dispatcher) {
        val q = queue()
        advanceUntilIdle()
        q.enqueue(podcast("mine"))

        q.playAll(listOf(podcast("x"), podcast("y")), QueueGroup("pl", "Mix"))
        advanceUntilIdle()

        // x plays now; y is queued ahead of what was already there, and "mine" survives.
        assertEquals("x", controller.state.value?.itemId?.value)
        assertEquals(listOf("y", "mine"), q.state.value.upNext.map { it.item.item.id.value })
    }

    /**
     * A real report (0.1.225): play-now fired seventeen times in twelve seconds, about every
     * 170ms, alternating between two videos. Each one resolves, and a resolve costs 10-20s
     * with the JS runtime — so one tap became minutes of duplicated extraction.
     */
    @Test
    fun `a storm of identical play-now calls plays once`() = runTest(dispatcher) {
        val q = queue()
        val item = podcast("a")

        repeat(10) {
            q.playNow(item)
            nowMs += 170
            testScheduler.advanceUntilIdle()
        }

        assertEquals(1, q.state.value.entries.size)
        assertEquals(1, controller.played.size)
    }

    @Test
    fun `the same video played again later is honoured, not swallowed`() = runTest(dispatcher) {
        val q = queue()
        val item = podcast("a")

        q.playNow(item)
        testScheduler.advanceUntilIdle()
        nowMs += 5_000
        q.playNow(item)
        testScheduler.advanceUntilIdle()

        assertEquals(2, controller.played.size)
    }

    @Test
    fun `two different videos in quick succession both play`() = runTest(dispatcher) {
        val q = queue()

        q.playNow(podcast("a"))
        nowMs += 50
        testScheduler.advanceUntilIdle()
        q.playNow(podcast("b"))
        testScheduler.advanceUntilIdle()

        assertEquals(2, controller.played.size)
    }

    /**
     * Recovery must get a FRESH stream, so replaying has to drop the cached resolution first.
     *
     * This is the wiring, and the wiring is what broke. Report 0.1.277: a video died nine
     * minutes in, recovery replayed it three times over twenty seconds, and every attempt logged
     * "cache hit … skipped extraction" against the same dead URL before the video was skipped as
     * unplayable.
     *
     * Testing `VideoResolver.forget` alone does NOT cover this — it proves the method works, not
     * that anything calls it, and deleting the one line in `replayCurrent` would leave those
     * tests green while the bug returned. That is the same component-correct/composition-wrong
     * shape that let three autoplay bugs ship, so it is asserted here where the two meet.
     */
    @Test
    fun `replaying after a failure re-resolves instead of reusing the cached URL`() = runTest(dispatcher) {
        val extractions = AtomicInteger()
        val counting = VideoResolver(CountingEngine(extractions), SkipSegmentSource { emptyList() })
        val queue = PlaybackQueue(
            controller,
            VideoPlaybackLauncher(counting, controller, FakeYouTubeWatchHistory(), InMemoryPlayHistoryStore()),
            backgroundScope,
            store,
        )
        queue.playNow(video("a"))
        advanceUntilIdle()
        assertEquals("the first play extracts once", 1, extractions.get())

        queue.replayCurrent(positionMs = 5_000)
        advanceUntilIdle()

        assertEquals(
            "recovery must re-extract, not serve the URL that just died",
            2,
            extractions.get(),
        )
    }

    /** A video item, since only those resolve — a podcast plays its enclosure directly. */
    private fun video(id: String) = PlayableItem(
        item = MediaItem(
            id = MediaItemId(id),
            sourceId = SourceId("s"),
            title = id,
            publishedAt = null,
            duration = null,
        ),
        handle = PlayHandle.Video(HttpUrl.of("https://www.youtube.com/watch?v=dQw4w9WgXcQ")),
    )

    /** Counts extractions so "did it really re-resolve?" is answerable. */
    private class CountingEngine(private val calls: AtomicInteger) : YtDlpEngine by FakeYtDlpEngine() {
        override suspend fun extract(url: HttpUrl): ExtractionResult {
            calls.incrementAndGet()
            return ExtractionResult.Success(
                MediaMetadata(
                    id = "dQw4w9WgXcQ",
                    title = "A video",
                    uploader = null,
                    durationSeconds = 10,
                    thumbnailUrl = null,
                    formats = listOf(
                        MediaFormat("18", "mp4", 640, 360, true, true, null, "https://x.test/v", "avc1", "mp4a"),
                    ),
                ),
            )
        }
    }
}
