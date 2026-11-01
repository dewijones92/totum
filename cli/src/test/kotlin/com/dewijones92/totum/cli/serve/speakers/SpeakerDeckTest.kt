package com.dewijones92.totum.cli.serve.speakers

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.backup.BackupItem
import com.dewijones92.totum.data.backup.toBackup
import com.dewijones92.totum.data.speakers.QueueMode
import com.dewijones92.totum.data.speakers.Speaker
import com.dewijones92.totum.data.speakers.SpeakerAction
import com.dewijones92.totum.data.speakers.SpeakerControl
import com.dewijones92.totum.data.speakers.SpeakerQueueRequest
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SpeakerDeckTest {

    private class FakePlayer : SpeakerPlayer {
        val played = mutableListOf<String>()
        var paused = false
        var stopped = false
        var vol = -1
        override fun play(url: String, sink: String, volume: Int) {
            played += "$url@$sink"
            paused = false
            stopped = false
            vol = volume
        }
        override fun pause(paused: Boolean) { this.paused = paused }
        override fun seek(positionMs: Long) = Unit
        override fun volume(percent: Int) { vol = percent }
        override fun stop() { stopped = true }
        override val positionMs: Long? = 1_000
        override val durationMs: Long? = 60_000
        override var onEnded: (failed: Boolean) -> Unit = {}
    }

    private class FakeLink(var sink: String? = "bar-sink") : SpeakerLink {
        var connects = 0
        var released = 0
        override suspend fun connect(): String? = sink.also { connects++ }
        override suspend fun release() { released++ }
    }

    private fun item(id: String): BackupItem = PlayableItem(
        MediaItem(
            MediaItemId(id),
            SourceId("feed"),
            "Bluey $id",
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://example.com/$id.mp3"),
        ),
        PlayHandle.Podcast(),
    ).toBackup()
    private val player = FakePlayer()
    private val link = FakeLink()
    private val dir = Files.createTempDirectory("speakers").toFile()
    private val sounds = SpeakerSounds { item ->
        item.item.id.value.takeIf { it != "missing" }?.let { "https://sound/$it" }
    }
    private val lookedAhead = mutableListOf<String>()
    private fun deck() = SpeakerDeck(
        Speaker(
            "bar",
            "LG soundbar"
        ),
        player,
        link,
        sounds,
        dir.resolve("bar.json"),
        lookAhead = {
            lookedAhead += it.item.id.value
        }
    )

    @Test
    fun `a queue sent to the speaker plays its first item on the speaker's sink, then each next one`() = runBlocking {
        val deck = deck()
        deck.enqueue(SpeakerQueueRequest(listOf(item("a"), item("b")), QueueMode.REPLACE))
        assertEquals(listOf("https://sound/a@bar-sink"), player.played)
        assertTrue(deck.state().playing)
        deck.ended(failed = false)
        assertEquals("https://sound/b@bar-sink", player.played.last())
        deck.ended(failed = false)
        assertFalse("the end of the queue stops", deck.state().playing)
        assertEquals("and hands the speaker back", 1, link.released)
    }

    @Test
    fun `an item with no sound is passed over, and an unreachable speaker says so`() = runBlocking {
        deck().enqueue(SpeakerQueueRequest(listOf(item("missing"), item("b")), QueueMode.REPLACE))
        assertEquals(listOf("https://sound/b@bar-sink"), player.played)
        link.sink = null
        val deck = deck()
        deck.enqueue(SpeakerQueueRequest(listOf(item("c")), QueueMode.REPLACE))
        assertFalse(deck.state().playing)
        assertTrue(deck.state().problem!!.isNotBlank())
    }

    @Test
    fun `controls pause, skip, jump, set the volume, remove and stop`() = runBlocking {
        val deck = deck()
        deck.enqueue(SpeakerQueueRequest(listOf(item("a"), item("b"), item("c")), QueueMode.REPLACE))
        deck.control(SpeakerControl(SpeakerAction.PAUSE))
        assertTrue(player.paused)
        deck.control(SpeakerControl(SpeakerAction.PLAY))
        assertFalse(player.paused)
        deck.control(SpeakerControl(SpeakerAction.NEXT))
        assertEquals("b", deck.state().current?.item?.itemId)
        deck.control(SpeakerControl(SpeakerAction.JUMP, index = 2))
        assertEquals("https://sound/c@bar-sink", player.played.last())
        deck.control(SpeakerControl(SpeakerAction.VOLUME, volume = 40))
        assertEquals(40, player.vol)
        deck.control(SpeakerControl(SpeakerAction.REMOVE, index = 0))
        assertEquals(listOf("b", "c"), deck.state().entries.map { it.item.itemId })
        deck.control(SpeakerControl(SpeakerAction.STOP))
        assertTrue(player.stopped)
        assertFalse(deck.state().playing)
        assertEquals(1, link.released)
    }

    @Test
    fun `adding to an idle speaker starts it, and a group comes with its items`() = runBlocking {
        val deck = deck()
        deck.enqueue(
            SpeakerQueueRequest(listOf(item("a"), item("b")), QueueMode.END, groupId = "bluey", groupTitle = "Bluey")
        )
        assertEquals(listOf("https://sound/a@bar-sink"), player.played)
        assertEquals("Bluey", deck.state().entries.first().groupTitle)
    }

    @Test
    fun `when an item starts the next one is looked up ahead, so the hand-over does not wait for it`() = runBlocking {
        val deck = deck()
        deck.enqueue(SpeakerQueueRequest(listOf(item("a"), item("b"), item("c")), QueueMode.REPLACE))
        assertEquals(listOf("b"), lookedAhead)
        deck.ended(failed = false)
        assertEquals(listOf("b", "c"), lookedAhead)
    }

    @Test
    fun `the queue survives a restart, paused rather than playing by itself`() = runBlocking {
        deck().enqueue(SpeakerQueueRequest(listOf(item("a"), item("b")), QueueMode.REPLACE))
        val again = deck()
        assertEquals(listOf("a", "b"), again.state().entries.map { it.item.itemId })
        assertFalse(again.state().playing)
    }
}
