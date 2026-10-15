package com.dewijones92.totum.uploads

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.innertube.history.fake.FakeYouTubeWatchHistory
import com.dewijones92.totum.pins.Pin
import com.dewijones92.totum.pins.PinnedSource
import com.dewijones92.totum.playback.fake.FakePlaybackController
import com.dewijones92.totum.queue.PlaybackQueue
import com.dewijones92.totum.video.VideoPlaybackLauncher
import com.dewijones92.totum.video.VideoResolver
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class NewUploadsOverTheQueueTest {

    private val dispatcher = StandardTestDispatcher()
    private val controller = FakePlaybackController()
    private val channel = "https://www.youtube.com/channel/UCpeepshowpeepshowpeepsh"
    private val peep = MediaSource.VideoChannel(SourceId(channel), "Peep Show", HttpUrl.of(channel))
    private val group = QueueGroup("shuffle:peep", "Peep Show")

    private fun video(id: String, day: Long = 1) = PlayableItem(
        MediaItem(
            MediaItemId(id),
            peep.id,
            "clip $id",
            publishedAt = Instant.ofEpochSecond(day * 86_400),
            duration = null,
            mediaUrl = HttpUrl.of("https://www.youtube.com/watch?v=$id"),
        ),
        PlayHandle.Video(HttpUrl.of("https://www.youtube.com/watch?v=$id")),
    )

    @Test
    fun `a new upload lands at the head of the real queue's group, after the saved queue has loaded`() =
        runTest(dispatcher) {
            val queue = PlaybackQueue(
                controller = controller,
                launcher = VideoPlaybackLauncher(
                    VideoResolver(FakeYtDlpEngine(), SkipSegmentSource { emptyList() }),
                    controller,
                    FakeYouTubeWatchHistory(),
                    InMemoryPlayHistoryStore(),
                ),
                scope = CoroutineScope(dispatcher),
            )
            advanceUntilIdle()
            queue.enqueueAll(listOf(video("a"), video("b")))
            queue.enqueueAll(listOf(video("p1"), video("p2")), group)
            var saved = UploadsState()
            val uploads = NewUploads(
                latest = { listOf(video("p1").item, video("new", day = 9).item) },
                queue = QueueForUploads(queue),
                inboxSources = { InboxSources(emptyList(), complete = true) },
                store = object : UploadsStore {
                    override suspend fun load() = saved
                    override suspend fun save(state: UploadsState) {
                        saved = state
                    }
                },
            )
            val shuffle = Pin.ShuffleAllOf(
                "peep",
                "Peep Show",
                null,
                listOf(PinnedSource.of(peep)),
                includeShorts = false
            )
            uploads.remember(group.id, shuffle, listOf(video("p1").item, video("p2").item))

            val report = uploads.check("test")

            assertEquals(1, report.added)
            assertEquals(listOf("a", "b", "new", "p1", "p2"), queue.state.value.entries.map { it.item.item.id.value })
            assertEquals(group, queue.state.value.entries[2].group)
        }
}
