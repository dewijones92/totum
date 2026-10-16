package com.dewijones92.totum.queue

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.domain.Listening
import com.dewijones92.totum.domain.MediaContentKind
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutoPlayStaysInMusicOrTalkTest {

    private val dispatcher = StandardTestDispatcher()
    private val controller = FakePlaybackController()

    private fun item(id: String, music: Boolean) = PlayableItem(
        MediaItem(
            id = MediaItemId(id),
            sourceId = SourceId("s"),
            title = id,
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://feed.test/$id.mp3"),
            contentKind = if (music) MediaContentKind.MUSIC else MediaContentKind.STANDARD,
        ),
        PlayHandle.Podcast(),
    )

    private fun TestScope.queueOf(vararg items: PlayableItem): Pair<PlaybackQueue, CoroutineScope> {
        val work = CoroutineScope(coroutineContext + SupervisorJob())
        val queue = PlaybackQueue(
            controller = controller,
            launcher = VideoPlaybackLauncher(
                VideoResolver(FakeYtDlpEngine(), SkipSegmentSource { emptyList() }),
                controller,
                FakeYouTubeWatchHistory(),
                InMemoryPlayHistoryStore(),
            ),
            scope = work,
        )
        queue.playAll(items.toList())
        return queue to work
    }

    private fun PlaybackQueue.playing() = nowPlaying.value?.item?.id?.value

    @Test
    fun `talk carries on to the next talk, passing over the music and leaving it queued`() = runTest(dispatcher) {
        val (queue, work) = queueOf(item("peep1", false), item("song", true), item("novara", false))
        advanceUntilIdle()

        assertEquals("novara", queue.peekNext()?.item?.id?.value)
        assertTrue(queue.continueInKind())
        advanceUntilIdle()

        assertEquals("novara", queue.playing())
        assertTrue("the song left the queue", queue.state.value.entries.any { it.item.item.id.value == "song" })
        work.cancel()
    }

    @Test
    fun `music carries on to the next music, passing over the talk`() = runTest(dispatcher) {
        val (queue, work) = queueOf(item("song1", true), item("peep", false), item("song2", true))
        advanceUntilIdle()

        assertTrue(queue.continueInKind())
        advanceUntilIdle()

        assertEquals("song2", queue.playing())
        work.cancel()
    }

    @Test
    fun `when only the other kind is left it says so and plays nothing`() = runTest(dispatcher) {
        val (queue, work) = queueOf(item("peep", false), item("song1", true), item("song2", true))
        advanceUntilIdle()

        assertNull(queue.peekNext())
        assertFalse(queue.continueInKind())
        advanceUntilIdle()

        assertEquals("peep", queue.playing())
        assertEquals(Listening.MUSIC to 2, queue.onlyOtherKindAhead())
        work.cancel()
    }

    @Test
    fun `the skip button still plays whatever is next`() = runTest(dispatcher) {
        val (queue, work) = queueOf(item("peep", false), item("song", true))
        advanceUntilIdle()

        assertTrue(queue.playNextInQueue())
        advanceUntilIdle()

        assertEquals("song", queue.playing())
        work.cancel()
    }
}
