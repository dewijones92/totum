package com.dewijones92.totum.cli.serve.targets

import com.dewijones92.totum.data.targets.QueueMode
import com.dewijones92.totum.data.targets.SleepKind
import com.dewijones92.totum.data.targets.Target
import com.dewijones92.totum.data.targets.TargetAction
import com.dewijones92.totum.data.targets.TargetControl
import com.dewijones92.totum.data.targets.TargetKind
import com.dewijones92.totum.data.targets.TargetQueueRequest
import com.dewijones92.totum.data.targets.TargetSleepAsk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class TargetDeckTreatEndsEarlyTest {

    private val events = mutableListOf<String>()
    private val player = RecordingPlayer()
    private val dir = Files.createTempDirectory("deck-treat-early").toFile()
    private val sounds = TargetSounds { item, _ -> "https://sound/${item.item.id.value}" }

    private inner class PowerLink(override val canSwitchOff: Boolean = true) : TargetLink {
        override suspend fun connect(): String? = "tv"

        override suspend fun release() {
            events += "release"
        }

        override suspend fun switchOff(): String? {
            events += "switch off"
            return null
        }
    }

    private fun TestScope.deck(canSwitchOff: Boolean = true) = TargetDeck(
        Target("lg-tv", "LG TV", TargetKind.TV),
        player,
        PowerLink(canSwitchOff),
        sounds,
        dir.resolve("tv-$canSwitchOff-${events.hashCode()}.json"),
        clock = { testScheduler.currentTime },
        scope = backgroundScope,
    )

    private suspend fun TargetDeck.playing(vararg ids: String) {
        enqueue(TargetQueueRequest(ids.map { podcastItem(it) }, QueueMode.REPLACE))
    }

    private fun after(seconds: Int, switchOff: Boolean = false, stop: Boolean = false, treat: Boolean = true) =
        TargetControl(
            TargetAction.SLEEP,
            sleep = TargetSleepAsk(
                SleepKind.AFTER_TIME,
                seconds,
                fadeSeconds = 0,
                switchOff = switchOff,
                stop = stop,
                treat = treat,
            ),
        )

    @Test
    fun `a film that ends before the timer still switches the TV off`() = runTest {
        val deck = deck()
        deck.playing("film")
        deck.control(after(900, switchOff = true))
        runCurrent()
        events.clear()

        deck.ended(failed = false)

        assertEquals("handed back, then off, once: $events", listOf("release", "switch off"), events)
        assertNull("the timer is spent", deck.state().sleep)
        assertFalse(deck.state().playing)
    }

    @Test
    fun `a film that ends before the timer is stopped and handed back when the end is stop`() = runTest {
        val deck = deck()
        deck.playing("film")
        deck.control(after(900, stop = true))
        runCurrent()
        events.clear()

        deck.ended(failed = false)

        assertEquals(listOf("release"), events)
        assertNull(deck.state().sleep)
        assertFalse(deck.state().linked)
    }

    @Test
    fun `a film that ends before the timer on a TV that cannot be switched off is only stopped`() = runTest {
        val deck = deck(canSwitchOff = false)
        deck.playing("film")
        deck.control(after(900, switchOff = true))
        runCurrent()
        events.clear()

        deck.ended(failed = false)

        assertFalse("never tried a switch off it cannot do: $events", "switch off" in events)
        assertNull(deck.state().sleep)
    }

    @Test
    fun `a sleep timer set by hand keeps ADR 166's behaviour, its film ending does not switch the TV off`() = runTest {
        val deck = deck()
        deck.playing("film")
        deck.control(after(900, switchOff = true, treat = false))
        runCurrent()
        events.clear()

        deck.ended(failed = false)

        assertFalse("a hand-set timer ends with its playback: $events", "switch off" in events)
        assertNull("and goes with it", deck.state().sleep)
    }

    @Test
    fun `the end action waits for the last item, an earlier one ending changes nothing`() = runTest {
        val deck = deck()
        deck.playing("part-one", "part-two")
        deck.control(after(900, switchOff = true))
        runCurrent()
        events.clear()

        deck.ended(failed = false)

        assertTrue("still playing the second part: $events", events.isEmpty())
        assertTrue(deck.state().playing)
        assertEquals(true, deck.state().sleep?.switchesOff)
    }
}
