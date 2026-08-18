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
import org.junit.Test

/**
 * Changing quality, or leaving Listen mode, must not throw away where you were.
 *
 * `listen()` was given the position on 2026-08-18 because a rescue an hour into a 97-minute video was
 * restarting from zero. Its three siblings were not: `selectQuality` calls `playback.play(...)` with no
 * start position, and `watch()` and `selectAudioTrack` both go through `playVideoQuality(resolved)`,
 * whose parameter defaults to 0. So the player falls back to the resume store — and `seekTo` neither
 * saves progress nor resets the save tick, so a scrub-then-switch inside the save window loses the seek
 * ENTIRELY rather than merely a few seconds of it.
 *
 * The visible bug: watch 40 minutes of something, nudge the quality up, and it starts again. On a long
 * video that is the whole session. `docs/todos/listen-mode-exit-ux.md` is marked `shipped` and promises
 * "switching to Watch re-attaches the video at the current position (don't restart)" — which was not
 * true of the code, and the doc is corrected in the same pass.
 *
 * One deliberate exception, already in the code and kept: a ONE-STREAM item (a torrent, a podcast) has
 * nothing to switch to, so `watch()` returns without touching the player rather than re-preparing it
 * and losing your place for no gain — measured on report 0.1.317.
 */
class SwitchingKeepsYourPlaceTest {

    private val controller = FakePlaybackController()

    /** A ladder plus a separate audio track: the shape every switch under test needs. */
    private class LadderEngine : YtDlpEngine by FakeYtDlpEngine() {
        override suspend fun extract(url: HttpUrl): ExtractionResult = ExtractionResult.Success(
            MediaMetadata(
                id = VIDEO,
                title = "Cosmic Dawn",
                uploader = null,
                durationSeconds = 5805,
                thumbnailUrl = null,
                formats = listOf(
                    MediaFormat(
                        "137", "mp4", 1920, 1080, true, false, 2_000_000,
                        "https://x.test/v1080?n=solved", "avc1.640028", null,
                    ),
                    MediaFormat(
                        "135", "mp4", 854, 480, true, false, 800_000,
                        "https://x.test/v480?n=solved", "avc1.4d401f", null,
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
        VideoResolver(LadderEngine(), SkipSegmentSource { emptyList() }),
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
        mediaUrl = HttpUrl.of(WATCH),
    )

    private suspend fun playAndSeekAnHourIn() {
        launcher.play(listing, HttpUrl.of(WATCH))
        controller.seekTo(AN_HOUR_IN)
    }

    /** THE common case: nudging the quality must not restart a long video. */
    @Test
    fun `changing quality resumes where you were`() = runTest {
        playAndSeekAnHourIn()
        val other = launcher.quality.value.options.first { it.height == 480 }

        launcher.selectQuality(other.id)

        assertEquals(
            "changing quality must not restart the video",
            AN_HOUR_IN,
            controller.lastStartPositionMs,
        )
    }

    /** Leaving Listen mode re-attaches the picture where the sound had got to. */
    @Test
    fun `going back to Watch resumes where you were`() = runTest {
        playAndSeekAnHourIn()
        launcher.listen(AN_HOUR_IN)
        controller.seekTo(AN_HOUR_IN + A_MINUTE)

        launcher.watch()

        assertEquals(
            "Watch must re-attach at the position, which is what the shipped doc already promises",
            AN_HOUR_IN + A_MINUTE,
            controller.lastStartPositionMs,
        )
    }

    /**
     * A FINISHED item must start again from the beginning, not from its own end.
     *
     * `whereWeAre()` was added so a switch keeps your place — and it broke the opposite rule, which is
     * older and lives in the progress store: an item played to the end resumes at 0 next time.
     * `Media3PlaybackController` treats ANY non-zero start position as "the caller knows better, skip
     * the store", so handing it a position at all bypasses that rule. An ended item's `positionMs` is
     * sticky at roughly its duration (the ticker stops), so nudging the quality on something you have
     * just finished re-prepared it at its own end and it instantly ended again — and with autoplay on,
     * that second `Ended` makes the advancer skip an extra item.
     *
     * The old `startPositionMs = 0` here was not an oversight; it was the mechanism. So the position is
     * only carried while there is a position worth carrying.
     */
    @Test
    fun `a finished item still restarts from the beginning`() = runTest {
        playAndSeekAnHourIn()
        controller.endCurrent()
        val other = launcher.quality.value.options.first { it.height == 480 }

        launcher.selectQuality(other.id)

        assertEquals(
            "a finished item must go back to the start, not re-prepare at its own end",
            0L,
            controller.lastStartPositionMs,
        )
    }

    private companion object {
        const val VIDEO = "uSMGENDH_QI"
        const val WATCH = "https://www.youtube.com/watch?v=$VIDEO"
        const val AUDIO_URL = "https://x.test/a?n=solved"
        const val AN_HOUR_IN = 3_600_000L
        const val A_MINUTE = 60_000L
    }
}
