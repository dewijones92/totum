package com.dewijones92.totum.cli.serve.targets

import com.dewijones92.totum.data.targets.QueueMode
import com.dewijones92.totum.data.targets.SleepKind
import com.dewijones92.totum.data.targets.Target
import com.dewijones92.totum.data.targets.TargetAction
import com.dewijones92.totum.data.targets.TargetControl
import com.dewijones92.totum.data.targets.TargetKind
import com.dewijones92.totum.data.targets.TargetPower
import com.dewijones92.totum.data.targets.TargetQueueRequest
import com.dewijones92.totum.data.targets.TargetSleepAsk
import com.dewijones92.totum.data.targets.TroubleKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class TargetDeckPowerOffTest {

    private val events = mutableListOf<String>()

    private inner class RecordingPlayer : TargetPlayer {
        override fun play(request: PlayRequest) {
            events += "play"
        }
        override fun pause(paused: Boolean) {
            events += if (paused) "pause" else "resume"
        }
        override fun seek(positionMs: Long) = Unit
        override fun volume(percent: Int) {
            events += "volume $percent"
        }
        override val volumePercent: Int? = 40
        override fun stop() {
            events += "stop"
        }
        override val positionMs: Long? = 1_000
        override val durationMs: Long? = 180_000
        override var onEnded: (failed: Boolean) -> Unit = {}
    }

    private inner class PowerLink(
        override val canSwitchOff: Boolean = true,
        var switchOffAnswer: String? = null,
        override val startsApp: Boolean = false,
        override var power: TargetPower? = null,
        override var powerNote: String? = null,
    ) : TargetLink {
        override suspend fun connect(): String? = "tv"

        override suspend fun release() {
            events += "release"
        }

        override suspend fun switchOff(): String? {
            events += "switch off"
            return switchOffAnswer
        }
    }

    private val dir = Files.createTempDirectory("deck-poweroff").toFile()
    private val sounds = TargetSounds { item, _ -> "https://sound/${item.item.id.value}" }
    private val tv = Target("lg-tv", "LG TV", TargetKind.TV)

    private fun TestScope.deck(link: TargetLink) = TargetDeck(
        tv,
        RecordingPlayer(),
        link,
        sounds,
        dir.resolve("tv-${link.hashCode()}.json"),
        clock = { testScheduler.currentTime },
        scope = backgroundScope,
    )

    private suspend fun TargetDeck.playing(vararg ids: String) {
        enqueue(TargetQueueRequest(ids.map { podcastItem(it) }, QueueMode.REPLACE))
    }

    private val switchOff = TargetControl(TargetAction.SWITCH_OFF)

    @Test
    fun `switching off a playing TV stops it, hands it back and only then switches it off`() = runTest {
        val deck = deck(PowerLink())
        deck.playing("a")
        events.clear()

        val state = deck.control(switchOff)

        assertEquals(listOf("stop", "release", "switch off"), events)
        assertFalse(state.playing)
        assertNull(state.problem)
    }

    @Test
    fun `switching off an idle TV just switches it off`() = runTest {
        val deck = deck(PowerLink())

        deck.control(switchOff)

        assertEquals(listOf("switch off"), events)
    }

    @Test
    fun `a switch off that fails says why, in words the phone shows as they are`() = runTest {
        val deck = deck(PowerLink(switchOffAnswer = "it has not been paired: run totum webos-pair"))

        val state = deck.control(switchOff)

        assertEquals("❌ LG TV didn't switch off: it has not been paired: run totum webos-pair", state.problem)
        assertEquals(TroubleKind.WONT_WAKE, state.trouble?.kind)
        assertEquals(state.problem, state.trouble?.reason)
    }

    @Test
    fun `a target with no way to switch off refuses, touches nothing and says so`() = runTest {
        val deck = deck(PowerLink(canSwitchOff = false))
        deck.playing("a")
        events.clear()

        val state = deck.control(switchOff)

        assertTrue("nothing was stopped or switched: $events", events.isEmpty())
        assertTrue(state.playing)
        assertEquals("❌ LG TV didn't switch off: Totum has no way to switch it off", state.problem)
    }

    @Test
    fun `the state says whether it can be switched off and whether picking it starts an app`() = runTest {
        val kodi = deck(PowerLink(startsApp = true))
        val plain = deck(object : TargetLink {
            override suspend fun connect(): String? = "x"

            override suspend fun release() = Unit
        })

        assertTrue(kodi.state().canSwitchOff)
        assertTrue(kodi.state().startsApp)
        assertFalse(plain.state().canSwitchOff)
        assertFalse(plain.state().startsApp)
    }

    @Test
    fun `what a wake is doing is shown while it wakes and not after`() = runTest {
        val link = PowerLink(power = TargetPower.WAKING, powerNote = "🔌 Starting Kodi on the Fire Stick…")
        val deck = deck(link)

        assertEquals("🔌 Starting Kodi on the Fire Stick…", deck.state().powerNote)

        link.power = TargetPower.ON
        assertNull(deck.state().powerNote)
    }

    @Test
    fun `the power flags are not saved with the queue`() = runTest {
        val link = PowerLink(startsApp = true)
        val deck = deck(link)
        deck.playing("a")
        val saved = dir.listFiles().orEmpty().first { it.name.startsWith("tv-${link.hashCode()}") }.readText()

        assertFalse(saved.contains("\"canSwitchOff\":true"))
        assertFalse(saved.contains("\"startsApp\":true"))
    }

    private fun after(seconds: Int, switchOff: Boolean) = TargetControl(
        TargetAction.SLEEP,
        sleep = TargetSleepAsk(SleepKind.AFTER_TIME, seconds, fadeSeconds = 10, switchOff = switchOff),
    )

    private suspend fun TestScope.moveTo(ms: Long) {
        advanceTimeBy(ms - testScheduler.currentTime)
        runCurrent()
    }

    @Test
    fun `a sleep timer set to switch off pauses, puts the volume back, then stops and switches the TV off`() = runTest {
        val deck = deck(PowerLink())
        deck.playing("a")
        deck.control(after(30, switchOff = true))
        runCurrent()
        events.clear()

        moveTo(40_000)

        assertEquals("pause", events.first { it == "pause" })
        val pauseAt = events.indexOf("pause")
        val restoreAt = events.indexOf("volume 40")
        val stopAt = events.indexOf("stop")
        val offAt = events.indexOf("switch off")
        assertTrue("paused, then volume back, then stopped, then off: $events", pauseAt < restoreAt)
        assertTrue("the volume was restored before the TV was stopped: $events", restoreAt < stopAt)
        assertTrue(
            "the TV was switched off last: $events",
            stopAt < offAt && offAt == events.lastIndex - 0 ||
                events.indexOf("release") < offAt
        )
        assertEquals(1, events.count { it == "switch off" })
    }

    @Test
    fun `a sleep timer is off by default, so without the option the TV is paused and left on`() = runTest {
        val deck = deck(PowerLink())
        deck.playing("a")
        deck.control(after(30, switchOff = false))
        runCurrent()
        events.clear()

        moveTo(40_000)

        assertTrue("paused: $events", "pause" in events)
        assertFalse("never switched off: $events", "switch off" in events)
        assertFalse("never stopped: $events", "stop" in events)
    }

    @Test
    fun `the state of an armed timer says it will switch off`() = runTest {
        val deck = deck(PowerLink())
        deck.playing("a")

        deck.control(after(30, switchOff = true))

        assertTrue(deck.state().sleep?.switchesOff == true)
        deck.control(after(30, switchOff = false))
        assertFalse(deck.state().sleep?.switchesOff == true)
    }

    @Test
    fun `a sleep timer after the last item switches the TV off once that item ends`() = runTest {
        val deck = deck(PowerLink())
        deck.playing("a", "b")
        deck.control(
            TargetControl(
                TargetAction.SLEEP,
                sleep = TargetSleepAsk(SleepKind.AFTER_ITEMS, fadeSeconds = 0, itemIds = listOf("a"), switchOff = true),
            ),
        )
        events.clear()

        deck.ended(failed = false)

        assertTrue("stopped, handed back, then off: $events", events.indexOf("stop") < events.indexOf("switch off"))
        assertEquals(1, events.count { it == "switch off" })
    }

    @Test
    fun `a timer that wants a switch off on a TV that cannot be switched off still stops it`() =
        runTest {
            val deck = deck(PowerLink(canSwitchOff = false))
            deck.playing("a")
            deck.control(after(30, switchOff = true))
            runCurrent()
            events.clear()

            moveTo(40_000)

            assertTrue("paused: $events", "pause" in events)
            assertFalse("no switch off was tried: $events", "switch off" in events)
        }
}
