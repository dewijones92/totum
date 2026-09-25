package com.dewijones92.totum.playback

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.MainActivity
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.support.PlaybackWaits
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
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The core loop, end to end, with a real player: an item finishes and the next one starts.
 *
 * **This is the test that was missing, and its absence is the best explanation for why the
 * loop kept breaking.** Autoplay failed three times in a week and every one of those bugs
 * passed the 377 unit tests, because each component was correct against its fake and the
 * *composition* was not:
 *
 *  - an advancer that worked, hosted by a lifecycle that stopped when the activity did
 *  - a watchdog that worked, fed by a `StateFlow` that conflates equal values, so the stall
 *    it existed to catch never produced an emission
 *  - a guard that worked for one end, but was kept for a whole session, so an item played a
 *    second time was refused with a reason three hours out of date
 *
 * No fake can catch those. Only a real `ExoPlayer`, actually reaching the end of actual media,
 * with the actual advancer listening, exercises the seam where all three lived.
 *
 * The media is a one-second silent WAV generated here rather than committed: it keeps the test
 * offline and fast, and no audio anyone owns goes into the repository. Playing it through
 * [PlayHandle.Podcast] is deliberate — that route hands a local file straight to the controller
 * with no resolver or network in the way, so a failure here is a failure of the loop and cannot
 * be YouTube having a bad day.
 *
 * The activity is launched, and must be: Android 16 DENIES audio focus to a backgrounded app
 * (`AS.HardeningEnforcer: Focus request DENIED … procState:4`), so without it the player reached
 * READY and then sat at 0ms forever, never playing and therefore never ending. That is the
 * platform behaving correctly — playback here always starts from the app in the foreground — but
 * it cost a run to find, and a test that silently measures a suppressed player is worse than no
 * test.
 *
 * It drives the app's REAL container rather than building its own graph, which took one failed
 * attempt to learn. `TotumApplication.onCreate` already starts the advancer, the stall watchdog
 * and the prefetcher against the real controller, and an instrumented test runs inside that same
 * process — so a second controller of its own was a second client of one `MediaSession`, both
 * issuing commands, every callback logged three times, and the item sat READY and never played.
 * Using the real graph is not a compromise here: the composition is the thing under test.
 */
class AutoAdvanceLoopTest {

    /** Foreground, or the platform refuses audio focus and nothing ever plays. */
    @get:Rule
    val activity = ActivityScenarioRule(MainActivity::class.java)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as TotumApplication
    private val container get() = app.container
    private val controller get() = container.playbackController
    private val queue get() = container.playbackQueue

    /**
     * A real device carries a real queue — Dewi's had twenty items in it during the first run.
     * Clearing is what makes "the next item" mean the one this test put there.
     */
    @Before
    fun emptyTheQueue() {
        runBlocking(Dispatchers.Main) {
            awaitControllerConnected()
            container.appPreferences.setAutoPlayNext(true)
            // Explicitly OFF, not merely assumed off. This media is a SILENT wav, so if a
            // previous test left skip-silence on, sample-removal deletes the entire file and
            // playback never starts — which reads as "the item never played" and looks like a
            // playback bug. CI hit exactly that; the local order happened to hide it.
            controller.setSkipSilence(false)
            queue.clear()
            controller.player?.stop()
            controller.player?.clearMediaItems()
        }
    }

    /** Leave nothing playing: the advancer here is the app's own and outlives the test. */
    @After
    fun tearDown() {
        runBlocking(Dispatchers.Main) {
            queue.clear()
            controller.player?.stop()
            controller.player?.clearMediaItems()
        }
    }

    @Test
    fun `an item that finishes starts the next one`() = runBlocking(Dispatchers.Main) {
        val first = item("first")
        val second = item("second")
        queue.enqueue(second)

        queue.playNow(first)

        awaitPlaying("first")

        assertEquals(
            "and when it ends, the next must start on its own",
            "second",
            awaitCurrent("second")?.value,
        )
    }

    /**
     * The shape of report 0.1.258: an item the queue has already advanced past, played again,
     * must advance again. `AutoAdvancer.handled` held one id for the life of the process, so the
     * second end was refused citing the first — three hours earlier, in Dewi's case.
     *
     * The third item is not padding. `playNow` RELOCATES its item to the cursor, and after an
     * advance the cursor is the end of the queue — so replaying with nothing else queued leaves
     * the item genuinely last, `playNextInQueue` correctly reports "nothing after first of 2",
     * and the test fails on the app being right. Dewi's queue had sixty-nine items with plenty
     * after the one he replayed, so something must follow it here too or this is not that bug.
     */
    @Test
    fun `an item played a second time advances a second time`() = runBlocking(Dispatchers.Main) {
        val first = item("first")
        queue.enqueue(item("second"))

        queue.playNow(first)
        awaitPlaying("first")
        assertNotNull("the first pass must advance", awaitCurrent("second"))

        // Back to the one that already ended once — a replay, exactly as it happened by hand —
        // with something after it, as there was on the phone.
        queue.playNow(first)
        queue.enqueue(item("third"))
        awaitPlaying("first")

        assertNotNull(
            "the second end of the same item must advance too, not cite the first",
            awaitCurrent("third"),
        )
    }

