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
    fun `listening to a paused stream whose audio finishes downloading moves to the file at the same place`() =
        runTest(dispatcher) {
            val queue = queue(listening = true)
            queue.playAll(listOf(video()))
            advanceUntilIdle()
            assertNull("it starts on the stream", controller.lastLocalPath)
            controller.seekTo(2_000_000)
            controller.setPlaying(false)
            onDisk[MediaItemId(VIDEO_ID)] = LocalCopy(path = COPY, audioOnly = true)

            val handed = queue.handOverToTheDownload(MediaItemId(VIDEO_ID))
            advanceUntilIdle()

            assertTrue(handed)
            assertEquals(COPY, controller.lastLocalPath)
            assertEquals(2_000_000L, controller.lastStartPositionMs)
        }

    @Test
    fun `a download that lands while the stream plays steadily does not rebuild the player`() =
        runTest(dispatcher) {
            val queue = queue(listening = true)
            queue.playAll(listOf(video()))
            advanceUntilIdle()
            controller.seekTo(7_904)
            onDisk[MediaItemId(VIDEO_ID)] = LocalCopy(path = COPY, audioOnly = true)

            val handed = queue.handOverToTheDownload(MediaItemId(VIDEO_ID))
            advanceUntilIdle()

            assertFalse(handed)
            assertNull("the stream is left alone", controller.lastLocalPath)
            assertEquals("one play only", 1, controller.played.size)
        }

    @Test
    fun `a deferred switch happens at the next pause, at the place it was paused`() = runTest(dispatcher) {
        val queue = queue(listening = true)
        queue.watchForHandOverBreaks()
        queue.playAll(listOf(video()))
        advanceUntilIdle()
        controller.seekTo(7_904)
        advanceUntilIdle()
        onDisk[MediaItemId(VIDEO_ID)] = LocalCopy(path = COPY, audioOnly = true)
        queue.handOverToTheDownload(MediaItemId(VIDEO_ID))
        advanceUntilIdle()
        assertEquals(1, controller.played.size)

        controller.seekTo(9_100)
        controller.setPlaying(false)
        advanceUntilIdle()

        assertEquals(COPY, controller.lastLocalPath)
        assertEquals(9_100L, controller.lastStartPositionMs)
        assertEquals(2, controller.played.size)
    }

    @Test
    fun `a deferred switch happens when the stream stalls`() = runTest(dispatcher) {
        val queue = queue(listening = true)
        queue.watchForHandOverBreaks()
        queue.playAll(listOf(video()))
        advanceUntilIdle()
        controller.seekTo(7_904)
        advanceUntilIdle()
        onDisk[MediaItemId(VIDEO_ID)] = LocalCopy(path = COPY, audioOnly = true)
        queue.handOverToTheDownload(MediaItemId(VIDEO_ID))
        advanceUntilIdle()

        controller.emitState(controller.state.value?.copy(isBuffering = true, isPlaying = false))
        advanceUntilIdle()

        assertEquals(COPY, controller.lastLocalPath)
        assertEquals(2, controller.played.size)
    }

    @Test
    fun `a deferred switch happens when the position jumps, as a seek does`() = runTest(dispatcher) {
        val queue = queue(listening = true)
        queue.watchForHandOverBreaks()
        queue.playAll(listOf(video()))
        advanceUntilIdle()
        controller.seekTo(7_904)
        advanceUntilIdle()
        onDisk[MediaItemId(VIDEO_ID)] = LocalCopy(path = COPY, audioOnly = true)
        queue.handOverToTheDownload(MediaItemId(VIDEO_ID))
        advanceUntilIdle()

        controller.seekTo(120_000)
        advanceUntilIdle()

        assertEquals(COPY, controller.lastLocalPath)
        assertEquals(120_000L, controller.lastStartPositionMs)
    }

    @Test
    fun `starting the item again takes the copy by itself and leaves nothing waiting`() = runTest(dispatcher) {
        val queue = queue(listening = true)
        queue.watchForHandOverBreaks()
        queue.playAll(listOf(video()))
        advanceUntilIdle()
        controller.seekTo(7_904)
        advanceUntilIdle()
        onDisk[MediaItemId(VIDEO_ID)] = LocalCopy(path = COPY, audioOnly = true)
        queue.handOverToTheDownload(MediaItemId(VIDEO_ID))
        queue.playAll(listOf(video()))
        advanceUntilIdle()
        val playsAfterRestart = controller.played.size

        controller.setPlaying(false)
        advanceUntilIdle()

        assertEquals(COPY, controller.lastLocalPath)
        assertEquals("the pause swaps nothing more", playsAfterRestart, controller.played.size)
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
