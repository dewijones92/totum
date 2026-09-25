package com.dewijones92.totum.playback

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.MainActivity
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.support.PlaybackWaits
import com.dewijones92.totum.support.RangedMediaServer
import com.dewijones92.totum.support.SilentWav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The symptom Dewi reported, at the level he reported it: *"buffers towards the end of the video"*.
 *
 * Every other test of this fix looks at a part — [com.dewijones92.totum.playback.ChunkedReadTest] at
 * the arithmetic, `ChunkedDataSourceTest` at the ranged reads. This one drives the whole thing: the
 * real queue, the real session, the real service, the real ExoPlayer, over HTTP with a `clen`
 * parameter, and asks the only question that actually matters — **does the item reach its end?**
 *
 * Before the fix it did not. Report 0.1.359 (2026-08-06) had four consecutive videos stall inside
 * their last 45 seconds with an empty buffer, 208 of 244 seconds of buffering abandoned, and every
 * one of them given up on rather than finished. The cause was the data source taking `clen` — the
 * length of the WHOLE resource — as the bytes remaining from wherever the loader had resumed, and
 * so asking for a range past the end when it got there.
 *
 * **The seek is the point.** ExoPlayer restarts its loader at a non-zero byte offset on every seek
 * and every time the load control pauses loading, which is how nearly every read of a long item
 * begins — so a test that only ever plays from byte zero passes with the defect in place. That is
 * precisely why this shipped.
 *
 * Deterministic and offline: the media is a generated silent WAV and the server is a localhost
 * socket, so it runs on every commit rather than behind the live-test tunnel.
 */
class StreamPlaysToItsEndTest {

    /** Foreground, or the platform refuses audio focus and nothing ever plays. */
    @get:Rule
    val activity = ActivityScenarioRule(MainActivity::class.java)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as TotumApplication
    private val container get() = app.container
    private val controller get() = container.playbackController
    private val queue get() = container.playbackQueue

    private val media = SilentWav.bytes(MEDIA_SECONDS)
    private lateinit var server: RangedMediaServer

    private var autoPlayNextBefore = true

    @Before
    fun startServerAndEmptyTheQueue() {
        server = RangedMediaServer(media)
        // Autoplay OFF: reaching an item's end with an empty queue is what makes the app go and
        // play something related, and teardown cannot catch it because the end has already happened.
        // It leaked into a later test on 2026-09-20 — see PlaybackWaits for what that cost.
        autoPlayNextBefore = container.appPreferences.settings.value.autoPlayNext
        container.appPreferences.setAutoPlayNext(false)
        runBlocking(Dispatchers.Main) {
            queue.clear()
            // Silence removal deletes a silent file outright, and playback then never starts —
            // which reads identically to the failure this test is looking for.
            controller.setSkipSilence(false)
            controller.setSpeed(1f)
        }
    }

    @After
    fun tearDown() {
        server.close()
        runBlocking(Dispatchers.Main) {
            // In a finally, and FIRST in it, because setAutoPlayNext writes through to
            // SharedPreferences: a throw in any of the three calls below would otherwise leave
            // autoplay off on this device permanently, for every later test and for whoever uses
            // the emulator next.
            try {
                queue.clear()
                controller.player?.stop()
                controller.player?.clearMediaItems()
            } finally {
                container.appPreferences.setAutoPlayNext(autoPlayNextBefore)
            }
        }
    }

    /** THE REGRESSION. Resume partway through, and the item must still reach its end. */
    @Test
    fun `an item resumed partway through plays all the way to its end`() = runBlocking(Dispatchers.Main) {
        queue.playNow(hostedItem())
        assertTrue("it never started playing at all", awaitPlaying())

        // Where a resume lands, and where the defect lived: from here the loader reads to the true
        // end of the resource while still believing it is owed everything before this point.
        controller.seekTo(NEAR_THE_END_MS)

        assertTrue(
            "the item never reached its end — this is the reported stall. It buffered with the tail " +
                "never arriving, because the reader asked for a range past the end of the resource " +
                "and got nothing back, forever. Ranges asked for: ${server.asked}",
            awaitEnded(),
        )
    }

    @Test
    fun `an item played from the start reaches its end`() = runBlocking(Dispatchers.Main) {
        queue.playNow(hostedItem())
        assertTrue("it never started playing at all", awaitPlaying())
        controller.seekTo(NEAR_THE_END_MS)
        assertTrue("a stream read from byte zero must also finish", awaitEnded())
    }

    /**
     * And the ranges must stay inside the resource, which is the mechanism rather than the symptom.
     *
     * Asserted separately because a player can reach the end for reasons other than the fix — a
     * timeout treated as an end, say — and the byte ranges cannot.
     */
    @Test
    fun `no byte range is ever asked for past the end of the resource`() = runBlocking(Dispatchers.Main) {
        queue.playNow(hostedItem())
        assertTrue(awaitPlaying())
        controller.seekTo(NEAR_THE_END_MS)
        awaitEnded()

        val pastTheEnd = server.asked.mapNotNull { header ->
            header.substringAfter("bytes=", "").substringBefore('-').toIntOrNull()
        }.filter { it >= media.size }

        assertEquals(
            "a range starting at or past the ${media.size}-byte resource was asked for: $pastTheEnd " +
                "(all ranges: ${server.asked})",
            emptyList<Int>(),
            pastTheEnd,
        )
    }

    private fun hostedItem() = PlayableItem(
        item = MediaItem(
            id = MediaItemId(ITEM_ID),
            sourceId = SourceId("test"),
            title = "an item that must finish",
            publishedAt = null,
            duration = null,
            // With `clen`, as YouTube's stream URLs carry it — the branch that was wrong.
            mediaUrl = HttpUrl.of(server.url()),
        ),
        handle = PlayHandle.Podcast(),
    )

    private suspend fun awaitPlaying(): Boolean =
        PlaybackWaits.awaitStateOf(controller, MediaItemId(ITEM_ID), START_TIMEOUT_MS) { it.isPlaying } != null

    private suspend fun awaitEnded(): Boolean =
        PlaybackWaits.awaitStateOf(controller, MediaItemId(ITEM_ID), END_TIMEOUT_MS) { it.hasEnded } != null

    private companion object {
        const val ITEM_ID = "plays-to-the-end"
        const val MEDIA_SECONDS = 30

        /** Inside the last few seconds, which is exactly where the reported stalls happened. */
        const val NEAR_THE_END_MS = 26_000L

        const val START_TIMEOUT_MS = 30_000L

        /** The remaining seconds at 1x, with room for a slow emulator — and finite, unlike the bug. */
        const val END_TIMEOUT_MS = 45_000L
    }
}