    /**
     * Waits for the `MediaController` to finish connecting to [PlaybackService].
     *
     * The connection is asynchronous and commands issued before it lands are queued, so on a
     * cold CI emulator a `play` can sit unexecuted and the state stays null — which surfaced as
     * "first must become the current item … but was:<null>" and reads like a broken advance
     * rather than a player that was never there. Named separately so those two never look alike
     * again.
     */
    private suspend fun awaitControllerConnected() {
        val connected = withTimeoutOrNull(TIMEOUT_MS) {
            while (controller.player == null) delay(POLL_MS)
            true
        }
        assertEquals("the media controller never connected to the playback service", true, connected)
    }

    /**
     * Waits for [id] to be the current item, or null on timeout.
     *
     * Polls the state rather than collecting it, for the same reason [StallWatchdog] does: the
     * flow conflates equal values, so waiting for an *emission* can wait forever on a player
     * that is already in the state being waited for.
     */
    private suspend fun awaitCurrent(id: String): MediaItemId? = withTimeoutOrNull(TIMEOUT_MS) {
        var seen: MediaItemId? = null
        while (seen?.value != id) {
            delay(POLL_MS)
            seen = controller.state.value?.itemId
        }
        seen
    }

    /**
     * Waits for [id] to be current AND genuinely advancing, then asserts it.
     *
     * Being *selected* is not playing, and conflating the two wasted a run: with the emulator's
     * screen off the platform denied audio focus, the player sat at READY and 0ms forever, and
     * the only symptom was the NEXT assertion timing out twenty seconds later — which reads like
     * a broken advance rather than a player that was never allowed to start. Failing here says
     * which it was.
     */
    private suspend fun awaitPlaying(id: String) {
        assertEquals("$id must become the current item", id, awaitCurrent(id)?.value)
        val playing = PlaybackWaits.awaitStateOf(controller, MediaItemId(id), TIMEOUT_MS) { it.isPlaying } != null
        assertEquals(
            "$id is loaded but not playing — on a device that usually means audio focus was " +
                "refused, which happens when the screen is off or the app is not foreground",
            true,
            playing,
        )
    }

    private fun item(id: String) = PlayableItem(
        item = MediaItem(
            id = MediaItemId(id),
            sourceId = SourceId("test"),
            title = id,
            publishedAt = null,
            duration = null,
            mediaUrl = null,
        ),
        handle = PlayHandle.Podcast(silentWav(id).absolutePath),
    )

    /**
     * A one-second silent WAV, written to the cache.
     *
     * Generated rather than committed so the repository carries no audio, and short so the test
     * measures the loop rather than the media. Eight-bit unsigned PCM at 8kHz is the simplest
     * thing ExoPlayer will decode, and silence at that depth is 0x80, not zero.
     */
    private fun silentWav(name: String): File {
        val file = File(context.cacheDir, "$name.wav")
        val samples = SAMPLE_RATE
        val header = ByteBuffer.allocate(WAV_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(WAV_HEADER_BYTES - RIFF_PREAMBLE + samples)
        header.put("WAVEfmt ".toByteArray())
        header.putInt(FMT_CHUNK_BYTES)
        header.putShort(PCM_FORMAT)
        header.putShort(MONO)
        header.putInt(SAMPLE_RATE)
        header.putInt(SAMPLE_RATE)
        header.putShort(BLOCK_ALIGN)
        header.putShort(BITS_PER_SAMPLE)
        header.put("data".toByteArray())
        header.putInt(samples)
        file.writeBytes(header.array() + ByteArray(samples) { SILENCE_8_BIT })
        return file
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L
        const val POLL_MS = 100L
        const val SAMPLE_RATE = 8_000
        const val WAV_HEADER_BYTES = 44
        const val RIFF_PREAMBLE = 8
        const val FMT_CHUNK_BYTES = 16
        const val PCM_FORMAT: Short = 1
        const val MONO: Short = 1
        const val BLOCK_ALIGN: Short = 1
        const val BITS_PER_SAMPLE: Short = 8

        /** Silence in unsigned 8-bit PCM is mid-scale, not zero — zero is full negative. */
        const val SILENCE_8_BIT: Byte = -128
    }
}
