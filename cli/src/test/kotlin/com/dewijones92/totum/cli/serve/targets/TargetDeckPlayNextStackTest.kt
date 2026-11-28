package com.dewijones92.totum.cli.serve.targets

import com.dewijones92.totum.data.targets.QueueMode
import com.dewijones92.totum.data.targets.TargetAction
import com.dewijones92.totum.data.targets.TargetControl
import com.dewijones92.totum.data.targets.TargetQueueRequest
import com.dewijones92.totum.data.targets.TargetState
import com.dewijones92.totum.data.targets.TargetsJson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class TargetDeckPlayNextStackTest {

    private val player = RecordingPlayer()
    private val dir = Files.createTempDirectory("deck-stack").toFile()
    private val sounds = TargetSounds { item, _ -> "https://sound/${item.item.id.value}" }
    private val file = dir.resolve("bar.json")

    private fun deck() = TargetDeck(speaker(), player, ConnectingLink(), sounds, file)

    private fun TargetDeck.order() = state().entries.map { it.item.itemId }

    private suspend fun TargetDeck.playNext(vararg ids: String) {
        enqueue(TargetQueueRequest(ids.map { podcastItem(it) }, QueueMode.NEXT))
    }

    @Test
    fun `play next on A then B then C plays them in that order on a target`() = runBlocking {
        val deck = deck()
        deck.enqueue(TargetQueueRequest(listOf("now", "x", "y").map { podcastItem(it) }, QueueMode.REPLACE))

        deck.playNext("a")
        deck.playNext("b")
        deck.playNext("c")

        assertEquals(listOf("now", "a", "b", "c", "x", "y"), deck.order())
    }

    @Test
    fun `a group sent as a play next stacks after the last one`() = runBlocking {
        val deck = deck()
        deck.enqueue(TargetQueueRequest(listOf("now", "x").map { podcastItem(it) }, QueueMode.REPLACE))

        deck.playNext("a")
        deck.enqueue(
            TargetQueueRequest(
                listOf("t1", "t2").map { podcastItem(it) },
                QueueMode.NEXT,
                groupId = "album",
                groupTitle = "Album",
            ),
        )
        deck.playNext("c")

        assertEquals(listOf("now", "a", "t1", "t2", "c", "x"), deck.order())
    }

    @Test
    fun `the chain starts again once the target moves to another item`() = runBlocking {
        val deck = deck()
        deck.enqueue(TargetQueueRequest(listOf("now", "x").map { podcastItem(it) }, QueueMode.REPLACE))
        deck.playNext("a")
        deck.playNext("b")

        deck.control(TargetControl(TargetAction.NEXT))
        deck.playNext("d")

        assertEquals(listOf("now", "a", "d", "b", "x"), deck.order())
    }

    @Test
    fun `the chain survives the Pi restarting`() = runBlocking {
        val before = deck()
        before.enqueue(TargetQueueRequest(listOf("now", "x").map { podcastItem(it) }, QueueMode.REPLACE))
        before.playNext("a")

        val after = deck()
        after.playNext("b")

        assertEquals(listOf("now", "a", "b", "x"), after.order())
    }

    @Test
    fun `the state sent to the phone says nothing about a chain until there is one`() = runBlocking {
        val deck = deck()
        deck.enqueue(TargetQueueRequest(listOf("now", "x").map { podcastItem(it) }, QueueMode.REPLACE))

        assertNull(deck.state().nextStack)
        val wire = TargetsJson.encodeToString(TargetState.serializer(), deck.state())
        assertFalse("the wire shape other adapters read is unchanged: $wire", wire.contains("nextStack"))
        deck.playNext("a")
        assertEquals("a", deck.state().nextStack?.tail)
    }

    @Test
    fun `play now on a target still goes straight after what is playing, never behind the chain`() = runBlocking {
        val deck = deck()
        deck.enqueue(TargetQueueRequest(listOf("now", "x").map { podcastItem(it) }, QueueMode.REPLACE))
        deck.playNext("a")
        deck.playNext("b")

        deck.enqueue(TargetQueueRequest(listOf(podcastItem("p")), QueueMode.PLAY))

        assertEquals(listOf("now", "p", "a", "b", "x"), deck.order())
    }
}
