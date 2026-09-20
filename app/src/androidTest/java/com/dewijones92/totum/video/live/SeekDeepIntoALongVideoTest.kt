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
import com.dewijones92.totum.settings.PlaybackMode
import com.dewijones92.totum.support.PlaybackWaits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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

    /** Whatever the app was set to, so this test hands it back rather than assuming a default. */
    private var modeBefore: PlaybackMode = PlaybackMode.AUTO

    @Before
    fun emptyTheQueue() = runBlocking(Dispatchers.Main) {
        modeBefore = container.appPreferences.settings.value.playbackMode
        queue.clear()
        controller.setSkipSilence(false)
        controller.setSpeed(1f)
    }

    /**
     * Leaves nothing behind, because this test is expensive to be untidy about.
     *
     * `playNow` puts the item IN the queue, and the queue auto-downloads its audio — so a 97-minute
     * video quietly starts fetching and outlives the test. Run before
     * `LiveDownloadedVideoOfflineTest` it made that one fail on a stream URL for a video it had never
     * heard of, while passing perfectly in isolation. Cancelling the download is the part that is easy
     * to forget and expensive to leave: it is the only leak here measured in hundreds of megabytes.
     */
    @After
    fun tearDown() {
        runBlocking(Dispatchers.Main) {
            container.downloadManager.cancel(MediaItemId(VIDEO_ID))
            container.downloadManager.delete(MediaItemId(VIDEO_ID))
            queue.clear()
            controller.player?.stop()
            controller.player?.clearMediaItems()
            container.appPreferences.setPlaybackMode(modeBefore)
        }
    }

    /**
     * LISTEN mode, which is how Dewi actually uses the app — every diagnostics report from his phone
     * carries `settings.playbackMode = AUDIO`.
     *
     * Kept as its own case rather than folded into the one below, because the two go through different
     * pickers and, measured on 2026-08-18, behave completely differently an hour deep: the audio-only
     * URL served byte 61,567,041 on 5 of 5 attempts, while the video ladder managed roughly 3 of 10.
     * One test covering "seeking works" would have averaged those into a flake and hidden the fact that
     * the mode he lives in is the one that works.
     */
    @Test
    fun anHourIntoALongVideoPlaysOnWhileListening() = runBlocking(Dispatchers.Main) {
        container.appPreferences.setPlaybackMode(PlaybackMode.AUDIO)
        try {
            seekAnHourInAndKeepPlaying()
        } finally {
            container.appPreferences.setPlaybackMode(modeBefore)
        }
    }

    @Test
    fun anHourIntoALongVideoPlaysOn() = runBlocking(Dispatchers.Main) {
        container.appPreferences.setPlaybackMode(PlaybackMode.VIDEO)
        try {
            seekAnHourInAndKeepPlaying()
        } finally {
            container.appPreferences.setPlaybackMode(modeBefore)
        }
    }

    private suspend fun seekAnHourInAndKeepPlaying() {
        queue.playNow(longVideo())

        // Scoped to THIS video, because resolving one takes tens of seconds and the previous item
        // keeps playing throughout. Unscoped, both waits below were answered instantly by whatever
        // was already on, and this test failed claiming the 96-minute fixture was 22 minutes long.
        val playing = PlaybackWaits.awaitStateOf(controller, MediaItemId(VIDEO_ID), START_TIMEOUT_MS) {
            it.isPlaying
        }
        assertTrue(
            "$VIDEO_ID never played within ${START_TIMEOUT_MS}ms — the player is on " +
                "${PlaybackWaits.whatIsActuallyPlaying(controller)}. Trail:\n${trail()}",
            playing != null,
        )

        val known = PlaybackWaits.awaitStateOf(controller, MediaItemId(VIDEO_ID), START_TIMEOUT_MS) {
            (it.durationMs ?: 0) > 0
        }
        val duration = known?.durationMs ?: 0
        assertTrue(
            "the fixture should be long enough for an hour-deep seek to be nowhere near its end, " +
                "but $VIDEO_ID reported ${duration}ms. The player is on " +
                "${PlaybackWaits.whatIsActuallyPlaying(controller)}",
            duration > SEEK_TO_MS + WELL_CLEAR_OF_THE_END_MS,
        )

        controller.seekTo(SEEK_TO_MS)

        val arrived = PlaybackWaits.awaitStateOf(controller, MediaItemId(VIDEO_ID), SEEK_TIMEOUT_MS) {
            it.positionMs >= SEEK_TO_MS
        } != null
        assertTrue(
            "never reached ${SEEK_TO_MS}ms — about ${SEEK_TO_MS / 60_000} minutes in, roughly 30MB " +
                "into the audio. This is the exact request YouTube was refusing on 2026-08-18. " +
                "Trail:\n${trail()}",
            arrived,
        )

        // ARRIVING is a seek; still moving is the stream being served. A URL that answers one range
        // and refuses the next would satisfy the assertion above and fail this one.
        val reached = controller.state.value?.takeIf { it.itemId == MediaItemId(VIDEO_ID) }?.positionMs ?: 0
        // Generous, because the fallback to sound-only costs a re-resolve and a fresh connection.
        val keptGoing = PlaybackWaits.awaitStateOf(controller, MediaItemId(VIDEO_ID), PROGRESS_TIMEOUT_MS) {
            it.positionMs >= reached + PROGRESS_MS
        } != null
        // "Still playing" — not "still playing with a picture". Measured 2026-08-18: a 97-minute video
        // offers NO video format carrying a solved `n`, so watching it an hour in is not something the
        // app can choose; the sound is, and recovery falls back to it. Demanding the picture here would
        // assert something YouTube does not currently serve to any client yt-dlp can reach, and the
        // test would be a standing complaint about the world rather than a guard on this app.
        assertTrue(
            "reached ${reached}ms and then stopped — it never got ${PROGRESS_MS}ms further. Neither the " +
                "stream nor the sound-only fallback is serving from there. Trail:\n${trail()}",
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
        const val PROGRESS_TIMEOUT_MS = 120_000L
        const val TRAIL_LINES = 30
    }
}
