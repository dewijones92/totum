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
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AnAudioCopyReadiesItsPictureTest {

    private val dispatcher = StandardTestDispatcher()
    private val controller = FakePlaybackController()
    private val onDisk = mutableMapOf<MediaItemId, LocalCopy>()
    private val readied = mutableListOf<MediaItemId>()

    private fun queue(audioPreferred: Boolean = true) = PlaybackQueue(
        controller = controller,
        launcher = VideoPlaybackLauncher(
            VideoResolver(FakeYtDlpEngine(), SkipSegmentSource { emptyList() }),
            controller,
            FakeYouTubeWatchHistory(),
            InMemoryPlayHistoryStore(),
        ),
        scope = CoroutineScope(dispatcher),
        audioPreferred = { audioPreferred },
        localCopy = { id -> onDisk[id] },
        readyThePicture = { readied += it.item.id },
    )

    @Test
    fun aVideoPlayingFromItsDownloadedAudioGetsItsPictureReadied() = runTest(dispatcher) {
        onDisk[VIDEO] = LocalCopy(path = "/downloads/v.media", audioOnly = true)

        queue().peek(youTubeVideo())
        advanceUntilIdle()

        assertEquals(listOf(VIDEO), readied)
    }

    @Test
    fun aVideoPlayedFromTheLibraryAsItsFileGetsItsPictureReadied() = runTest(dispatcher) {
        queue().peek(youTubeVideo().copy(handle = PlayHandle.Podcast("/downloads/v.m4a")))
        advanceUntilIdle()

        assertEquals(listOf(VIDEO), readied)
    }

    @Test
    fun aPodcastEpisodeHasNoPictureToReady() = runTest(dispatcher) {
        onDisk[EPISODE] = LocalCopy(path = "/downloads/e.mp3", audioOnly = true)

        queue().peek(podcastEpisode())
        advanceUntilIdle()

        assertEquals(emptyList<MediaItemId>(), readied)
    }

    @Test
    fun aFullDownloadAlreadyHasItsPicture() = runTest(dispatcher) {
        onDisk[VIDEO] = LocalCopy(path = "/downloads/v.mkv", audioOnly = false)

        queue(audioPreferred = false).peek(youTubeVideo())
        advanceUntilIdle()

        assertEquals(emptyList<MediaItemId>(), readied)
    }

    @Test
    fun aStreamedVideoIsResolvedByItsOwnPlayNotByThis() = runTest(dispatcher) {
        queue().peek(youTubeVideo())
        advanceUntilIdle()

        assertEquals(emptyList<MediaItemId>(), readied)
    }

    private fun youTubeVideo() = PlayableItem(
        item = MediaItem(VIDEO, SourceId("youtube"), "A talk", publishedAt = null, duration = null, mediaUrl = WATCH),
        handle = PlayHandle.Video(WATCH),
    )

    private fun podcastEpisode() = PlayableItem(
        item = MediaItem(
            EPISODE,
            SourceId("feed"),
            "An episode",
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://example.test/ep1.mp3"),
        ),
        handle = PlayHandle.Podcast(),
    )

    private companion object {
        val VIDEO = MediaItemId("YZAtVucNu8c")
        val EPISODE = MediaItemId("ep1")
        val WATCH = HttpUrl.of("https://www.youtube.com/watch?v=YZAtVucNu8c")
    }
}
