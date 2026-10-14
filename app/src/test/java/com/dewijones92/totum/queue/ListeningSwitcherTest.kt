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
import com.dewijones92.totum.pins.Pin
import com.dewijones92.totum.pins.RecentActions
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
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ListeningSwitcherTest {

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

    private class Setup(
        val queue: PlaybackQueue,
        val switcher: ListeningSwitcher,
        val played: List<Pin>,
        val work: CoroutineScope,
    )

    private fun TestScope.setUp(recent: List<Pin> = emptyList()): Setup {
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
        val played = mutableListOf<Pin>()
        val switcher = ListeningSwitcher(queue, controller, RecentActions(recent), { played += it }, work)
        switcher.start()
        return Setup(queue, switcher, played, work)
    }

    private fun Setup.playing() = queue.state.value.current?.item?.item?.id?.value

    @Test
    fun `talk to music and back to the same talk`() = runTest(dispatcher) {
        val s = setUp()
        s.queue.playAll(listOf(item("talk1", false), item("song1", true), item("song2", true)))
        advanceUntilIdle()
        assertEquals(Listening.MUSIC, s.switcher.target.value)
        assertEquals(Listening.MUSIC, controller.switchButton)

        s.switcher.tap("test")
        advanceUntilIdle()
        assertEquals("song1", s.playing())
        assertEquals(Listening.TALK, controller.switchButton)

        s.switcher.tap("test")
        advanceUntilIdle()
        assertEquals("talk1", s.playing())
        s.work.cancel()
    }

    @Test
    fun `the notification's button switches too`() = runTest(dispatcher) {
        val s = setUp()
        s.queue.playAll(listOf(item("talk1", false), item("song1", true)))
        advanceUntilIdle()

        controller.tapSwitchInNotification()
        advanceUntilIdle()

        assertEquals("song1", s.playing())
        s.work.cancel()
    }

    @Test
    fun `with no music queued it plays the newest music from Recently`() = runTest(dispatcher) {
        val show = Pin.Show("feed", "https://feed.test/rss", "A show", null)
        val album = Pin.Album("MPREb_x", "An album", null)
        val s = setUp(recent = listOf(show, album))
        s.queue.playAll(listOf(item("talk1", false)))
        advanceUntilIdle()

        s.switcher.tap("test")
        advanceUntilIdle()

        assertEquals(listOf<Pin>(album), s.played)
        s.work.cancel()
    }
}
