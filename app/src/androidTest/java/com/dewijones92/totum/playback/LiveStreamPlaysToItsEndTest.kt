package com.dewijones92.totum.playback

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.MainActivity
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.common.Breadcrumbs
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.support.DeviceRadios.goOnline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * A real YouTube stream, played to its actual end.
 *
 * This exists because the deterministic version cannot do it. `StreamPlaysToItsEndTest` drives the
 * same flow over a localhost server and **passes with the defect reinstated**, because a generated
 * WAV carries explicit sample sizes: the extractor stops at the last sample and never reads to the
 * data source's end-of-input, which is the point at which the tail defect bit. YouTube's streams
 * carry `gir=yes` and are read to end-of-input instead, and this repository ships no media of that
 * kind — so the only way to exercise it is against the real thing.
 *
 * What the reported failure looked like (0.1.359, 2026-08-06): four consecutive items stalled inside
 * their last 45 seconds with an empty buffer and never recovered, 208 of 244 seconds of buffering
 * abandoned. See `docs/todos/stalls-near-the-end-of-an-item.md`.
 *
 * **The seek is the whole point.** ExoPlayer restarts its loader at a non-zero byte offset on every
 * seek and every load-control pause, and the defect only showed there. A test that plays from byte
 * zero passes either way, which is exactly how this shipped.
 *
 * Live YouTube, so it runs through `tools/ci/live-test-via-home.sh` (residential egress) and is
 * SKIPPED rather than failed when the service will not serve this machine.
 */
class LiveStreamPlaysToItsEndTest {

    /** Foreground, or the platform refuses audio focus and nothing ever plays. */
    @get:Rule
    val activity = ActivityScenarioRule(MainActivity::class.java)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as TotumApplication
    private val container get() = app.container
    private val controller get() = container.playbackController
    private val queue get() = container.playbackQueue

    private val watchUrl = HttpUrl.of("https://www.youtube.com/watch?v=$VIDEO_ID")

    /**
     * Establishes what this test needs, because another live test leaves the opposite.
     *
     * `LiveDownloadedVideoOfflineTest` uses the SAME video id, turns the radios off, and leaves a
     * downloaded copy behind. Run before this one, both bite: the stream fails `Unreachable` at 0ms,
     * recovery correctly falls back to that leftover file, and a test about STREAMING to the end
     * quietly stops testing streaming. Seen in CI on 2026-08-18 — it passed locally and failed there
     * purely on run order.
     *
     * So: radios on, wait for the app to actually believe it (ConnectivityManager's callback is
     * asynchronous), and delete any copy of the fixture so nothing can stand in for the stream.
     */
    @Before
    fun emptyTheQueue() = runBlocking(Dispatchers.Main) {
        goOnline()
        withTimeoutOrNull(NETWORK_BACK_TIMEOUT_MS) {
            while (container.isOffline()) delay(POLL_MS)
            true
        }
        container.downloadManager.delete(MediaItemId(VIDEO_ID))
        queue.clear()
        // Sample removal on a short clip can consume the whole thing, which reads as "never played".
        controller.setSkipSilence(false)
        controller.setSpeed(1f)
    }

    // A block body, not an expression one: `player?.clearMediaItems()` is Unit? rather than Unit, and
    // JUnit rejects a fixture method whose return type is not void with "should be void".
    @After
    fun tearDown() {
        runBlocking(Dispatchers.Main) {
            queue.clear()
            controller.player?.stop()
            controller.player?.clearMediaItems()
        }
    }

    @Test
    fun `a real stream seeked near its end reaches the end`() = runBlocking(Dispatchers.Main) {
        queue.playNow(videoItem())

        val started = withTimeoutOrNull(START_TIMEOUT_MS) {
            while (controller.state.value?.isPlaying != true) delay(POLL_MS)
            true
        } ?: false
        // ASSERTED, not assumed. This line used to read `assumeTrue("… an environment condition and
        // not this defect")`, and on 2026-08-17 it hit the real thing: YouTube had stopped serving
        // the app's streams entirely, this test met that exactly, reported a SKIP, and CI published a
        // build in which nothing could be played. Dewi found out by using the app.
        //
        // But the message that replaced it named a CAUSE it had not established — "YouTube did not
        // serve this machine a playable stream" — and then said so in CI on a run where the same test
        // had passed on a warm emulator twenty minutes earlier through the same connection. A cold
        // emulator resolving with QuickJS can spend most of this budget on the `n` solve alone. So it
        // states what was OBSERVED and hands over the trail, which is the only thing that can tell a
        // refusal from a slow start.
        assertTrue(
            "nothing was playing after ${START_TIMEOUT_MS}ms. That is either YouTube refusing this " +
                "client or a resolve too slow to finish in time, and the difference is in the trail " +
                "below — a `resolve … for play` line means we got a stream and the wait was ours; a " +
                "refusal or no resolve line at all means we did not. Trail:\n" + trail(),
            started,
        )

        val duration = withTimeoutOrNull(START_TIMEOUT_MS) {
            while ((controller.state.value?.durationMs ?: 0) <= 0) delay(POLL_MS)
            controller.state.value?.durationMs
        }
        assumeTrue("the stream reported no duration, so there is no end to seek towards", duration != null)

        // Inside the last few seconds, which is where every reported stall happened.
        val target = (duration!! - FROM_THE_END_MS).coerceAtLeast(0)
        controller.seekTo(target)

        val ended = withTimeoutOrNull(END_TIMEOUT_MS) {
            while (controller.state.value?.hasEnded != true) delay(POLL_MS)
            true
        } ?: false

        assertTrue(
            "a real stream seeked to ${target}ms of ${duration}ms never reached its end. This is the " +
                "reported stall: the tail never arrives because the reader asks for a range past the " +
                "end of the resource. Last state: buffered=" +
                "${controller.state.value?.bufferedPositionMs} position=${controller.state.value?.positionMs}",
            ended,
        )
    }

    private fun videoItem() = PlayableItem(
        MediaItem(
            id = MediaItemId(VIDEO_ID),
            sourceId = SourceId("live-test"),
            title = "a real video played to its end",
            publishedAt = null,
            duration = null,
            mediaUrl = watchUrl,
        ),
        PlayHandle.Video(watchUrl),
    )

    /** The app's own breadcrumbs, so a failure carries its evidence instead of a guess. */
    private fun trail(): String =
        Breadcrumbs.snapshot().takeLast(TRAIL_LINES).joinToString("\n") { "${it.tag}: ${it.message}" }

    private companion object {
        /** "Me at the zoo" — 19 seconds, the oldest video on the site, unlikely to move. */
        const val VIDEO_ID = "jNQXAC9IVRw"

        /** Close enough to the end that the tail is the only thing left to fetch. */
        const val FROM_THE_END_MS = 6_000L

        /** ConnectivityManager's callback is asynchronous; generous and finite. */
        const val NETWORK_BACK_TIMEOUT_MS = 20_000L

        /** Enough to see the resolve and the first loads, without pasting a whole session. */
        const val TRAIL_LINES = 25

        /** A yt-dlp extraction on an emulator is slow; this is generous and finite. */
        const val START_TIMEOUT_MS = 120_000L

        /** The remaining seconds at 1x, with plenty of room — and finite, unlike the defect. */
        const val END_TIMEOUT_MS = 60_000L
        const val POLL_MS = 250L
    }
}
