package com.dewijones92.totum.video.live

import android.util.Log
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
 * A long item keeps PLAYING, without the spinner coming back — watching, and listening.
 *
 * Dewi, 2026-08-19: *"make sure there is no buffering when playing an hour long video AND just audio"*.
 * Starting is not the hard part; staying started is.
 *
 * Driven through the APP — its queue, its controller, its recovery ladder — and that is the whole point
 * of this test rather than a detail. The first version wired a bare `ExoPlayer` to the SABR sources
 * directly, and `SabrDataSource` throws on a stream that stops short **on purpose**, so the app can
 * re-resolve and carry on. A bare player has nothing to catch that: playback died at 60s and stayed
 * dead, and because a dead player never returns to BUFFERING the test reported `rebuffers=0` and passed.
 * It was measuring the absence of a symptom in something that had already stopped.
 *
 * So this asserts PROGRESS as well as quiet: the position has to keep up with the wall clock, or the
 * pass means nothing. Both are needed — progress alone would accept a stuttering stream, and quiet alone
 * accepts a corpse.
 */
class AnHourLongItemDoesNotRebufferTest {

    @get:Rule
    val activity = ActivityScenarioRule(MainActivity::class.java)

    private val app = InstrumentationRegistry.getInstrumentation()
        .targetContext.applicationContext as TotumApplication
    private val container get() = app.container
    private val controller get() = container.playbackController
    private val queue get() = container.playbackQueue
    private var modeBefore = PlaybackMode.AUTO
    private var sabrBefore = false

    @Before
    fun startClean() = runBlocking(Dispatchers.Main) {
        modeBefore = container.appPreferences.settings.value.playbackMode
        container.appPreferences.setAutoDownloadQueue(false)
        // SABR ON, because that is the path this is about. It is off by DEFAULT, so the first version of
        // this test measured the extraction path instead — and found its wall: a plain ANDROID_VR URL
        // 403ing at 60006ms ("a stream with 21522s of its lease left"), the app re-resolving, and one
        // visible stall. That is the same failure as report 0.1.444 from Dewi's phone, and it is exactly
        // what SABR exists to avoid. Whether the default should change is his call; this test says what
        // the SABR path does.
        sabrBefore = container.appPreferences.settings.value.sabrPlayback
        container.appPreferences.setSabrPlayback(true)
        queue.clear()
        container.downloadManager.delete(MediaItemId(VIDEO_ID))
        // From the BEGINNING, every time. The queue resumes where it left off, so the second case
        // started three minutes in and inherited a warm, already-settled stream — a kinder question
        // than the one asked, and one whose answer depended on which case ran first.
        //
        // Via setPlayed(false), which DELETES the row. `save(id, 0, null)` looks like the obvious reset
        // and is silently ignored: the store drops "trivially small positions" on purpose, so replaying
        // something would otherwise mark it unplayed the instant it started.
        container.playbackProgressStore.setPlayed(MediaItemId(VIDEO_ID), false)
    }

    @After
    fun tidyUp() = runBlocking(Dispatchers.Main) {
        queue.clear()
        controller.player?.stop()
        container.appPreferences.setPlaybackMode(modeBefore)
        container.appPreferences.setSabrPlayback(sabrBefore)
        container.downloadManager.delete(MediaItemId(VIDEO_ID))
    }

    @Test
    fun anHourLongVideoPlaysOnWithoutRebuffering() = playOn(PlaybackMode.VIDEO, "video")

    @Test
    fun anHourLongItemPlaysOnAsAudioWithoutRebuffering() = playOn(PlaybackMode.AUDIO, "audio-only")

