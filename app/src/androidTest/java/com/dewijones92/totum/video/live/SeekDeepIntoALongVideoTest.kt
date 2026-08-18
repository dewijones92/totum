package com.dewijones92.totum.video.live

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Seek an hour into a 97-minute video and keep playing.
 *
 * Dewi asked for exactly this, 2026-08-18: *"try seeking in a really long video"*. It is the sharpest
 * possible test of what broke that day, because **seeking is the operation that failed**. YouTube began
 * serving roughly the first megabyte of a stream and refusing everything beyond it, so playback from
 * byte zero looked fine for about a minute and any jump further in was refused outright. A test that
 * presses play and waits would have passed throughout.
 *
 * An hour in is around 30MB into the audio — thirty times past the ceiling, on a fresh URL, through the
 * real player and the real `ChunkedDataSource`. Nothing about it can be satisfied by a cached first
 * chunk.
 *
 * And it asserts playback **continues** from there rather than merely arriving. Reaching the position is
 * a seek; still moving several seconds later is the stream actually being served, which is the
 * difference between a URL that answers one range and one that answers the rest of the file.
 *
 * Uses NASA's "Cosmic Dawn" — public domain, and long enough that an hour in is nowhere near the end,
 * so this cannot accidentally become a test about end-of-stream (that is
 * `LiveStreamPlaysToItsEndTest`'s job).
 */
class SeekDeepIntoALongVideoTest {

    /** Foreground, or the platform refuses audio focus and nothing ever plays. */
    @get:Rule
    val activity = ActivityScenarioRule(MainActivity::class.java)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as TotumApplication
    private val container get() = app.container
    private val controller get() = container.playbackController
    private val queue get() = container.playbackQueue

    @Before
    fun emptyTheQueue() = runBlocking(Dispatchers.Main) {
        queue.clear()
        controller.setSkipSilence(false)
        controller.setSpeed(1f)
    }

    @After
    fun tearDown() {
        runBlocking(Dispatchers.Main) {
            queue.clear()
            controller.player?.stop()
            controller.player?.clearMediaItems()
        }
    }

    @Test
    fun anHourIntoALongVideoPlaysOn() = runBlocking(Dispatchers.Main) {
        queue.playNow(longVideo())

        val started = withTimeoutOrNull(START_TIMEOUT_MS) {
            while (controller.state.value?.isPlaying != true) delay(POLL_MS)
            true
        } ?: false
        assertTrue("nothing played at all within ${START_TIMEOUT_MS}ms. Trail:\n${trail()}", started)

        val duration = withTimeoutOrNull(START_TIMEOUT_MS) {
            while ((controller.state.value?.durationMs ?: 0) <= 0) delay(POLL_MS)
            controller.state.value?.durationMs
        } ?: 0
        assertTrue(
            "the fixture should be long enough for an hour-deep seek to be nowhere near its end, " +
                "but it reported ${duration}ms",
            duration > SEEK_TO_MS + WELL_CLEAR_OF_THE_END_MS,
        )

        controller.seekTo(SEEK_TO_MS)

        val arrived = withTimeoutOrNull(SEEK_TIMEOUT_MS) {
            while ((controller.state.value?.positionMs ?: 0) < SEEK_TO_MS) delay(POLL_MS)
            true
        } ?: false
        assertTrue(
            "never reached ${SEEK_TO_MS}ms — about ${SEEK_TO_MS / 60_000} minutes in, roughly 30MB " +
                "into the audio. This is the exact request YouTube was refusing on 2026-08-18. " +
                "Trail:\n${trail()}",
            arrived,
        )

        // ARRIVING is a seek; still moving is the stream being served. A URL that answers one range
        // and refuses the next would satisfy the assertion above and fail this one.
        val reached = controller.state.value?.positionMs ?: 0
        val keptGoing = withTimeoutOrNull(PROGRESS_TIMEOUT_MS) {
            while ((controller.state.value?.positionMs ?: 0) < reached + PROGRESS_MS) delay(POLL_MS)
            true
        } ?: false
        assertTrue(
            "reached ${reached}ms and then stopped — it never got ${PROGRESS_MS}ms further, so the " +
                "seek landed but the stream is not being served from there. Trail:\n${trail()}",
            keptGoing,
        )
    }

    private fun longVideo() = PlayableItem(
        item = MediaItem(
            id = MediaItemId(VIDEO_ID),
            sourceId = SourceId("youtube"),
            title = "Cosmic Dawn",
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://www.youtube.com/watch?v=$VIDEO_ID"),
        ),
        handle = PlayHandle.Video(HttpUrl.of("https://www.youtube.com/watch?v=$VIDEO_ID")),
    )

    /** The app's own breadcrumbs, so a failure carries its evidence rather than a guess. */
    private fun trail(): String =
        Breadcrumbs.snapshot().takeLast(TRAIL_LINES).joinToString("\n") { "${it.tag}: ${it.message}" }

    private companion object {
        /** NASA's "Cosmic Dawn" — public domain, about 97 minutes. */
        const val VIDEO_ID = "uSMGENDH_QI"

        /** An hour in: ~30MB into the audio, thirty times past the ceiling that broke everything. */
        const val SEEK_TO_MS = 60L * 60 * 1000

        /** So a pass cannot be an accident of landing near the end of the file. */
        const val WELL_CLEAR_OF_THE_END_MS = 10L * 60 * 1000

        /** A cold emulator resolving with QuickJS can spend most of this on the `n` solve. */
        const val START_TIMEOUT_MS = 180_000L

        /** A seek an hour in has to re-open the stream at a new offset; that is a network round trip. */
        const val SEEK_TIMEOUT_MS = 90_000L

        /** Enough movement to be playback rather than the seek settling. */
        const val PROGRESS_MS = 3_000L
        const val PROGRESS_TIMEOUT_MS = 60_000L
        const val POLL_MS = 250L
        const val TRAIL_LINES = 30
    }
}
