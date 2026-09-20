package com.dewijones92.totum.playback

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.MainActivity
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.common.Breadcrumbs
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.support.SilentWav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * A rewind is written down at once, and a rewind to the very start is written down too.
 *
 * Report 0.1.496, Dewi: *"I have tried to rewind the video back to the start but it is not
 * working"*. Two of the three faults behind it only exist in the composition, so no fake can
 * catch them — this drives the real `Media3PlaybackController`, the real player and the real Room
 * store:
 *
 *  - progress was saved on a pause and every tenth position tick (five seconds of playing) and
 *    **never on a seek**, so rewinding and tapping another queue row four seconds later left the
 *    store holding a position from ninety seconds earlier;
 *  - the store's five-second floor, there so a quick tap creates no resume point, **discarded
 *    every save that would have moved an existing one back** — 3790ms, 2595ms and 1144ms were all
 *    dropped on the phone.
 *
 * Both assertions are deliberately made well inside the five-second tick, because a save that
 * eventually happens on the tick is exactly the behaviour that was not good enough.
 */
class RewindIsRememberedTest {

    /** Foreground, or Android 16 denies audio focus and the player never leaves 0ms. */
    @get:Rule
    val activity = ActivityScenarioRule(MainActivity::class.java)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as TotumApplication
    private val container get() = app.container
    private val controller get() = container.playbackController
    private val queue get() = container.playbackQueue
    private val progress get() = container.playbackProgressStore

    private val id = MediaItemId("rewind-me")

    @Before
    fun emptyTheQueue() = runBlocking(Dispatchers.Main) {
        awaitControllerConnected()
        // Silent media: with skip-silence on, sample removal deletes the whole file and nothing
        // ever plays, which reads as a broken seek rather than a suppressed one.
        controller.setSkipSilence(false)
        queue.clear()
        controller.player?.stop()
        controller.player?.clearMediaItems()
        progress.setPlayed(id, played = false) // clears any row a previous run left
    }

    @After
    fun tearDown() = runBlocking(Dispatchers.Main) {
        queue.clear()
        controller.player?.stop()
        controller.player?.clearMediaItems()
        progress.setPlayed(id, played = false)
    }

    @Test
    fun aSeekIsRememberedWithoutWaitingForATick() = runBlocking(Dispatchers.Main) {
        queue.playNow(item())
        awaitPlaying()

        controller.seekTo(HALFWAY_MS)

        assertNotNull(
            "a seek must be written down at once, not on the next five-second tick",
            awaitStoredPosition("at least halfway") { it != null && it >= HALFWAY_MS },
        )
    }

    @Test
    fun aRewindToTheStartReplacesAPositionTheFloorUsedToProtect() = runBlocking(Dispatchers.Main) {
        queue.playNow(item())
        awaitPlaying()
        controller.seekTo(HALFWAY_MS)
        assertNotNull(
            "arrange: the item must hold a real position before the rewind means anything",
            awaitStoredPosition("at least halfway") { it != null && it >= HALFWAY_MS },
        )

        controller.seekTo(0)

        assertNotNull(
            "the rewind must reach the store even though it is under the five-second floor",
            awaitStoredPosition("back near the start") { it != null && it < FLOOR_MS },
        )
    }

    /**
     * Resuming is not a seek, and must not be recorded as one.
     *
     * `play()` hands the resume position to `setMediaItem(item, resumeMs)`. If that masked as
     * `DISCONTINUITY_REASON_SEEK` the listener would deliberate-save on every single play — which
     * would create a resume point for anything merely opened, destroying the floor's whole purpose,
     * and would do it silently. Reading ExoPlayer says it reports `REMOVE`; this is the check,
     * because "I read the source" is not the same as "I watched it happen".
     */
    @Test
    fun openingAnItemAtItsResumePointIsNotRecordedAsAChoice() = runBlocking(Dispatchers.Main) {
        progress.save(id, positionMs = HALFWAY_MS, durationMs = MEDIA_SECONDS * 1_000L)
        Breadcrumbs.clear()

        queue.playNow(item())
        awaitPlaying()

        val chosen = Breadcrumbs.snapshot().filter { "the position was chosen" in it.message }
        assertEquals("opening an item must not log a chosen position: $chosen", 0, chosen.size)
    }

    private suspend fun awaitStoredPosition(what: String, matches: (Long?) -> Boolean): Long? =
        withTimeoutOrNull(STORE_WAIT_MS) {
            var seen = progress.resumePositionMs(id)
            while (!matches(seen)) {
                delay(POLL_MS)
                seen = progress.resumePositionMs(id)
            }
            seen
        }.also {
            if (it == null) {
                assertEquals("stored position never became $what", what, "never")
            }
        }

    private suspend fun awaitControllerConnected() {
        val connected = withTimeoutOrNull(TIMEOUT_MS) {
            while (controller.player == null) delay(POLL_MS)
            true
        }
        assertEquals("the media controller never connected to the playback service", true, connected)
    }

    private suspend fun awaitPlaying() {
        val playing = withTimeoutOrNull(TIMEOUT_MS) {
            while (controller.state.value?.itemId != id || controller.state.value?.isPlaying != true) delay(POLL_MS)
            true
        }
        assertEquals(
            "the item is loaded but not playing — on a device that usually means audio focus " +
                "was refused, which happens when the screen is off or the app is not foreground",
            true,
            playing,
        )
    }

    private fun item() = PlayableItem(
        item = MediaItem(
            id = id,
            sourceId = SourceId("test"),
            title = "rewind me",
            publishedAt = null,
            duration = null,
            mediaUrl = null,
        ),
        handle = PlayHandle.Podcast(silentWav().absolutePath),
    )

    /** Long enough that halfway is well past the store's floor and nowhere near its finished tail. */
    private fun silentWav(): File =
        File(context.cacheDir, "rewind-me.wav").apply { writeBytes(SilentWav.bytes(MEDIA_SECONDS)) }

    private companion object {
        const val MEDIA_SECONDS = 120
        const val HALFWAY_MS = 60_000L
        const val FLOOR_MS = 5_000L
        const val TIMEOUT_MS = 20_000L

        /** Comfortably under the ten-tick save, so passing cannot mean "the tick got there eventually". */
        const val STORE_WAIT_MS = 2_000L
        const val POLL_MS = 50L
    }
}
