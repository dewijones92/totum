package com.dewijones92.totum.playback

import com.dewijones92.totum.domain.MediaItemId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * When to trade video for audio on mobile data.
 *
 * The saving is worth having — 15.2 MB/min against 2.1, measured — but the decision is only as good
 * as its restraint. Switching on a momentary blip would stutter playback every few seconds on a
 * train, which is a worse app than one that never had the feature.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MeteredAudioSwitchTest {

    private val item = MediaItemId("a-film")
    private var metered = false
    private var playing: MediaItemId? = item
    private var switchSucceeds = true
    private var switches = 0
    private val announced = mutableListOf<MediaItemId>()

    private fun TestScope.switcher() = MeteredAudioSwitch(
        metered = { metered },
        playingVideoId = { playing },
        switchToAudio = {
            switches++
            switchSucceeds
        },
        announce = { announced += it },
        scope = backgroundScope,
    ).also { it.start() }

    @Test
    fun `choosing video again on the same stretch of mobile data is not undone, even for the next video`() = runTest {
        switcher()
        metered = true
        advanceTimeBy(20_000)
        playing = MediaItemId("the-next-video")
        advanceTimeBy(60_000)

        assertEquals(1, switches)

        metered = false
        advanceTimeBy(10_000)
        metered = true
        advanceTimeBy(20_000)
        assertEquals("a new trip off Wi-Fi switches again", 2, switches)
    }

    @Test
    fun `mobile data that holds switches to audio and says so`() = runTest {
        switcher()
        metered = true
        advanceTimeBy(20_000)

        assertEquals(1, switches)
        assertEquals(listOf(item), announced)
    }

    /**
     * The case that decides whether this feature is tolerable. A lift, a tunnel, the end of the
     * drive — connectivity flaps, and acting on each flap would re-prepare the player continuously.
     */
    @Test
    fun `a brief drop onto mobile data is ignored`() = runTest {
        switcher()
        metered = true
        advanceTimeBy(9_000)
        metered = false
        advanceTimeBy(20_000)

        assertEquals(0, switches)
    }

    @Test
    fun `staying on wifi never switches`() = runTest {
        switcher()
        advanceTimeBy(600_000)

        assertEquals(0, switches)
    }

    /** Once per item, not once per tick of a two-hour journey. */
    @Test
    fun `a long journey on mobile data switches once`() = runTest {
        switcher()
        metered = true
        advanceTimeBy(600_000)

        assertEquals(1, switches)
    }

    /**
     * Nothing to downgrade: already audio, or paused, or stopped. Asking for a switch then would
     * restart playback for no benefit.
     */
    @Test
    fun `nothing playing with video means nothing to switch`() = runTest {
        playing = null
        switcher()
        metered = true
        advanceTimeBy(60_000)

        assertEquals(0, switches)
    }

    /**
     * Re-armed by returning to Wi-Fi, so a second trip out switches again. A flag that survives for
     * the life of the process is the exact defect that broke autoplay and then the stall rescue;
     * this is the third place it would have appeared.
     */
    @Test
    fun `a second trip onto mobile data switches again`() = runTest {
        switcher()
        metered = true
        advanceTimeBy(60_000)
        metered = false
        advanceTimeBy(10_000)
        metered = true
        advanceTimeBy(60_000)

        assertEquals(2, switches)
    }

    /**
     * Coming back to Wi-Fi does NOT restore video. Dewi's call: a screen lighting up with video
     * nobody asked for is worse than staying put, and it is one tap away.
     */
    @Test
    fun `returning to wifi does not switch anything back`() = runTest {
        switcher()
        metered = true
        advanceTimeBy(60_000)
        metered = false
        advanceTimeBy(600_000)

        assertEquals("only the downgrade, never an upgrade", 1, switches)
    }

    /** A failed switch must not be announced — video is still running, and saying otherwise lies. */
    @Test
    fun `a switch that fails is not announced`() = runTest {
        switchSucceeds = false
        switcher()
        metered = true
        advanceTimeBy(60_000)

        assertEquals(1, switches)
        assertEquals(emptyList<MediaItemId>(), announced)
    }
}
