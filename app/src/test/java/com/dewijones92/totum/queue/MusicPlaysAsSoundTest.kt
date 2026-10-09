package com.dewijones92.totum.queue

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.innertube.history.fake.FakeYouTubeWatchHistory
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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MusicPlaysAsSoundTest {

    private val controller = FakePlaybackController()
    private val dispatcher = StandardTestDispatcher()

    private class SongWithAudioTrack : YtDlpEngine by FakeYtDlpEngine() {
        override suspend fun extract(url: HttpUrl): ExtractionResult = ExtractionResult.Success(
            MediaMetadata(
                id = url.value.substringAfter("v="),
                title = "a song",
                uploader = null,
                durationSeconds = 180,
                thumbnailUrl = null,
                formats = listOf(
                    MediaFormat("137", "mp4", 1920, 1080, true, false, 1_000_000, PICTURE, "avc1.640028", null),
                    MediaFormat("251", "webm", null, null, false, true, 150_000, SOUND, null, "opus"),
                ),
            ),
        )
    }

    private val launcher = VideoPlaybackLauncher(
        VideoResolver(SongWithAudioTrack(), SkipSegmentSource { emptyList() }),
        controller,
        FakeYouTubeWatchHistory(),
        InMemoryPlayHistoryStore(),
    )

    private val queue = PlaybackQueue(controller = controller, launcher = launcher, scope = CoroutineScope(dispatcher))

    private fun youtube(id: String, kind: MediaContentKind): PlayableItem {
        val watch = HttpUrl.of("https://www.youtube.com/watch?v=$id")
        return PlayableItem(
            item = MediaItem(
                id = MediaItemId(id),
                sourceId = SourceId("music:test"),
                title = "item $id",
                publishedAt = null,
                duration = null,
                contentKind = kind,
            ),
            handle = PlayHandle.Video(watch),
        )
    }

    @Test
    fun `a song plays its sound while the app is set to watch`() = runTest {
        queue.playNow(youtube("song1", MediaContentKind.MUSIC))
        advanceUntilIdle()

        assertEquals(SOUND, controller.lastItem?.mediaUrl?.value)
    }

    @Test
    fun `an ordinary video still plays its picture`() = runTest {
        queue.playNow(youtube("video1", MediaContentKind.STANDARD))
        advanceUntilIdle()

        assertEquals(PICTURE, controller.lastItem?.mediaUrl?.value)
    }

    @Test
    fun `asking for the picture of a song gets it, and keeps it on the next route`() = runTest {
        queue.playNow(youtube("song2", MediaContentKind.MUSIC))
        advanceUntilIdle()
        assertEquals("precondition: the song started as sound", SOUND, controller.lastItem?.mediaUrl?.value)

        queue.wantsThePictureAgain(MediaItemId("song2"))
        queue.replayCurrent(positionMs = 30_000)
        advanceUntilIdle()

        assertEquals(PICTURE, controller.lastItem?.mediaUrl?.value)
    }

    @Test
    fun `the next song is armed as sound`() = runTest {
        queue.playNow(youtube("song3", MediaContentKind.MUSIC))
        advanceUntilIdle()
        queue.enqueue(youtube("song4", MediaContentKind.MUSIC))
        advanceUntilIdle()

        queue.armNext(allowStream = true)
        advanceUntilIdle()

        assertEquals(SOUND, controller.armedItem?.mediaUrl?.value)
    }

    private companion object {
        const val PICTURE = "https://x.test/v?n=solved"
        const val SOUND = "https://x.test/a?n=solved"
    }
}
