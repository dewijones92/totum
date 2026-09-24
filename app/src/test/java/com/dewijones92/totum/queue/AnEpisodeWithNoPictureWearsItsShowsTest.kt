package com.dewijones92.totum.queue

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
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
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AnEpisodeWithNoPictureWearsItsShowsTest {

    private val dispatcher = StandardTestDispatcher()
    private val controller = FakePlaybackController()
    private val feedId = SourceId("https://feeds.example.com/show.rss")
    private val showArt = HttpUrl.of("https://img.example.com/show.jpg")

    private fun queue() = PlaybackQueue(
        controller = controller,
        launcher = VideoPlaybackLauncher(
            VideoResolver(FakeYtDlpEngine(), SkipSegmentSource { emptyList() }),
            controller,
            FakeYouTubeWatchHistory(),
            InMemoryPlayHistoryStore(),
        ),
        scope = CoroutineScope(dispatcher),
        sourceArtwork = { mapOf(feedId to showArt) },
    )

    private fun episode(thumbnail: HttpUrl? = null) = PlayableItem(
        MediaItem(
            id = MediaItemId("ep-1"),
            sourceId = feedId,
            title = "Queued before the show had artwork",
            publishedAt = null,
            duration = null,
            thumbnailUrl = thumbnail,
            mediaUrl = HttpUrl.of("https://cdn.example.com/ep1.mp3"),
        ),
        PlayHandle.Podcast(),
    )

    @Test
    fun anEpisodeQueuedWithNoPicturePlaysWithTheShowsArtwork() = runTest(dispatcher) {
        queue().peek(episode())
        advanceUntilIdle()

        assertEquals(showArt.value, controller.state.value?.artworkUrl)
    }

    @Test
    fun anEpisodesOwnPictureStillWins() = runTest(dispatcher) {
        queue().peek(episode(thumbnail = HttpUrl.of("https://img.example.com/own.jpg")))
        advanceUntilIdle()

        assertEquals("https://img.example.com/own.jpg", controller.state.value?.artworkUrl)
    }
}
