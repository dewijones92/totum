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
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The sound-only rescue must play the item that FAILED — never the last video that happened to resolve.
 *
 * `VideoPlaybackLauncher.current` holds the most recently resolved video and is cleared only by
 * `playLocal`. A podcast (or torrent) never goes through the launcher at all — `PlaybackQueue.route`'s
 * audio branches call the controller directly — so after a video plays, `current` stays put.
 *
 * `playCurrentWithoutThePicture` then asked `listenIfPossible` for a fallback without checking the
 * pillar, and without reading `_state.value.current` at all — unlike both of its neighbours in the
 * ladder, which do exactly that. So when a podcast episode failed every retry, the rung reported
 * success and started **the previous video's audio**:
 *
 * * the wrong media plays, at the podcast's position;
 * * `attempts` resets to 0, so the broken episode is never abandoned and the queue stops advancing;
 * * progress is saved against the video's id;
 * * and the only log line names the podcast — two situations producing one line, which is the failure
 *   this repo has already paid for once.
 *
 * Found by a podcast-pillar audit on 2026-08-18, after a day of work that was almost entirely video.
 * That asymmetry is the point: the repo's twin law is that a capability serves BOTH pillars, and the
 * rung nobody exercised for podcasts is the one that broke. The three existing ladder tests all
 * substitute a lambda for this rung, so none of them could see it.
 *
 * Two guards, deliberately, because either alone leaves a hole: the pillar check the sibling rung
 * already has, and an item check inside `listenIfPossible` so a stale `current` is refused even if some
 * future caller forgets the pillar.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TheSoundRungPlaysTheFailINGItemTest {

    private val controller = FakePlaybackController()
    private val dispatcher = StandardTestDispatcher()

    /** A video WITH an audio-only track — the thing `listenIfPossible` looks for. */
    private class VideoWithAudioTrack : YtDlpEngine by FakeYtDlpEngine() {
        override suspend fun extract(url: HttpUrl): ExtractionResult = ExtractionResult.Success(
            MediaMetadata(
                id = VIDEO_ID,
                title = "a video that resolved earlier",
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
                        VIDEO_AUDIO_URL, null, "mp4a.40.2",
                    ),
                ),
            ),
        )
    }

    private val launcher = VideoPlaybackLauncher(
        VideoResolver(VideoWithAudioTrack(), SkipSegmentSource { emptyList() }),
        controller,
        FakeYouTubeWatchHistory(),
        InMemoryPlayHistoryStore(),
    )

    private val queue = PlaybackQueue(
        controller = controller,
        launcher = launcher,
        scope = CoroutineScope(dispatcher),
    )

    private fun video() = PlayableItem(
        item = MediaItem(
            id = MediaItemId(VIDEO_ID),
            sourceId = SourceId("youtube"),
            title = "a video that resolved earlier",
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of(WATCH),
        ),
        handle = PlayHandle.Video(HttpUrl.of(WATCH)),
    )

    private fun episode() = PlayableItem(
        item = MediaItem(
            id = MediaItemId(EPISODE_ID),
            sourceId = SourceId("a-podcast-feed"),
            title = "an episode whose enclosure keeps failing",
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of(ENCLOSURE),
        ),
        handle = PlayHandle.Podcast(),
    )

    /** THE bug: a failing PODCAST must not be "rescued" with a video's soundtrack. */
    @Test
    fun `a failing podcast is not rescued with the previous video's audio`() = runTest {
        queue.playNow(video())
        advanceUntilIdle()
        queue.playNow(episode())
        advanceUntilIdle()
        // What the queue is on NOW — the episode. Recorded before the rung runs, so the assertion is
        // "it did not switch to the video" rather than "nothing at all happened".
        val onEpisode = controller.lastItem?.mediaUrl?.value

        val kept = queue.playCurrentWithoutThePicture(positionMs = 120_000)
        advanceUntilIdle()

        assertFalse("a podcast has no picture to lose, so the rung must decline", kept)
        assertEquals(
            "it must not have started the video that resolved earlier",
            onEpisode,
            controller.lastItem?.mediaUrl?.value,
        )
    }

    /**
     * And the rung still works for a VIDEO — otherwise the guard could have disabled it entirely and
     * the assertion above would pass for the wrong reason.
     */
    @Test
    fun `a failing video is still rescued with its own audio`() = runTest {
        queue.playNow(video())
        advanceUntilIdle()

        val kept = queue.playCurrentWithoutThePicture(positionMs = 120_000)
        advanceUntilIdle()

        assertEquals(VIDEO_AUDIO_URL, controller.lastItem?.mediaUrl?.value)
        assertEquals("and from where the picture died", 120_000L, controller.lastStartPositionMs)
        assertEquals(true, kept)
    }

    /**
     * The launcher's own guard, pinned separately.
     *
     * The two guards are defence in depth, and either one alone makes the queue-level test above pass —
     * so that test cannot tell them apart, and a refactor could delete one without going red. This
     * exercises the launcher's half directly, with a mismatched id, so both mechanisms have coverage of
     * their own.
     */
    @Test
    fun `the launcher refuses to listen for an item it did not resolve`() = runTest {
        launcher.play(video().item, HttpUrl.of(WATCH))

        val forSomethingElse = launcher.listenIfPossible(MediaItemId("a-different-item"), fromMs = 0)

        assertFalse("the launcher holds $VIDEO_ID, so it cannot supply audio for another item", forSomethingElse)
        assertEquals(
            "and it must still serve the item it DID resolve",
            true,
            launcher.listenIfPossible(MediaItemId(VIDEO_ID), fromMs = 0),
        )
    }

    private companion object {
        const val VIDEO_ID = "uSMGENDH_QI"
        const val EPISODE_ID = "episode-1"
        const val WATCH = "https://www.youtube.com/watch?v=$VIDEO_ID"
        const val ENCLOSURE = "https://feed.test/ep1.mp3"
        const val VIDEO_AUDIO_URL = "https://x.test/a?n=solved"
    }
}
