package com.dewijones92.totum.queue

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.domain.LocalCopy
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.innertube.history.fake.FakeYouTubeWatchHistory
import com.dewijones92.totum.playback.fake.FakePlaybackController
import com.dewijones92.totum.video.VideoPlaybackLauncher
import com.dewijones92.totum.video.VideoResolver
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * An advance that plays NOTHING must leave the cursor where it was.
 *
 * `playAt` moved the cursor before attempting the play and nothing rolled it back on refusal, and the
 * advance loop walks every remaining entry. So one failed advance over a mostly-unstreamed queue parked
 * the cursor on the LAST entry — and because `mutate` is what triggers `store.save`, the parked cursor
 * was persisted. After that `upNext` is empty, every later advance logs "nothing after cursor N", and a
 * 97-item queue reads as finished even once the network comes back. Restarting the app does not help,
 * which is what makes this worse than a transient glitch.
 *
 * Offline is the ordinary trigger: `routeNow` refuses every entry with no copy on disk, so a single
 * auto-advance walks the whole queue in one go.
 *
 * Two assertions, because the first alone is satisfied by code that is still broken: the call must
 * report failure AND the cursor must be untouched. `PlaybackQueueTest`'s existing failed-advance cases
 * are one-bad-then-good and empty-queue — neither looks at the cursor afterwards.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AFailedAdvanceLeavesTheCursorAloneTest {

    private val dispatcher = StandardTestDispatcher()
    private val controller = FakePlaybackController()
    private val onDisk = mutableMapOf<MediaItemId, LocalCopy>()

    private fun queue(offline: Boolean) = PlaybackQueue(
        controller = controller,
        launcher = VideoPlaybackLauncher(
            VideoResolver(FakeYtDlpEngine(), SkipSegmentSource { emptyList() }),
            controller,
            FakeYouTubeWatchHistory(),
            InMemoryPlayHistoryStore(),
        ),
        scope = CoroutineScope(dispatcher),
        offline = { offline },
        localCopy = { id -> onDisk[id] },
    )

    private fun video(id: String) = PlayableItem(
        item = MediaItem(
            id = MediaItemId(id),
            sourceId = SourceId("youtube"),
            title = "video $id",
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://www.youtube.com/watch?v=$id"),
        ),
        handle = PlayHandle.Video(HttpUrl.of("https://www.youtube.com/watch?v=$id")),
    )

    /**
     * THE case: offline, playing a downloaded item, with three stream-only items behind it.
     *
     * The advance must fail and change nothing. A queue that walks itself to the end and saves that is
     * how eighty items become "finished" on a plane.
     */
    @Test
    fun `offline, an advance that plays nothing leaves the cursor and nowPlaying untouched`() = runTest {
        val queue = queue(offline = true)
        val downloaded = video("downloaded")
        onDisk[downloaded.item.id] = LocalCopy("/sdcard/downloaded.m4a", audioOnly = true)
        queue.enqueue(downloaded)
        listOf("stream-a", "stream-b", "stream-c").forEach { queue.enqueue(video(it)) }
        advanceUntilIdle()
        queue.playNow(downloaded)
        advanceUntilIdle()

        val cursorBefore = queue.state.value.currentIndex
        val playingBefore = queue.nowPlaying.value?.item?.id?.value

        val advanced = queue.playNextInQueue()
        advanceUntilIdle()

        assertFalse("nothing behind it can play offline, so the advance must fail", advanced)
        assertEquals(
            "the cursor must not have walked to the end of the queue — that state gets SAVED",
            cursorBefore,
            queue.state.value.currentIndex,
        )
        assertEquals(
            "and what is playing must be untouched",
            playingBefore,
            queue.nowPlaying.value?.item?.id?.value,
        )
    }

    /**
     * And a PARTIALLY failing advance still lands on the item that works — otherwise a rollback that
     * was too eager would silently stop the queue advancing at all, which is worse than the bug.
     */
    @Test
    fun `an advance still moves to the first item that does play`() = runTest {
        val queue = queue(offline = true)
        val first = video("downloaded-first")
        val playable = video("downloaded-second")
        onDisk[first.item.id] = LocalCopy("/sdcard/one.m4a", audioOnly = true)
        onDisk[playable.item.id] = LocalCopy("/sdcard/two.m4a", audioOnly = true)
        queue.enqueue(first)
        queue.enqueue(video("stream-only"))
        queue.enqueue(playable)
        advanceUntilIdle()
        queue.playNow(first)
        advanceUntilIdle()

        val advanced = queue.playNextInQueue()
        advanceUntilIdle()

        assertEquals("it should have reached the downloaded item behind the unplayable one", true, advanced)
        assertEquals(
            playable.item.id.value,
            queue.nowPlaying.value?.item?.id?.value,
        )
    }
}
