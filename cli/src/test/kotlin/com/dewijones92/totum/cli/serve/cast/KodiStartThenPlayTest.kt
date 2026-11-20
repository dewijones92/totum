package com.dewijones92.totum.cli.serve.cast

import com.dewijones92.totum.cli.serve.power.Adb
import com.dewijones92.totum.cli.serve.power.AdbStartApp
import com.dewijones92.totum.cli.serve.power.AdbWakeStick
import com.dewijones92.totum.cli.serve.power.FakeAdb
import com.dewijones92.totum.cli.serve.power.SteppedPower
import com.dewijones92.totum.cli.serve.sound.ProcessedSound
import com.dewijones92.totum.cli.serve.targets.EnqueueResult
import com.dewijones92.totum.cli.serve.targets.TargetDeck
import com.dewijones92.totum.cli.serve.targets.TargetSounds
import com.dewijones92.totum.cli.serve.targets.podcastItem
import com.dewijones92.totum.data.targets.PlayOn
import com.dewijones92.totum.data.targets.PlayOnHint
import com.dewijones92.totum.data.targets.QueueMode
import com.dewijones92.totum.data.targets.Target
import com.dewijones92.totum.data.targets.TargetKind
import com.dewijones92.totum.data.targets.TargetPower
import com.dewijones92.totum.data.targets.TargetQueueRequest
import com.dewijones92.totum.data.targets.TroubleKind
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class KodiStartThenPlayTest {

    private val dir = Files.createTempDirectory("kodi-start-then-play").toFile()
    private val control = FakeCastControl()
    private val adb = FakeAdb()
    private val noteSeenAtEachProbe = mutableListOf<String?>()
    private val loadsSeenAtEachProbe = mutableListOf<Int>()
    private var now = 0L
    private var running = false
    private lateinit var deck: TargetDeck

    private fun power(): SteppedPower {
        val stick = Adb("192.168.0.211:5555", adb)
        return SteppedPower(
            label = "Kodi",
            wakeSteps = listOf(
                AdbWakeStick("Kodi", stick),
                AdbStartApp("Kodi", stick, "org.xbmc.kodi/.Splash", "the Fire Stick"),
            ),
            answers = {
                noteSeenAtEachProbe += deck.state().powerNote
                loadsSeenAtEachProbe += control.loads.size
                running
            },
            wakeFailed = "❌ Kodi didn't start",
            startsApp = true,
            timeoutMs = AdbStartApp.START_TIMEOUT_MS,
            pause = {
                now += it
                yield()
            },
            clock = { now },
        )
    }

    private fun deckWith(power: SteppedPower): TargetDeck {
        val streams = PiStreams(ProcessedSound("ffmpeg-not-run") { null }, null, 0)
        val player = ChromecastPlayer("Kodi", control, streams, wake = power, clock = { now })
        player.onLost = { reason -> runBlocking { deck.lost(reason) } }
        return TargetDeck(
            Target("kodi", "Kodi", TargetKind.TV),
            player,
            CastLink("192.168.0.211", control, power),
            TargetSounds { _, _ -> "https://sound/talk.mp3" },
            File(dir, "kodi.json"),
            clock = { now },
        ).also { deck = it }
    }

    private fun play() = runBlocking {
        deck.enqueue(TargetQueueRequest(listOf(podcastItem("a")), QueueMode.REPLACE))
    }

    @Test
    fun `a play to a Kodi that is not running wakes the stick, starts Kodi, waits for it, then plays`() {
        adb.onStart = { running = true }
        deckWith(power())

        val result = play() as EnqueueResult.Accepted

        assertTrue(adb.shellLines.contains("input keyevent KEYCODE_WAKEUP"))
        assertTrue(adb.shellLines.contains("am start -n org.xbmc.kodi/.Splash"))
        assertEquals("nothing was loaded until Kodi answered", listOf(0, 0), loadsSeenAtEachProbe.take(2))
        assertEquals(1, control.loads.size)
        assertTrue(result.state.playing)
        assertNull(result.state.problem)
        assertEquals(TargetPower.ON, result.state.power)
    }

    @Test
    fun `the phone is told Kodi is starting, in words, while it starts and not afterwards`() {
        adb.onStart = { running = true }
        deckWith(power())

        val result = play() as EnqueueResult.Accepted

        assertTrue(noteSeenAtEachProbe.toString(), "🔌 Starting Kodi on the Fire Stick…" in noteSeenAtEachProbe)
        assertNull(result.state.powerNote)
    }

    @Test
    fun `a Kodi that never starts holds the play and says it did not start`() {
        deckWith(power())

        val result = play() as EnqueueResult.Accepted

        assertTrue(control.loads.isEmpty())
        assertFalse(result.state.playing)
        assertEquals("❌ Kodi didn't start", result.state.problem)
        assertEquals(TroubleKind.WONT_WAKE, result.state.trouble?.kind)
        assertEquals(TargetPower.ASLEEP, result.state.power)
        assertTrue("gave up at $now ms", now in 60_000..61_000)
    }

    @Test
    fun `the state says picking it starts an app, so the picker can say so`() {
        deckWith(power())

        val state = deck.state()

        assertTrue(state.startsApp)
        assertFalse(state.canSwitchOff)
        val option = PlayOn.options(listOf(state.copy(power = TargetPower.ASLEEP)), atHome = true).last()
        assertEquals(PlayOnHint.STARTS_WHEN_PICKED, option.hint)
    }
}
