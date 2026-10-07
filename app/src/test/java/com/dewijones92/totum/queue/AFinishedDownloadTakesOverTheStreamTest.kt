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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AFinishedDownloadTakesOverTheStreamTest {

    private val dispatcher = StandardTestDispatcher()
    private val controller = FakePlaybackController()
    private val onDisk = mutableMapOf<MediaItemId, LocalCopy>()
    private val engine = FakeYtDlpEngine().apply {
        registerMedia(WATCH_URL, FakeYtDlpEngine.sampleMetadata(VIDEO_ID))
    }

    private fun queue(listening: Boolean) = PlaybackQueue(
        controller = controller,
        launcher = VideoPlaybackLauncher(
            VideoResolver(engine, SkipSegmentSource { emptyList() }),
            controller,
            FakeYouTubeWatchHistory(),
            InMemoryPlayHistoryStore(),
            audioPreferred = { listening },
        ),
        scope = CoroutineScope(dispatcher),
        audioPreferred = { listening },
        localCopy = { id -> onDisk[id] },
    )

    @Test
    fun `listening to a stream whose audio finishes downloading moves to the file at the same place`() =
        runTest(dispatcher) {
            val queue = queue(listening = true)
            queue.playAll(listOf(video()))
            advanceUntilIdle()
            assertNull("it starts on the stream", controller.lastLocalPath)
            controller.seekTo(2_000_000)
            onDisk[MediaItemId(VIDEO_ID)] = LocalCopy(path = COPY, audioOnly = true)

            val handed = queue.handOverToTheDownload(MediaItemId(VIDEO_ID))
            advanceUntilIdle()

            assertTrue(handed)
            assertEquals(COPY, controller.lastLocalPath)
            assertEquals(2_000_000L, controller.lastStartPositionMs)
        }

    @Test
    fun `watching, an audio-only download does not take the picture away`() = runTest(dispatcher) {
        val queue = queue(listening = false)
        queue.playAll(listOf(video()))
        advanceUntilIdle()
        onDisk[MediaItemId(VIDEO_ID)] = LocalCopy(path = COPY, audioOnly = true)

        assertFalse(queue.handOverToTheDownload(MediaItemId(VIDEO_ID)))
        advanceUntilIdle()
        assertNull(controller.lastLocalPath)
    }

    @Test
    fun `a download of something else changes nothing`() = runTest(dispatcher) {
        val queue = queue(listening = true)
        queue.playAll(listOf(video()))
        advanceUntilIdle()

        assertFalse(queue.handOverToTheDownload(MediaItemId("someOtherId")))
    }

    private fun video() = PlayableItem(
        item = MediaItem(
            id = MediaItemId(VIDEO_ID),
            sourceId = SourceId("youtube"),
            title = "Riot Police Clash With Protesters",
            publishedAt = null,
            duration = null,
            mediaUrl = WATCH_URL,
        ),
        handle = PlayHandle.Video(WATCH_URL),
    )

    private companion object {
        const val VIDEO_ID = "edon5wb5Qsc"
        const val COPY = "/downloads/3683740494.media"
        val WATCH_URL = HttpUrl.of("https://www.youtube.com/watch?v=$VIDEO_ID")
    }
}
