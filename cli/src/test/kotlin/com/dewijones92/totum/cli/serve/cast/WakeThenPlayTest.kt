package com.dewijones92.totum.cli.serve.cast

import com.dewijones92.totum.cli.serve.sound.ProcessedSound
import com.dewijones92.totum.cli.serve.targets.EnqueueResult
import com.dewijones92.totum.cli.serve.targets.TargetDeck
import com.dewijones92.totum.cli.serve.targets.TargetSounds
import com.dewijones92.totum.cli.serve.targets.podcastItem
import com.dewijones92.totum.data.targets.QueueMode
import com.dewijones92.totum.data.targets.Target
import com.dewijones92.totum.data.targets.TargetAction
import com.dewijones92.totum.data.targets.TargetControl
import com.dewijones92.totum.data.targets.TargetKind
import com.dewijones92.totum.data.targets.TargetPower
import com.dewijones92.totum.data.targets.TargetQueueRequest
import com.dewijones92.totum.data.targets.TroubleKind
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WakeThenPlayTest {

    private val dir = Files.createTempDirectory("wake-then-play").toFile()
    private val control = FakeCastControl()
    private val mac = byteArrayOf(0x20, 0x28, 0xbc.toByte(), 0x6b, 0xf7.toByte(), 0xf8.toByte())
    private val sent = mutableListOf<String>()
    private val powerSeenAtEachProbe = mutableListOf<TargetPower>()
    private val loadsSeenAtEachProbe = mutableListOf<Int>()
    private var now = 0L
    private var probes = 0
    private var answersFromProbe = Int.MAX_VALUE
    private lateinit var deck: TargetDeck

    private fun wake() = TvWake(
        label = "LG TV",
        mac = mac,
        broadcasts = listOf("255.255.255.255", "192.168.0.255"),
        sender = { _, address, port -> sent += "$address:$port" },
        answers = {
            powerSeenAtEachProbe += deck.state().power
            loadsSeenAtEachProbe += control.loads.size
            probes++ >= answersFromProbe
        },
        pause = {
            now += it
            yield()
        },
        clock = { now },
    )

    private fun deckWith(wake: TvWake?): TargetDeck {
        val streams = PiStreams(ProcessedSound("ffmpeg-not-run") { null }, null, 0)
        val player = ChromecastPlayer("LG TV", control, streams, wake = wake, clock = { now })
        player.onLost = { reason -> runBlocking { deck.lost(reason) } }
        return TargetDeck(
            Target("lg-tv", "LG TV", TargetKind.TV),
            player,
            CastLink("tv.local", control, wake),
            TargetSounds { _, _ -> "https://sound/talk.mp3" },
            File(dir, "lg.json"),
            clock = { now },
        ).also { deck = it }
    }

    private fun play() = runBlocking {
        deck.enqueue(TargetQueueRequest(listOf(podcastItem("a")), QueueMode.REPLACE))
    }

    @Test
    fun `a play to a sleeping TV wakes it, waits for it to answer, then plays`() {
        answersFromProbe = 3
        deckWith(wake())

        val result = play() as EnqueueResult.Accepted

        assertEquals(4, sent.size)
        assertEquals("the play waited: nothing was loaded while it probed", listOf(0, 0, 0, 0), loadsSeenAtEachProbe)
        assertEquals(1, control.loads.size)
        assertTrue(result.state.playing)
        assertEquals(TargetPower.ON, result.state.power)
        assertNull(result.state.problem)
        assertTrue(
            "the phone could see it waking: $powerSeenAtEachProbe",
            powerSeenAtEachProbe.any { it == TargetPower.WAKING },
        )
    }

    @Test
    fun `a TV that never wakes holds the play and says what to check`() {
        deckWith(wake())

        val result = play() as EnqueueResult.Accepted

        assertTrue(control.loads.isEmpty())
        assertEquals(false, result.state.playing)
        assertEquals(TargetPower.ASLEEP, result.state.power)
        assertEquals("❌ LG TV didn't wake: check 'Turn on via Wi-Fi' in its settings", result.state.problem)
    }

    @Test
    fun `a TV switched off mid-item stops the deck asleep with its place kept, and play wakes it and resumes`() =
        runBlocking {
            answersFromProbe = 0
            deckWith(wake())
            play()
            control.reading = CastReading(positionMs = 42_000, durationMs = 60_000, playing = true, atMs = now)
            assertEquals(1, control.loads.size)

            probes = 0
            answersFromProbe = 3
            control.onLost("the connection to LG TV was LOST")

            val off = deck.state()
            assertEquals(false, off.playing)
            assertEquals(TargetPower.ASLEEP, off.power)
            assertEquals(TroubleKind.SWITCHED_OFF, off.trouble?.kind)
            assertEquals("📺 LG TV was switched off", off.problem)
            assertEquals(42_000L, off.positionMs)
            assertEquals(0, off.at)

            val woken = deck.control(TargetControl(TargetAction.PLAY))

            assertTrue("the wake was sent: $sent", sent.isNotEmpty())
            assertEquals(2, control.loads.size)
            assertEquals(42_000L, control.loads.last().startAtMs)
            assertTrue(woken.playing)
            assertNull(woken.trouble)
        }

    @Test
    fun `a TV that already answers is played to without a wake`() {
        answersFromProbe = 0
        deckWith(wake())

        val result = play() as EnqueueResult.Accepted

        assertTrue(sent.isEmpty())
        assertTrue(result.state.playing)
        assertEquals(1, control.loads.size)
    }

    @Test
    fun `a TV with no MAC that cannot be reached says so as before and sends nothing`() {
        control.reachable = false
        deckWith(null)

        val result = play() as EnqueueResult.Accepted

        assertTrue(sent.isEmpty())
        assertEquals("📶 Couldn't reach LG TV: it isn't connected (off or out of range)", result.state.problem)
        assertEquals(TroubleKind.NOT_CONNECTED, result.state.trouble?.kind)
        assertEquals(TargetPower.ON, result.state.power)
    }

    @Test
    fun `a receiver slow to connect after the wake gets one more try`() {
        answersFromProbe = 1
        control.reachableAnswers.addAll(listOf(false, true))
        deckWith(wake())

        val result = play() as EnqueueResult.Accepted

        assertEquals(2, control.reachableAsked)
        assertTrue(result.state.playing)
    }

    @Test
    fun `a TV that answers the wake but never connects says it could not be reached, not that it did not wake`() {
        answersFromProbe = 1
        control.reachable = false
        deckWith(wake())

        val result = play() as EnqueueResult.Accepted

        assertEquals(2, control.reachableAsked)
        assertEquals("📶 Couldn't reach LG TV: it isn't connected (off or out of range)", result.state.problem)
        assertEquals(TroubleKind.NOT_CONNECTED, result.state.trouble?.kind)
    }

    @Test
    fun `the wake control wakes a sleeping TV and plays nothing`() = runBlocking {
        answersFromProbe = 2
        deckWith(wake())

        val state = deck.control(TargetControl(TargetAction.WAKE))

        assertEquals(4, sent.size)
        assertTrue(control.loads.isEmpty())
        assertEquals(TargetPower.ON, state.power)
        assertEquals(false, state.playing)
        assertNull(state.problem)
    }

    @Test
    fun `the wake control on a TV that is on sends no packet`() = runBlocking {
        answersFromProbe = 0
        deckWith(wake())

        deck.control(TargetControl(TargetAction.WAKE))

        assertTrue(sent.isEmpty())
        assertEquals(1, probes)
    }

    @Test
    fun `a failed wake from the wake control says what to check`() = runBlocking {
        deckWith(wake())

        val state = deck.control(TargetControl(TargetAction.WAKE))

        assertEquals("❌ LG TV didn't wake: check 'Turn on via Wi-Fi' in its settings", state.problem)
        assertEquals(TargetPower.ASLEEP, state.power)
    }
}
