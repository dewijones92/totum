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
     * Once the picture is given up on, it must STAY given up on for that item.
     *
     * From Dewi's Pixel (0.1.437, commit c65a750), note *"tennis video not working????"*. The rescue
     * fired correctly and was then undone 4.3 seconds later, over and over:
     *
     * ```
     * 09:51:47  403 -> refused -> keeping the sound without its picture   (audio plays)
     * 09:51:52  stream 1080p av01... (merged)                            (video AGAIN)
     * 09:52:00  403 again
     * 09:52:10  video AGAIN
     * ```
     *
     * `listen()` sets a flag on the LAUNCHER, but every route decides from the persisted playback mode —
     * which was VIDEO — so the next automatic route went straight back to the stream that had just been
     * refused. Each cycle costs a 10-14 second extraction, and what the person sees is a video that
     * stops every few seconds forever.
     *
     * So the refusal is remembered per item, for the session, and automatic routes for it prefer the
     * sound. A deliberate tap on Watch still clears it — an automatic decision that cannot be overruled
     * is worse than no automatic decision.
     */
    @Test
    fun `a refused picture stays refused for that item`() = runTest {
        queue.playNow(video())
        advanceUntilIdle()
        queue.playCurrentWithoutThePicture(positionMs = 120_000)
        advanceUntilIdle()

        // Anything that routes the item again — a recovery replay, a refresh, an advance back to it.
        queue.replayCurrent(positionMs = 130_000)
        advanceUntilIdle()

        assertEquals(
            "the video stream was just refused, so re-routing must not ask for it again",
            VIDEO_AUDIO_URL,
            controller.lastItem?.mediaUrl?.value,
        )
    }

    /**
     * A TORRENT has a picture to lose, and its own audio-only stream to fall back to.
     *
     * It is a `PlayHandle.Podcast` — the pillar-shaped guard refused it — but it is one file carrying
     * both tracks, and the home server can remux the sound out: 2.1 MB/min against 15.2. So the rung
     * that exists to keep the sound is exactly the rung it wants, and the pillar was the wrong question.
     *
     * The old guard also logged "a PODCAST item has no picture to lose" while `handle.audioUrl` sat
     * right there — one line asserting something false about two different situations.
     */
    @Test
    fun `a torrent falls back to its own audio-only stream`() = runTest {
        val torrent = PlayableItem(
            item = MediaItem(
                id = MediaItemId("torrent-1"),
                sourceId = SourceId("torrents"),
                title = "an episode from the home server",
                publishedAt = null,
                duration = null,
                mediaUrl = HttpUrl.of(TORRENT_VIDEO),
            ),
            handle = PlayHandle.Podcast(audioUrl = HttpUrl.of(TORRENT_AUDIO)),
        )
        queue.playNow(torrent)
        advanceUntilIdle()

        val kept = queue.playCurrentWithoutThePicture(positionMs = 60_000)
        advanceUntilIdle()

        assertEquals("a torrent has a picture to lose and a soundtrack to keep", true, kept)
        assertEquals(TORRENT_AUDIO, controller.lastItem?.mediaUrl?.value)
    }

    /** A plain podcast enclosure is already sound — there is nothing to drop, and it says so. */
    @Test
    fun `a plain enclosure has no picture to drop`() = runTest {
        queue.playNow(episode())
        advanceUntilIdle()

        assertEquals(false, queue.playCurrentWithoutThePicture(positionMs = 60_000))
    }

    /**
     * A PEEKED video must still be rescuable — the cursor is -1 for a peek, by design.
     *
     * `peek()` sets `currentIndex = NOTHING_PLAYING` deliberately, and `QueueSnapshot.current` is
     * `entries.getOrNull(-1)` — null. So a rung that asks the CURSOR what is playing answers "nothing"
     * for every peeked item, and the whole recovery ladder is dead for one of the app's first-class
     * actions (long-press → Peek, on Videos, Search and Podcasts rows).
     *
     * This repo has been burned by that exact confusion twice and wrote it down at `nowPlaying`'s KDoc:
     * *"the cursor answers where are we in the queue, which is -1 for a peek... 'what is playing' and
     * 'where is the cursor' are different questions"*. The pillar guard added on 2026-08-18 asked the
     * cursor anyway, and so closed the last rung that still worked for peeks.
     */
    @Test
    fun `a peeked video can still be rescued`() = runTest {
        queue.peek(video())
        advanceUntilIdle()
        assertEquals("precondition: a peek leaves the cursor at -1", -1, queue.state.value.currentIndex)

        val kept = queue.playCurrentWithoutThePicture(positionMs = 120_000)
        advanceUntilIdle()

        assertEquals(
            "a peeked video has a picture to lose like any other; the cursor being -1 is not an answer " +
                "about what is playing",
            true,
            kept,
        )
        assertEquals(VIDEO_AUDIO_URL, controller.lastItem?.mediaUrl?.value)
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
        const val TORRENT_VIDEO = "http://pi.test/stream/torrent-1"
        const val TORRENT_AUDIO = "http://pi.test/audio/torrent-1"
    }
}
