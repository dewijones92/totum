package com.dewijones92.totum.queue

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.domain.LocalCopy
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.Refusal
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.innertube.history.fake.FakeYouTubeWatchHistory
import com.dewijones92.totum.playback.fake.FakePlaybackController
import com.dewijones92.totum.video.VideoPlaybackLauncher
import com.dewijones92.totum.video.VideoResolver
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ARefusedTapSaysSoTest {

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

    @Test
    fun `a tap that cannot play says which item and why`() = runTest {
        val queue = queue(offline = true)
        val refused = mutableListOf<RefusedTap>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { queue.refusedTaps.toList(refused) }
        advanceUntilIdle()

        queue.playNow(video("stream-only"))
        advanceUntilIdle()

        assertEquals(listOf(RefusedTap("video stream-only", Refusal.NotOnThisDevice)), refused)
        assertEquals("nothing is left showing as starting", null, queue.starting.value)
    }

    @Test
    fun `an automatic advance that cannot play stays quiet`() = runTest {
        val queue = queue(offline = true)
        val downloaded = video("downloaded")
        onDisk[downloaded.item.id] = LocalCopy("/sdcard/downloaded.m4a", audioOnly = true)
        queue.enqueue(downloaded)
        queue.enqueue(video("stream-a"))
        val refused = mutableListOf<RefusedTap>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { queue.refusedTaps.toList(refused) }
        advanceUntilIdle()
        queue.playNow(downloaded)
        advanceUntilIdle()

        queue.playNextInQueue()
        advanceUntilIdle()

        assertEquals(emptyList<RefusedTap>(), refused)
    }

    @Test
    fun `an automatic play like related-next stays quiet when it cannot start`() = runTest {
        val queue = queue(offline = true)
        val refused = mutableListOf<RefusedTap>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { queue.refusedTaps.toList(refused) }
        advanceUntilIdle()

        queue.playNow(video("related"), quiet = true)
        advanceUntilIdle()

        assertEquals(emptyList<RefusedTap>(), refused)
    }
}
