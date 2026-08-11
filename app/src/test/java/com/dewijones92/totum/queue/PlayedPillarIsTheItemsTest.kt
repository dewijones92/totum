package com.dewijones92.totum.queue

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.domain.LocalCopy
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaKind
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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pillar the player is told is the ITEM's, never the presentation's.
 *
 * Dewi, 2026-08-11: *"I want YouTube to know everything I watch and listen to … to improve the
 * algorithm"*. It did not, and this is why. The two audio routes handed the player
 * `MediaKind.PODCAST` — meaning "play this as audio" rather than "this is a podcast" — and
 * `WatchHistorySync` skips anything that is not a video. So **a YouTube video played from a
 * downloaded file was invisible to his own account**, which with auto-download-audio on by default
 * is most of his listening.
 *
 * Scope, corrected while writing these tests: Listen-mode *streaming* was never affected. That path
 * goes through the launcher, which calls `play()` without a kind, and the parameter defaults to
 * VIDEO. Only the routes the queue drives directly were wrong. The test below pins the launcher's
 * behaviour too, so the two cannot diverge later.
 *
 * Report 0.1.376 has it plainly: four plays in three minutes, and only the streamed one reached
 * YouTube. The state block read `playing.kind = PODCAST` for a YouTube video.
 *
 * Whether a picture is shown is `PlaybackState.hasVideo`'s business, and always was.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayedPillarIsTheItemsTest {

    private val dispatcher = StandardTestDispatcher()
    private val controller = FakePlaybackController()
    private val onDisk = mutableMapOf<MediaItemId, LocalCopy>()

    private fun queue(audioPreferred: Boolean = false, offline: Boolean = false) = PlaybackQueue(
        controller = controller,
        launcher = VideoPlaybackLauncher(
            VideoResolver(FakeYtDlpEngine(), SkipSegmentSource { emptyList() }),
            controller,
            FakeYouTubeWatchHistory(),
            InMemoryPlayHistoryStore(),
        ),
        scope = CoroutineScope(dispatcher),
        offline = { offline },
        audioPreferred = { audioPreferred },
        localCopy = { id -> onDisk[id] },
    )

    /**
     * THE BUG. A downloaded YouTube video is a VIDEO, whatever it is being played as.
     *
     * `audioPreferred` is on because that is what makes an audio-only copy usable at all — a
     * deliberate asymmetry documented in `PlayRoute`: an audio-only file does not stand in while
     * you are *watching*. It is also the state his report was in (`listen=true`).
     */
    @Test
    fun aDownloadedYouTubeVideoKeepsItsPillar() = runTest(dispatcher) {
        onDisk[MediaItemId(VIDEO_ID)] = LocalCopy(path = "/downloads/$VIDEO_ID.media", audioOnly = true)

        queue(audioPreferred = true).peek(youTubeVideo())
        advanceUntilIdle()

        assertEquals(trail(), MediaKind.VIDEO, controller.state.value?.kind)
    }

    /**
     * A video with no local copy is routed to the launcher even in Listen mode, and the launcher
     * swaps to the audio stream itself — so this asserts the ROUTE rather than the pillar, because
     * that is the decision the queue actually makes here. It matters: the fix must not accidentally
     * turn a Listen-mode video into a direct audio play that skips the launcher, and with it the
     * resolve, the skip segments and the tracking session.
     */
    @Test
    fun aVideoWithNoCopyStillGoesThroughTheLauncher() = runTest(dispatcher) {
        queue(audioPreferred = true).peek(youTubeVideo())
        advanceUntilIdle()

        assertTrue(trail(), trail().contains("streaming the video"))
    }

    /** Offline, from disk — the commonest case of all, given the queue auto-downloads. */
    @Test
    fun anOfflineDownloadedVideoKeepsItsPillar() = runTest(dispatcher) {
        onDisk[MediaItemId(VIDEO_ID)] = LocalCopy(path = "/downloads/$VIDEO_ID.media", audioOnly = true)

        queue(offline = true).peek(youTubeVideo())
        advanceUntilIdle()

        assertEquals(trail(), MediaKind.VIDEO, controller.state.value?.kind)
    }

    /** The other half of the contract: a podcast must still be a podcast. */
    @Test
    fun aPodcastIsStillAPodcast() = runTest(dispatcher) {
        queue().peek(podcastEpisode())
        advanceUntilIdle()

        assertEquals(MediaKind.PODCAST, controller.state.value?.kind)
    }

    @Test
    fun aDownloadedPodcastIsStillAPodcast() = runTest(dispatcher) {
        onDisk[MediaItemId(EPISODE_ID)] = LocalCopy(path = "/downloads/$EPISODE_ID.mp3", audioOnly = true)

        queue().peek(podcastEpisode())
        advanceUntilIdle()

        assertEquals(MediaKind.PODCAST, controller.state.value?.kind)
    }

    private fun trail(): String =
        com.dewijones92.totum.common.Breadcrumbs.snapshot().joinToString("\n") { "${it.tag}: ${it.message}" }

    private fun youTubeVideo() = PlayableItem(
        item = MediaItem(
            id = MediaItemId(VIDEO_ID),
            sourceId = SourceId("youtube"),
            title = "A talk",
            publishedAt = null,
            duration = null,
            mediaUrl = WATCH_URL,
        ),
        handle = PlayHandle.Video(WATCH_URL),
    )

    private fun podcastEpisode() = PlayableItem(
        item = MediaItem(
            id = MediaItemId(EPISODE_ID),
            sourceId = SourceId("feed"),
            title = "An episode",
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://example.test/$EPISODE_ID.mp3"),
        ),
        handle = PlayHandle.Podcast(),
    )

    private companion object {
        const val VIDEO_ID = "YZAtVucNu8c"
        const val EPISODE_ID = "ep1"
        val WATCH_URL = HttpUrl.of("https://www.youtube.com/watch?v=$VIDEO_ID")
    }
}
