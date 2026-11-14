package com.dewijones92.totum.cli.serve.targets

import com.dewijones92.totum.cli.serve.speakers.HardwareVolume
import com.dewijones92.totum.cli.serve.speakers.SpeakerTransport
import com.dewijones92.totum.cli.serve.speakers.SpeakerVolume
import com.dewijones92.totum.cli.serve.speakers.TransportVolume
import com.dewijones92.totum.data.targets.QueueMode
import com.dewijones92.totum.data.targets.SleepKind
import com.dewijones92.totum.data.targets.TargetAction
import com.dewijones92.totum.data.targets.TargetControl
import com.dewijones92.totum.data.targets.TargetQueueRequest
import com.dewijones92.totum.data.targets.TargetSleepAsk
import com.dewijones92.totum.data.targets.VolumeMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class TargetDeckVolumeSafetyTest {

    private class Transport(startRaw: Int, private val events: MutableList<String>) : SpeakerTransport {
        var raw = startRaw
        var gone = false
        val written = mutableListOf<Int>()
        override suspend fun volume(): TransportVolume =
            if (gone) TransportVolume.Unavailable("no A2DP transport") else TransportVolume.Level(raw)
        override suspend fun setVolume(raw: Int): TransportVolume {
            if (gone) return TransportVolume.Unavailable("no A2DP transport")
            this.raw = raw
            written += raw
            events += "hardware $raw"
            return TransportVolume.Level(raw)
        }
    }

    private class UnlinkingLink(private val transport: Transport, private val events: MutableList<String>) :
        TargetLink {
        override suspend fun connect(): String? = "bar-sink"
        override suspend fun release() {
            events += "release"
            transport.gone = true
        }
    }

    private val events = mutableListOf<String>()
    private val player = RecordingPlayer()
    private val dir = Files.createTempDirectory("deck-volume-safety").toFile()
    private val sounds = TargetSounds { item, _ -> "https://sound/${item.item.id.value}" }

    private fun TestScope.deck(volume: TargetVolume, link: TargetLink = ConnectingLink()) = TargetDeck(
        speaker(),
        player,
        link,
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

    private fun after(seconds: Int) =
        TargetControl(TargetAction.SLEEP, sleep = TargetSleepAsk(SleepKind.AFTER_TIME, seconds, 10))

    private val cancel = TargetControl(TargetAction.SLEEP, sleep = TargetSleepAsk(SleepKind.OFF))

    @Test
    fun `a stop in a fade puts the speaker's volume back before it lets the speaker go`() = runTest {
        val transport = Transport(HardwareVolume.rawOf(40), events)
        val deck = deck(SpeakerVolume("bar", transport, player), UnlinkingLink(transport, events))
        deck.playing("a")
        deck.control(after(30))
        runCurrent()
        moveTo(27_000)
        assertTrue("it faded: ${transport.written}", transport.written.isNotEmpty())

        deck.control(TargetControl(TargetAction.STOP))

        assertEquals("the level it began at is the last write", HardwareVolume.rawOf(40), transport.written.last())
        assertTrue(
            "the volume went back before the release: $events",
            events.indexOfLast { it.startsWith("hardware") } < events.indexOf("release"),
        )
        assertEquals("mpv was never made the other control", emptyList<Int>(), player.volumeCommands)
    }

    @Test
    fun `a speaker that is switched off mid-fade is not put right through mpv`() = runTest {
        val transport = Transport(HardwareVolume.rawOf(40), events)
        val deck = deck(SpeakerVolume("bar", transport, player))
        deck.playing("a")
        deck.control(after(30))
        runCurrent()
        moveTo(27_000)
        transport.gone = true

        deck.lost("the speaker went away")

        assertEquals(emptyList<Int>(), player.volumeCommands)
    }

    private class GatedVolume(override var percent: Int?) : TargetVolume {
        val sets = mutableListOf<Int>()
        var gate: CompletableDeferred<Unit>? = null
        var blocked = false
        override val mode: VolumeMode? get() = VolumeMode.HARDWARE
        override suspend fun set(percent: Int) {
            val held = gate
            if (held != null && percent < (this.percent ?: 0) && !blocked) {
                blocked = true
                withContext(NonCancellable) { held.await() }
            }
            sets += percent
            this.percent = percent
        }
        override suspend fun refresh() = Unit
    }

    @Test
    fun `a fade step still writing when the timer is cancelled lands before the volume is put back`() = runTest {
        val volume = GatedVolume(40).apply { gate = CompletableDeferred() }
        val deck = deck(volume)
        deck.playing("a")
        deck.control(after(30))
        runCurrent()
        moveTo(25_000)
        assertTrue("a fade write is in flight", volume.blocked)

        val cancelled = launch { deck.control(cancel) }
        runCurrent()
        volume.gate!!.complete(Unit)
        runCurrent()
        cancelled.join()

        assertEquals("the put-back is the last write: ${volume.sets}", 40, volume.sets.last())
        assertEquals(40, volume.percent)
    }

    private class SlowVolume : TargetVolume {
        override var percent: Int? = 30
        override val mode: VolumeMode? = VolumeMode.HARDWARE
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        var slowSet = false
        var slowRefresh = false
        override suspend fun set(percent: Int) {
            if (slowSet) {
                entered.complete(Unit)
                gate.await()
            }
            this.percent = percent
        }
        override suspend fun refresh() {
            if (slowRefresh) {
                entered.complete(Unit)
                gate.await()
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.Default)

    @After
    fun stopScope() = scope.cancel()

    private fun realDeck(volume: TargetVolume) = TargetDeck(
        speaker("mic"),
        player,
        ConnectingLink(),
        sounds,
        dir.resolve("mic.json"),
        volumeControl = volume,
        scope = scope,
    )

    @Test
    fun `a slow volume write does not hold the deck, so a pause is not stuck behind it`() = runBlocking {
        val volume = SlowVolume()
        val deck = realDeck(volume)
        deck.enqueue(TargetQueueRequest(listOf(podcastItem("a")), QueueMode.REPLACE))
        volume.slowSet = true
        val slow = scope.async { deck.control(TargetControl(TargetAction.VOLUME, volume = 40)) }
        volume.entered.await()

        val paused = withTimeout(3_000) { deck.control(TargetControl(TargetAction.PAUSE)) }

        assertFalse(paused.playing)
        volume.gate.complete(Unit)
        slow.await()
        assertEquals(40, volume.percent)
    }

    @Test
    fun `a slow level read in the beat does not hold the deck`() = runBlocking {
        val volume = SlowVolume()
        val deck = realDeck(volume)
        deck.enqueue(TargetQueueRequest(listOf(podcastItem("a")), QueueMode.REPLACE))
        volume.slowRefresh = true
        val beat = scope.async { deck.tick() }
        volume.entered.await()

        val paused = withTimeout(3_000) { deck.control(TargetControl(TargetAction.PAUSE)) }

        assertFalse(paused.playing)
        volume.gate.complete(Unit)
        beat.await()
    }
}