    private fun playOn(mode: PlaybackMode, what: String) = runBlocking {
        container.appPreferences.setPlaybackMode(mode)
        queue.playNow(item())

        val started = withTimeoutOrNull(START_TIMEOUT_MS) {
            while (controller.state.value?.isPlaying != true) delay(POLL_MS)
            true
        } ?: false
        assumeTrue("$what never started, which other tests cover", started)

        var rebuffers = 0
        var wasBuffering = false
        val startedAt = controller.state.value?.bufferedPositionMs ?: 0L
        val playedFrom = controller.state.value?.positionMs ?: 0L
        var reached = startedAt
        var played = playedFrom
        val stalls = mutableListOf<String>()
        val until = System.currentTimeMillis() + WATCH_MS
        while (System.currentTimeMillis() < until) {
            delay(POLL_MS)
            val state = controller.state.value ?: continue
            // A rebuffer is the EDGE into buffering after playback began, not the level — sampling a
            // level counts one stall many times and misses two that share a sample.
            if (state.isBuffering && !wasBuffering) {
                rebuffers++
                // WHY, not just that it happened. A count alone cannot tell a re-resolve from a slow
                // network, and those need completely different work.
                stalls += "at ${state.positionMs}ms: " + (lastPlaybackNote() ?: "nothing logged")
            }
            wasBuffering = state.isBuffering
            // BUFFERED position, not playback position. Buffering is about DATA arriving, and playback
            // position is about decode: CI's emulator renders 1080p in software and cannot hold real
            // time, so it advanced 44230ms of a 60000ms window while the stream itself kept up fine.
            // Asserting on playback position measured that machine's decoder and called it a verdict on
            // streaming — the same mistake, in its third disguise, in one evening.
            reached = state.bufferedPositionMs
            played = state.positionMs
        }
        // The DELTA, not the absolute position. The queue resumes where it left off, so the video case
        // began at ~59s (where the audio case stopped) and "reached 74234ms of 60000ms" — a number that
        // reads like more than a full window of progress and is really a quarter of one.
        val progressed = reached - startedAt

        Log.i(
            "dewidebug",
            "no-rebuffer $what: rebuffers=$rebuffers buffered=${progressed}ms of ${WATCH_MS}ms " +
                "(${startedAt}ms->${reached}ms) rendered=${played - playedFrom}ms" +
                stalls.joinToString(prefix = " stalls[", postfix = "]"),
        )
        // PROGRESS first, because its absence is what the previous version of this test could not see.
        // The GUARD, not the measurement. `rebuffers == 0` is the property being asserted; this only has
        // to rule out the case that made a corpse look healthy — a player that stopped entirely reports
        // no buffering either. So the bar is deliberately low: CI's emulator renders 1080p in software
        // and reached 44230ms of a 60000ms window while the stream was perfectly fine, and failing that
        // build measured its decoder rather than the app.
        //
        // Buffered position cannot serve here, tempting though it looks: it is a LEVEL, not a flow. The
        // video case sat at 300901ms for the whole window because the buffer was already five minutes
        // ahead and full, so its delta was zero while everything worked.
        val rendered = played - playedFrom
        assertTrue(
            "playing $what rendered only ${rendered}ms in ${WATCH_MS}ms — it stopped rather than played, " +
                "and a stopped player reports no buffering at all, which is how this passed before " +
                "(buffered ${progressed}ms, ${startedAt}ms->${reached}ms)",
            rendered >= WATCH_MS / STOPPED_UNLESS_FRACTION,
        )
        assertTrue(
            "playing $what stalled $rebuffers time(s) in ${WATCH_MS / MS_PER_SECOND}s — the spinner " +
                "coming back on a long item is what makes it unusable. Where: $stalls",
            rebuffers <= ALLOWED_REBUFFERS,
        )
    }

    /** The most recent playback breadcrumb, which is the app's own account of what it just decided. */
    private fun lastPlaybackNote(): String? = Breadcrumbs.snapshot()
        .lastOrNull { it.tag == "playback" || it.tag == "sabr" || it.tag == "resolve" }
        ?.let { "${it.tag}: ${it.message.take(NOTE_CHARS)}" }

    private fun item() = PlayableItem(
        item = MediaItem(
            id = MediaItemId(VIDEO_ID),
            sourceId = SourceId("no-rebuffer-test"),
            title = "a long item that has to keep playing",
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of(WATCH),
        ),
        handle = PlayHandle.Video(HttpUrl.of(WATCH)),
    )

    private companion object {
        /** NASA's "Cosmic Dawn" — public domain, 97 minutes, so nothing here is near its end. */
        const val VIDEO_ID = "uSMGENDH_QI"
        const val WATCH = "https://www.youtube.com/watch?v=$VIDEO_ID"

        /**
         * Sixty seconds by default, overridable for a real soak:
         *
         * ```
         * adb shell am instrument -w -e soakMs 600000 \
         *   -e class com.dewijones92.totum.video.live.AnHourLongItemDoesNotRebufferTest \
         *   com.dewijones92.totum.test/androidx.test.runner.AndroidJUnitRunner
         * ```
         *
         * The default stays short enough for CI to run on every push, and "an hour-long video" deserves
         * better evidence than one minute of it — so the same code does both.
         */
        val WATCH_MS: Long = InstrumentationRegistry.getArguments()
            .getString("soakMs")?.toLongOrNull() ?: 60_000L

        const val START_TIMEOUT_MS = 180_000L
        const val POLL_MS = 500L
        const val MS_PER_SECOND = 1_000L

        /**
         * A third of the window. Low on purpose: this only separates "played" from "stopped", and any
         * higher bar starts measuring how fast the machine can decode. A stopped stream renders a second
         * or two and fails this comfortably.
         */
        const val STOPPED_UNLESS_FRACTION = 3L

        /** ZERO. A tolerance here would hide exactly the regression this exists to catch. */
        const val ALLOWED_REBUFFERS = 0
        const val NOTE_CHARS = 110
    }
}
