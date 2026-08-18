package com.dewijones92.totum.video

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.innertube.history.fake.FakeYouTubeWatchHistory
import com.dewijones92.totum.playback.fake.FakePlaybackController
import com.dewijones92.totum.ytdlp.ExtractionResult
import com.dewijones92.totum.ytdlp.MediaFormat
import com.dewijones92.totum.ytdlp.MediaMetadata
import com.dewijones92.totum.ytdlp.YtDlpEngine
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Switching to sound-only keeps your place.
 *
 * `listen()` called `playback.play(...)` with no start position, so it began again from zero — while
 * its own documentation said "Replays from the saved position". On a 97-minute video an hour in, that
 * is not a small thing.
 *
 * It went unnoticed because the visible symptom is mild for the case it was written for: toggling
 * Listen on a short video restarts something you were three minutes into. Then on 2026-08-18 recovery
 * gained a rung that falls back to sound when the picture is refused, and the bug became the reason a
 * deep seek could not be rescued at all — the emulator trail says it plainly:
 *
 * ```
 * playback: keeping the sound without the picture — the video stream will not serve
 * playback: ready after 395ms at 10ms
 * playback: playing at 11ms
 * ```
 *
 * The fallback fired correctly and then threw away the hour it was rescuing.
 */
class ListenKeepsYourPlaceTest {

    private val controller = FakePlaybackController()

    /** A video with a separate audio-only track, which is what Listen mode needs to exist. */
    private class TwoTrackEngine : YtDlpEngine by FakeYtDlpEngine() {
        override suspend fun extract(url: HttpUrl): ExtractionResult = ExtractionResult.Success(
            MediaMetadata(
                id = VIDEO,
                title = "Cosmic Dawn",
                uploader = null,
                durationSeconds = 5805,
                thumbnailUrl = null,
                formats = listOf(
                    MediaFormat(
                        "137", "mp4", 1920, 1080, true, false, 1_000_000,
                        "https://x.test/v?n=solved", "avc1.640028", null,
                    ),
                    MediaFormat(
                        "140", "m4a", null, null, false, true, 500_000,
                        AUDIO_URL, null, "mp4a.40.2",
                    ),
                ),
            ),
        )
    }

    private val launcher = VideoPlaybackLauncher(
        VideoResolver(TwoTrackEngine(), SkipSegmentSource { emptyList() }),
        controller,
        FakeYouTubeWatchHistory(),
        InMemoryPlayHistoryStore(),
    )

    private val listing = MediaItem(
        id = MediaItemId(VIDEO),
        sourceId = SourceId("youtube"),
        title = "Cosmic Dawn",
        publishedAt = null,
        duration = null,
        mediaUrl = HttpUrl.of("https://www.youtube.com/watch?v=$VIDEO"),
    )

    /** THE case recovery depends on: rescue the hour, do not restart the video. */
    @Test
    fun `listening from a position starts there`() = runTest {
        launcher.play(listing, HttpUrl.of("https://www.youtube.com/watch?v=$VIDEO"))

        launcher.listenIfPossible(fromMs = AN_HOUR_IN)

        assertEquals(AUDIO_URL, controller.lastItem?.mediaUrl?.value)
        assertEquals("it must resume where the picture died", AN_HOUR_IN, controller.lastStartPositionMs)
    }

    /** The UI toggle passes no position, and must take the one the player is already at. */
    @Test
    fun `listening with no position given takes the player's own`() = runTest {
        launcher.play(listing, HttpUrl.of("https://www.youtube.com/watch?v=$VIDEO"))
        controller.seekTo(AN_HOUR_IN)

        launcher.listen()

        assertEquals(
            "toggling Listen must not restart a 97-minute video",
            AN_HOUR_IN,
            controller.lastStartPositionMs,
        )
    }

    /** And it still reports honestly when there is nothing to fall back to. */
    @Test
    fun `with no audio-only track it declines`() = runTest {
        val soloLauncher = VideoPlaybackLauncher(
            VideoResolver(FakeYtDlpEngine(), SkipSegmentSource { emptyList() }),
            controller,
            FakeYouTubeWatchHistory(),
            InMemoryPlayHistoryStore(),
        )
        soloLauncher.play(listing, HttpUrl.of("https://www.youtube.com/watch?v=$VIDEO"))

        assertTrue(
            "nothing to listen to should be reported, not silently ignored",
            !soloLauncher.listenIfPossible(AN_HOUR_IN)
        )
    }

    private companion object {
        const val VIDEO = "uSMGENDH_QI"
        const val AUDIO_URL = "https://x.test/a?n=solved"
        const val AN_HOUR_IN = 3_600_000L
    }
}
