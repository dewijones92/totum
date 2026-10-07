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
class ADeadStreamWithACopyPlaysTheCopyAtOnceTest {

    private val dispatcher = StandardTestDispatcher()
    private val controller = FakePlaybackController()
    private val onDisk = mutableMapOf<MediaItemId, LocalCopy>()
    private val refreshed = mutableListOf<MediaItemId>()

    private fun queue(listening: Boolean) = PlaybackQueue(
        controller = controller,
        launcher = VideoPlaybackLauncher(
            VideoResolver(FakeYtDlpEngine(), SkipSegmentSource { emptyList() }),
            controller,
            FakeYouTubeWatchHistory(),
            InMemoryPlayHistoryStore(),
        ),
        scope = CoroutineScope(dispatcher),
        audioPreferred = { listening },
        localCopy = { id -> onDisk[id] },
        refresh = { refreshed += it.item.id },
    )

    @Test
    fun `a replay that will play the copy on disk does not extract a fresh stream first`() = runTest(dispatcher) {
        val queue = queue(listening = true)
        queue.playAll(listOf(video()))
        advanceUntilIdle()
        onDisk[MediaItemId(VIDEO_ID)] = LocalCopy(path = COPY, audioOnly = true)

        val replayed = queue.replayCurrent(positionMs = 1_888_672)
        advanceUntilIdle()

        assertEquals(true, replayed)
        assertEquals("the copy plays without a fresh stream being fetched", emptyList<MediaItemId>(), refreshed)
        assertEquals(COPY, controller.lastLocalPath)
        assertEquals(1_888_672L, controller.lastStartPositionMs)
    }

    @Test
    fun `an audio-only copy does not stand in for a video being watched, so that replay still refreshes`() =
        runTest(dispatcher) {
            val queue = queue(listening = false)
            queue.playAll(listOf(video()))
            advanceUntilIdle()
            onDisk[MediaItemId(VIDEO_ID)] = LocalCopy(path = COPY, audioOnly = true)

            queue.replayCurrent(positionMs = 30_000)
            advanceUntilIdle()

            assertEquals(listOf(MediaItemId(VIDEO_ID)), refreshed)
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
