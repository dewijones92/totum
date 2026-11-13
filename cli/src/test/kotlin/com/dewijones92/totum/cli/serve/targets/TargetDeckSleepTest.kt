package com.dewijones92.totum.cli.serve.targets

import com.dewijones92.totum.data.targets.QueueMode
import com.dewijones92.totum.data.targets.SleepKind
import com.dewijones92.totum.data.targets.TargetAction
import com.dewijones92.totum.data.targets.TargetControl
import com.dewijones92.totum.data.targets.TargetQueueRequest
import com.dewijones92.totum.data.targets.TargetSleepAsk
import com.dewijones92.totum.data.targets.VolumeMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class TargetDeckSleepTest {

    private class FadePlayer(var base: Int? = 40) : TargetPlayer {
        val requests = mutableListOf<PlayRequest>()
        var paused = false
        var stopped = false
        val volumeCommands = mutableListOf<Int>()
        var positionValue: Long? = 1_000
        var durationValue: Long? = 180_000
        override fun play(request: PlayRequest) {
            requests += request
            paused = false
            stopped = false
        }
        override fun pause(paused: Boolean) {
            this.paused = paused
        }
        override fun seek(positionMs: Long) = Unit
        override fun volume(percent: Int) {
            volumeCommands += percent
            base = percent
        }
        override val volumePercent: Int? get() = base
        override fun stop() {
            stopped = true
        }
        override val positionMs: Long? get() = positionValue
        override val durationMs: Long? get() = durationValue
        override var onEnded: (failed: Boolean) -> Unit = {}
    }

    private class OwnVolume(override var percent: Int?) : TargetVolume {
        val sets = mutableListOf<Int>()
        var refreshes = 0
        override val mode: VolumeMode? get() = VolumeMode.HARDWARE
        override suspend fun set(percent: Int) {
            sets += percent
            this.percent = percent
        }
        override suspend fun refresh() {
            refreshes++
        }
    }

    private class Link : TargetLink {
        override suspend fun connect(): String? = "bar-sink"
        override suspend fun release() = Unit
    }

    private val player = FadePlayer()
    private val dir = Files.createTempDirectory("deck-sleep").toFile()
    private val sounds = TargetSounds { item, _ -> "https://sound/${item.item.id.value}" }

    private fun TestScope.deck(volume: TargetVolume? = null) = TargetDeck(
        speaker(),
        player,
        Link(),
        sounds,
        dir.resolve("bar.json"),
        volumeControl = volume,
        clock = { testScheduler.currentTime },
        scope = backgroundScope,
    )

    private suspend fun TestScope.moveTo(ms: Long) {
        advanceTimeBy(ms - testScheduler.currentTime)
        runCurrent()
    }

    private suspend fun TargetDeck.playing(vararg ids: String) {
        enqueue(TargetQueueRequest(ids.map { podcastItem(it) }, QueueMode.REPLACE))
    }

    private fun after(seconds: Int, fade: Int = 10) =
        TargetControl(TargetAction.SLEEP, sleep = TargetSleepAsk(SleepKind.AFTER_TIME, seconds, fade))

    private fun afterItems(vararg ids: String, fade: Int = 20) = TargetControl(
        TargetAction.SLEEP,
        sleep = TargetSleepAsk(SleepKind.AFTER_ITEMS, fadeSeconds = fade, itemIds = ids.toList()),
    )

    private val cancel = TargetControl(TargetAction.SLEEP, sleep = TargetSleepAsk(SleepKind.OFF))

    @Test
    fun `a countdown holds the volume, ramps it down steadily, pauses at the end and puts it back where it began`() =
        runTest {
            val deck = deck()
            deck.playing("a")
            deck.control(after(30))
            runCurrent()

            moveTo(19_900)
            assertTrue("no ramp before the last ten seconds", player.volumeCommands.isEmpty())

            moveTo(25_000)
            assertEquals(13, player.volumeCommands.last())

            moveTo(31_000)
            val ramp = player.volumeCommands.dropLast(1)
            assertEquals("the last command puts it back", 40, player.volumeCommands.last())
            assertEquals("silence was reached first", 0, ramp.last())
            assertEquals("it only ever went down", ramp, ramp.sortedDescending())
            assertTrue("never above where it began: $ramp", ramp.all { it <= 40 })
            assertTrue(player.paused)
            assertFalse(deck.state().playing)
            assertNull(deck.state().sleep)
        }

    @Test
    fun `the volume is put back only after the pause has had a moment to land`() = runTest {
        val deck = deck()
        deck.playing("a")
        deck.control(after(10, fade = 4))
        runCurrent()

        moveTo(10_100)

        assertTrue("paused at once", player.paused)
        assertEquals("still silent right after the pause", 0, player.volumeCommands.last())

        moveTo(11_500)
        assertEquals(40, player.volumeCommands.last())
    }

    @Test
    fun `a speaker whose volume is not known is paused at the end with no ramp and nothing touched`() = runTest {
        player.base = null
        val deck = deck()
        deck.playing("a")
        deck.control(after(10, fade = 4))
        runCurrent()

        moveTo(11_000)

        assertTrue(player.volumeCommands.toString(), player.volumeCommands.isEmpty())
        assertTrue(player.paused)
        assertNull(deck.state().sleep)
    }

    @Test
    fun `cancelling in the middle of a fade puts the volume back and leaves it playing`() = runTest {
        val deck = deck()
        deck.playing("a")
        deck.control(after(30))
        runCurrent()
        moveTo(26_000)
        assertTrue(player.volumeCommands.last() < 40)

        deck.control(cancel)

        assertEquals(40, player.volumeCommands.last())
        assertFalse(player.paused)
        assertNull(deck.state().sleep)
        moveTo(40_000)
        assertFalse("nothing fires after a cancel", player.paused)
    }

    @Test
    fun `stopping the target during a fade puts the volume back`() = runTest {
        val deck = deck()
        deck.playing("a")
        deck.control(after(30))
        runCurrent()
        moveTo(26_000)

        deck.control(TargetControl(TargetAction.STOP))

        assertEquals(40, player.volumeCommands.last())
        assertNull(deck.state().sleep)
    }

    @Test
    fun `a new timer replaces the old one and the old fade is undone first`() = runTest {
        val deck = deck()
        deck.playing("a")
        deck.control(after(30))
        runCurrent()
        moveTo(26_000)

        deck.control(after(600))

        assertEquals(40, player.volumeCommands.last())
        assertEquals(600_000L, deck.state().sleep!!.remainingMs)
    }

    @Test
    fun `the state says what is armed, how long is left and whether it is fading`() = runTest {
        val deck = deck()
        deck.playing("a")
        deck.control(after(60))
        runCurrent()

        moveTo(10_000)
        val early = deck.state().sleep
        assertNotNull(early)
        assertEquals(SleepKind.AFTER_TIME, early!!.kind)
        assertEquals(50_000L, early.remainingMs)
        assertFalse(early.fading)
        assertEquals(10, early.fadeSeconds)

        moveTo(55_000)
        assertTrue(deck.state().sleep!!.fading)
    }

    @Test
    fun `a timer shorter than the fade fades over its whole length and does not jump`() = runTest {
        val deck = deck()
        deck.playing("a")
        deck.control(after(4, fade = 20))
        runCurrent()

        moveTo(500)

        assertTrue("first step is small: ${player.volumeCommands}", player.volumeCommands.first() >= 30)
    }

    @Test
    fun `stopping after this item fades over its last seconds, then stops instead of going on to the next`() = runTest {
        val deck = deck()
        deck.playing("a", "b")
        deck.control(afterItems("a"))
        runCurrent()
        assertTrue(player.volumeCommands.isEmpty())

        player.positionValue = 170_000
        moveTo(1_500)
        assertEquals(13, player.volumeCommands.last())

        player.positionValue = 179_000
        moveTo(2_500)
        assertTrue(player.volumeCommands.last() <= 1)

        deck.ended(failed = false)

        assertEquals("b was not started", 1, player.requests.size)
        assertFalse(deck.state().playing)
        assertTrue(player.stopped)
        assertEquals("the next play will be b", 1, deck.state().at)
        assertEquals(40, player.volumeCommands.last())
        assertNull(deck.state().sleep)
    }

    @Test
    fun `an item with no known length is never faded, though the timer still stops it`() = runTest {
        player.durationValue = null
        val deck = deck()
        deck.playing("a", "b")
        deck.control(afterItems("a"))
        player.positionValue = 170_000
        moveTo(3_000)

        assertTrue(player.volumeCommands.isEmpty())
        deck.ended(failed = false)
        assertEquals(1, player.requests.size)
        assertFalse(deck.state().playing)
    }

    @Test
    fun `stopping at the end of a group waits for the last of its items`() = runTest {
        val deck = deck()
        deck.playing("a", "b", "c")
        deck.control(afterItems("a", "b"))

        deck.ended(failed = false)
        assertEquals("b started", 2, player.requests.size)
        assertTrue(deck.state().playing)
        assertEquals(1, deck.state().sleep!!.itemsLeft)

        deck.ended(failed = false)
        assertEquals("c was not started", 2, player.requests.size)
        assertFalse(deck.state().playing)
    }

    @Test
    fun `moving off the items the timer waits on pauses and puts the volume back`() = runTest {
        val deck = deck()
        deck.playing("a", "b")
        deck.control(afterItems("a"))
        player.positionValue = 175_000
        moveTo(2_000)
        assertTrue(player.volumeCommands.last() < 40)

        deck.control(TargetControl(TargetAction.NEXT))

        assertEquals(40, player.volumeCommands.last())
        assertTrue("paused rather than carrying on", player.paused)
        assertFalse(deck.state().playing)
        assertNull(deck.state().sleep)
    }

    @Test
    fun `a timer for items that are not what is playing is refused as out of date`() = runTest {
        val deck = deck()
        deck.playing("a", "b")

        try {
            deck.control(afterItems("zzz"))
            fail("expected the phone's view to be called stale")
        } catch (stale: StaleEdit) {
            assertNull(stale.state.sleep)
        }
        assertNull(deck.state().sleep)
    }

    @Test
    fun `a volume the person sets during a fade is followed down and never lifted above where it began`() = runTest {
        val deck = deck()
        deck.playing("a")
        deck.control(after(30))
        runCurrent()
        moveTo(25_000)

        deck.control(TargetControl(TargetAction.VOLUME, volume = 20))
        moveTo(26_000)
        assertTrue("fades from 20 now: ${player.volumeCommands.takeLast(3)}", player.volumeCommands.last() < 8)

        deck.control(TargetControl(TargetAction.VOLUME, volume = 90))
        moveTo(27_000)
        assertTrue("not lifted: ${player.volumeCommands.takeLast(3)}", player.volumeCommands.takeLast(3).all { it < 20 })

        deck.control(cancel)
        assertEquals("put back to the lowest it was", 20, player.volumeCommands.last())
    }

    @Test
    fun `a speaker with its own volume is faded through it from where it is, and put back there`() = runTest {
        val own = OwnVolume(60)
        val deck = deck(own)
        deck.playing("a")
        deck.control(after(30))
        runCurrent()

        moveTo(26_000)
        assertTrue("faded through the speaker's own volume: ${own.sets}", own.sets.isNotEmpty())
        assertTrue(own.sets.all { it <= 60 })
        assertTrue("the player's own volume is untouched: ${player.volumeCommands}", player.volumeCommands.isEmpty())
        assertTrue("read before it began", own.refreshes >= 1)

        moveTo(32_000)
        assertTrue(player.paused)
        assertEquals(60, own.sets.last())
        assertEquals(60, own.percent)
    }

    @Test
    fun `a speaker whose own volume is not known is not faded, so nothing is ever raised from a guess`() = runTest {
        val own = OwnVolume(null)
        val deck = deck(own)
        deck.playing("a")
        deck.control(after(10, fade = 4))
        runCurrent()

        moveTo(12_000)

        assertTrue(own.sets.toString(), own.sets.isEmpty())
        assertTrue(player.volumeCommands.isEmpty())
        assertTrue(player.paused)
    }

    @Test
    fun `a sleep control is not saved with the queue, so a restart forgets it`() = runTest {
        val deck = deck()
        deck.playing("a")
        deck.control(after(600))

        assertFalse(dir.resolve("bar.json").readText().contains("AFTER_TIME"))
        assertNull(deck().state().sleep)
    }
}
