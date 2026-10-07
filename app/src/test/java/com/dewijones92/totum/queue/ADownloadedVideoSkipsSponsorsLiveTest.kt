package com.dewijones92.totum.queue

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.domain.LocalCopy
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SkipSegment
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
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ADownloadedVideoSkipsSponsorsLiveTest {

    private val dispatcher = StandardTestDispatcher()
    private val controller = FakePlaybackController()
    private val stored = listOf(SkipSegment(10.seconds, 30.seconds))
    private val newer = listOf(SkipSegment(10.seconds, 30.seconds), SkipSegment(90.seconds, 120.seconds))

    private fun queue(audioPreferred: Boolean, copy: LocalCopy, refreshed: List<SkipSegment>?) = PlaybackQueue(
        controller = controller,
        launcher = VideoPlaybackLauncher(
            VideoResolver(FakeYtDlpEngine(), SkipSegmentSource { emptyList() }),
            controller,
            FakeYouTubeWatchHistory(),
            InMemoryPlayHistoryStore(),
        ),
        scope = CoroutineScope(dispatcher),
        audioPreferred = { audioPreferred },
        localCopy = { id -> copy.takeIf { id == VIDEO } },
        refreshSkipsOnDisk = { refreshed },
    )

    @Test
    fun aFullDownloadSkipsItsStoredSegmentsAndPicksUpNewerOnes() = runTest(dispatcher) {
        val queue = queue(audioPreferred = false, LocalCopy("/downloads/v.mkv", skipSegments = stored), newer)
        queue.peek(video())
        dispatcher.scheduler.runCurrent()
        assertEquals("/downloads/v.mkv", controller.lastLocalPath)
        advanceUntilIdle()
        assertEquals(newer, controller.lastSkipSegments)
    }

    @Test
    fun anAudioCopySkipsItsStoredSegments() = runTest(dispatcher) {
        queue(audioPreferred = true, LocalCopy("/downloads/v.m4a", audioOnly = true, stored), refreshed = null)
            .peek(video())
        advanceUntilIdle()
        assertEquals("/downloads/v.m4a", controller.lastLocalPath)
        assertEquals(stored, controller.lastSkipSegments)
    }

    private fun video() = PlayableItem(
        item = MediaItem(VIDEO, SourceId("youtube"), "A talk", publishedAt = null, duration = null, mediaUrl = WATCH),
        handle = PlayHandle.Video(WATCH),
    )

    private companion object {
        val VIDEO = MediaItemId("YZAtVucNu8c")
        val WATCH = HttpUrl.of("https://www.youtube.com/watch?v=YZAtVucNu8c")
    }
}
