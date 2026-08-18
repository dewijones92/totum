package com.dewijones92.totum.queue

import com.dewijones92.totum.common.Breadcrumbs
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
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offline, the SABR rescue must not even be attempted.
 *
 * SABR is a network route, so with no network it cannot possibly succeed — and attempting it is not
 * merely pointless, it is SLOW at the worst moment. The rescue sits in the give-up ladder, which runs
 * when a stream has failed every retry, and offline that is the path to "step over this item and play
 * the next one". A doomed `/player` request in the middle of it delays the skip.
 *
 * That is not hypothetical: adding the rung turned `OfflineQueuePlaybackTest` red in CI on 2026-08-18 —
 * *"It was still on 'never-downloaded' after 20000ms, which is what attempting a doomed stream looks
 * like"*. The rung was correct and its placement was correct; what was missing was the cheapest possible
 * check before making a request that cannot work.
 *
 * The mirror of the lesson that put SABR in the ladder at all. A fallback gated on a condition the real
 * failure never triggers never fires; a fallback with NO gate fires when it cannot help. Both cost the
 * user something, and the second costs it exactly when they are already stuck.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TheSabrRescueIsNotTriedOfflineTest {

    private val dispatcher = StandardTestDispatcher()
    private val controller = FakePlaybackController()

    private fun queue(offline: Boolean) = PlaybackQueue(
        controller = controller,
        launcher = VideoPlaybackLauncher(
            VideoResolver(FakeYtDlpEngine(), SkipSegmentSource { emptyList() }),
            controller,
            FakeYouTubeWatchHistory(),
            InMemoryPlayHistoryStore(),
        ),
        scope = CoroutineScope(dispatcher),
        offline = { offline },
    )

    private fun video() = PlayableItem(
        item = MediaItem(
            id = MediaItemId(VIDEO_ID),
            sourceId = SourceId("youtube"),
            title = "a video with no copy on disk",
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of(WATCH),
        ),
        handle = PlayHandle.Video(HttpUrl.of(WATCH)),
    )

    /**
     * THE case: offline, it declines for THAT reason rather than after trying.
     *
     * Asserted on the reason it logs, not on the returned boolean. The first version of this test
     * checked only `assertFalse(rescued)` and passed WITHOUT the fix, because the fake resolver returns
     * nothing either way — a test that passes for the wrong reason, which is the exact trap its own
     * docstring warns about. The log line is the only observable that separates "declined because
     * offline" from "tried and failed".
     */
    @Test
    fun `offline the rescue declines because it is offline`() = runTest {
        Breadcrumbs.clear()
        val queue = queue(offline = true)
        queue.playNow(video())
        advanceUntilIdle()

        val rescued = queue.playCurrentOverSabr(positionMs = 0)
        advanceUntilIdle()

        assertFalse("offline, SABR cannot serve anything", rescued)
        assertTrue(
            "it must decline for being OFFLINE, before any request. Trail:\n" + trail(),
            trail().contains("offline, so it cannot serve"),
        )
    }

    /**
     * Online it does NOT decline for that reason — so the gate cannot be silently disabling the rung.
     *
     * The other half matters: a gate that refused online too would make the assertion above pass while
     * removing the rescue entirely, and no single-sided test could tell.
     */
    @Test
    fun `online it does not decline for being offline`() = runTest {
        Breadcrumbs.clear()
        val queue = queue(offline = false)
        queue.playNow(video())
        advanceUntilIdle()

        queue.playCurrentOverSabr(positionMs = 0)
        advanceUntilIdle()

        assertFalse(
            "online it must get past the offline gate. Trail:\n" + trail(),
            trail().contains("offline, so it cannot serve"),
        )
    }

    private fun trail() = Breadcrumbs.snapshot().joinToString("\n") { "  [" + it.tag + "] " + it.message }

    private companion object {
        const val VIDEO_ID = "jNQXAC9IVRw"
        const val WATCH = "https://www.youtube.com/watch?v=$VIDEO_ID"
    }
}
