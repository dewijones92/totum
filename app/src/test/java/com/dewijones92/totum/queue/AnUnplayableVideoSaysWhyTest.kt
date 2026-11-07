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
import com.dewijones92.totum.playback.PlaybackProblem
import com.dewijones92.totum.playback.TrackKind
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
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AnUnplayableVideoSaysWhyTest {

    private val dispatcher = StandardTestDispatcher()
    private val controller = FakePlaybackController()

    private fun queue(listening: Boolean = false) = PlaybackQueue(
        controller = controller,
        launcher = VideoPlaybackLauncher(
            VideoResolver(FakeYtDlpEngine(), SkipSegmentSource { emptyList() }),
            controller,
            FakeYouTubeWatchHistory(),
            InMemoryPlayHistoryStore(),
        ),
        scope = CoroutineScope(dispatcher),
        audioPreferred = { listening },
    )

    private fun film(extension: String = "mkv") = PlayableItem(
        item = MediaItem(
            id = MediaItemId("jellyfin:00112233445566778899aabbccddeeff"),
            sourceId = SourceId("jellyfin:films"),
            title = "21 Jump Street",
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://pi.example/jellyfin/stream/00112233445566778899aabbccddeeff.$extension"),
        ),
        handle = PlayHandle.Podcast(),
    )

    private val tenBitHevc = PlaybackProblem.CannotDecode(TrackKind.VIDEO, "HEVC 10-bit", "ERROR_CODE_DECODING_FAILED")

    private suspend fun kotlinx.coroutines.test.TestScope.failing(
        queue: PlaybackQueue,
        problem: PlaybackProblem,
        hasSound: Boolean = true,
    ): List<RefusedTap> {
        val taps = mutableListOf<RefusedTap>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { queue.refusedTaps.toList(taps) }
        advanceUntilIdle()
        dispatcher.scheduler.advanceUntilIdle()
        queue.playNow(film())
        advanceUntilIdle()
        controller.failToPlay(problem, hasSound)
        dispatcher.scheduler.advanceUntilIdle()
        advanceUntilIdle()
        return taps
    }

    @Test
    fun `a video the phone cannot decode is told to the user with its codec, and Listen is offered for the item`() =
        runTest {
            val taps = failing(queue(), tenBitHevc)

            assertEquals(1, taps.size)
            assertEquals("21 Jump Street", taps.single().title)
            assertEquals(tenBitHevc, taps.single().problem)
            assertEquals("jellyfin:00112233445566778899aabbccddeeff", taps.single().listen?.item?.id?.value)
        }

    @Test
    fun `a film with no sound is told but offered no Listen`() = runTest {
        val taps = failing(queue(), tenBitHevc, hasSound = false)

        assertEquals(tenBitHevc, taps.single().problem)
        assertNull(taps.single().listen)
    }

    @Test
    fun `a sound the phone cannot decode is told, and Listen would not help so it is not offered`() = runTest {
        val sound = PlaybackProblem.CannotDecode(TrackKind.AUDIO, "E-AC-3", "ERROR_CODE_DECODING_FAILED")

        val taps = failing(queue(), sound)

        assertEquals(sound, taps.single().problem)
        assertNull(taps.single().listen)
    }

    @Test
    fun `any other failure with no recovery is told with its error name, and offers nothing`() = runTest {
        val other = PlaybackProblem.Failed("ERROR_CODE_IO_FILE_NOT_FOUND")

        val taps = failing(queue(), other)

        assertEquals(other, taps.single().problem)
        assertNull(taps.single().listen)
    }

    @Test
    fun `while already listening a failure does not offer Listen again`() = runTest {
        val taps = failing(queue(listening = true), tenBitHevc)

        assertEquals(tenBitHevc, taps.single().problem)
        assertNull(taps.single().listen)
    }
}
