package com.dewijones92.totum.cli.serve.targets

import com.dewijones92.totum.data.targets.KeepAwake
import com.dewijones92.totum.data.targets.QueueMode
import com.dewijones92.totum.data.targets.TargetAction
import com.dewijones92.totum.data.targets.TargetControl
import com.dewijones92.totum.data.targets.TargetQueueRequest
import com.dewijones92.totum.data.targets.VolumeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class TargetDeckSpeakerTest {

    private class FakeVolume : TargetVolume {
        val sets = mutableListOf<Int>()
        var refreshes = 0
        override var percent: Int? = null
        override var mode: VolumeMode? = null
        override suspend fun set(percent: Int) {
            sets += percent
            this.percent = percent
            mode = VolumeMode.HARDWARE
        }
        override suspend fun refresh() {
            refreshes++
        }
    }

    private class FakeKeeper(
        override var state: KeepAwake? = KeepAwake.KEPT,
        var checks: Boolean = true,
    ) : TargetKeeper {
        val beats = mutableListOf<Boolean>()
        override suspend fun beat(busy: Boolean): Boolean {
            beats += busy
            return checks
        }
    }

    private val player = RecordingPlayer()
    private val link = ConnectingLink()
    private val dir = Files.createTempDirectory("speaker-deck").toFile()
    private val volume = FakeVolume()
    private val keeper = FakeKeeper()
    private val sounds = TargetSounds { item, _ -> "https://sound/${item.item.id.value}" }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun stop() = scope.cancel()

    private fun deck(
        volumeControl: TargetVolume? = volume,
        theKeeper: TargetKeeper? = keeper,
    ) = TargetDeck(
        speaker("mic"),
        player,
        link,
        sounds,
        dir.resolve("mic.json"),
        volumeControl = volumeControl,
        keeper = theKeeper,
    )

    private fun send(id: String) = TargetQueueRequest(listOf(podcastItem(id)), QueueMode.REPLACE)

    @Test
    fun `the phone's volume goes to the speaker's own control, and mpv is not told anything`() = runBlocking {
        val deck = deck()
        deck.enqueue(send("a"))

        val state = deck.control(TargetControl(TargetAction.VOLUME, volume = 40))

        assertEquals(listOf(40), volume.sets)
        assertEquals(emptyList<Int>(), player.volumeCommands)
        assertEquals(40, state.volume)
        assertEquals(VolumeMode.HARDWARE, state.volumeMode)
    }

    @Test
    fun `a speaker with no volume control behind it keeps the old path, mpv's`() = runBlocking {
        val deck = deck(volumeControl = null)
        deck.enqueue(send("a"))

        val state = deck.control(TargetControl(TargetAction.VOLUME, volume = 40))

        assertEquals(listOf(40), player.volumeCommands)
        assertEquals(40, state.volume)
        assertNull(state.volumeMode)
    }

    @Test
    fun `a play never sets the volume, only reads it, and the speaker's own level is what the phone sees`() =
        runBlocking {
            val deck = deck()
            volume.percent = 22
            volume.mode = VolumeMode.HARDWARE

            val state = deck.enqueue(send("a"))

            assertTrue(state is EnqueueResult.Accepted)
            assertEquals(emptyList<Int>(), volume.sets)
            assertEquals(emptyList<Int>(), player.volumeCommands)
            assertEquals(22, deck.state().volume)
            assertTrue("the level is read after the link comes up", volume.refreshes >= 1)
        }

    @Test
    fun `the keep-awake state is part of the target's state, and absent when it is not kept`() = runBlocking {
        assertEquals(KeepAwake.KEPT, deck().state().keepAwake)
        assertNull(deck(theKeeper = null).state().keepAwake)
    }

    @Test
    fun `the keeper is told whether something is playing, and the level is re-read only when it checked`() =
        runBlocking {
            val deck = deck()

            deck.keepAwake()
            assertEquals(listOf(false), keeper.beats)
            assertEquals(1, volume.refreshes)

            deck.enqueue(send("a"))
            val before = volume.refreshes
            keeper.checks = false
            deck.keepAwake()

            assertEquals(listOf(false, true), keeper.beats)
            assertEquals("no check, no re-read", before, volume.refreshes)
        }

    @Test
    fun `a keeper that is outside its window or reconnecting does not trigger a re-read`() = runBlocking {
        val deck = deck()
        keeper.state = KeepAwake.RECONNECTING

        deck.keepAwake()

        assertEquals(0, volume.refreshes)
    }

    @Test
    fun `the volume mode and keep-awake state are never saved with the queue`() = runBlocking {
        val deck = deck()
        deck.enqueue(send("a"))
        deck.control(TargetControl(TargetAction.VOLUME, volume = 40))

        val saved = dir.resolve("mic.json").readText()

        assertTrue(saved, "\"volumeMode\":null" in saved && "\"keepAwake\":null" in saved)
    }
}
