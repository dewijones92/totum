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
import com.dewijones92.totum.playback.SkipDirection
import com.dewijones92.totum.playback.fake.FakePlaybackController
import com.dewijones92.totum.video.VideoPlaybackLauncher
import com.dewijones92.totum.video.VideoResolver
import com.dewijones92.totum.ytdlp.ExtractionResult
import com.dewijones92.totum.ytdlp.MediaFormat
import com.dewijones92.totum.ytdlp.MediaMetadata
import com.dewijones92.totum.ytdlp.YtDlpEngine
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RapidSkipsLandOnTheLastPressTest {

    private val dispatcher = StandardTestDispatcher()
    private val controller = FakePlaybackController()

    private class SlowEngine : YtDlpEngine by FakeYtDlpEngine() {
        override suspend fun extract(url: HttpUrl): ExtractionResult {
            delay(EXTRACT_MS)
            val id = url.value.substringAfter("v=")
            return ExtractionResult.Success(
                MediaMetadata(
                    id = id,
                    title = "video $id",
                    uploader = null,
                    durationSeconds = 600,
                    thumbnailUrl = null,
                    formats = listOf(
                        MediaFormat(
                            "18", "mp4", 640, 360, true, true, null, "https://cdn.example/$id.mp4", "avc1", "mp4a",
                        ),
                    ),
                ),
            )
        }
    }

    private fun video(id: String) = PlayableItem(
        MediaItem(
            id = MediaItemId(id),
            sourceId = SourceId("youtube"),
            title = "video $id",
            publishedAt = null,
            duration = null,
            mediaUrl = null,
        ),
        PlayHandle.Video(HttpUrl.of("https://www.youtube.com/watch?v=$id")),
    )

    @Test
    fun `three quick skips from the media session move straight to the third item`() = runTest(dispatcher) {
        val scope = CoroutineScope(dispatcher)
        val queue = PlaybackQueue(
            controller = controller,
            launcher = VideoPlaybackLauncher(
                VideoResolver(SlowEngine(), SkipSegmentSource { emptyList() }),
                controller,
                FakeYouTubeWatchHistory(),
                InMemoryPlayHistoryStore(),
            ),
            scope = scope,
            offline = { false },
        )
        val ids = listOf("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc", "ddddddddddd", "eeeeeeeeeee")
        queue.playAll(ids.map(::video))
        advanceUntilIdle()
        QueueSkips(scope, next = { queue.playNextInQueue() }, previous = { queue.playPreviousInQueue() })
            .follow(controller.skips)
        runCurrent()

        repeat(3) {
            controller.skips.emit(SkipDirection.NEXT)
            advanceTimeBy(PRESS_GAP_MS)
        }

        assertEquals("the cursor is on the last press at once", 3, queue.state.value.currentIndex)
        advanceUntilIdle()
        assertEquals(3, queue.state.value.currentIndex)
        assertEquals(MediaItemId("ddddddddddd"), controller.state.value?.itemId)
    }

    private companion object {
        const val EXTRACT_MS = 15_000L
        const val PRESS_GAP_MS = 300L
    }
}
