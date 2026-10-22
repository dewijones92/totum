package com.dewijones92.totum.queue

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
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

@OptIn(ExperimentalCoroutinesApi::class)
class RepeatGroupTest {

    private val dispatcher = StandardTestDispatcher()
    private val controller = FakePlaybackController()
    private val album = QueueGroup("album:a", "An album")
    private val queue = PlaybackQueue(
        controller = controller,
        launcher = VideoPlaybackLauncher(
            VideoResolver(FakeYtDlpEngine(), SkipSegmentSource { emptyList() }),
            controller,
            FakeYouTubeWatchHistory(),
            InMemoryPlayHistoryStore(),
        ),
        scope = CoroutineScope(dispatcher),
    )

    private fun track(id: String) = PlayableItem(
        MediaItem(
            id = MediaItemId(id),
            sourceId = SourceId("album"),
            title = id,
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://feed.test/$id.mp3"),
        ),
        PlayHandle.Podcast(),
    )

    private fun playing() = queue.state.value.current?.item?.item?.id?.value

    @Test
    fun `the group's last item ending goes back to the group's first`() = runTest(dispatcher) {
        queue.playAll(listOf("a1", "a2").map(::track), album)
        queue.enqueueAll(listOf(track("after")))
        advanceUntilIdle()
        queue.playNextInQueue()
        advanceUntilIdle()

        val restarted = queue.repeatGroupAfter(MediaItemId("a2"))
        advanceUntilIdle()

        assertEquals(true to "a1", restarted to playing())
    }

    @Test
    fun `mid-group, or outside any group, the queue carries on as usual`() = runTest(dispatcher) {
        queue.playAll(listOf("a1", "a2").map(::track), album)
        queue.enqueueAll(listOf(track("after")))
        advanceUntilIdle()

        assertFalse(queue.repeatGroupAfter(MediaItemId("a1")))
        assertFalse(queue.repeatGroupAfter(MediaItemId("after")))
    }
}
