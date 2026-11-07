package com.dewijones92.totum.cli.serve.targets

import com.dewijones92.totum.data.targets.QueueMode
import com.dewijones92.totum.data.targets.Target
import com.dewijones92.totum.data.targets.TargetKind
import com.dewijones92.totum.data.targets.TargetQueueRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class TargetDecksTest {

    private val dir = Files.createTempDirectory("decks").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var now = 1_000_000L
    private val sounds = TargetSounds { item, _ -> "https://sound/${item.item.id.value}" }

    private val bar = RecordingPlayer()
    private val barLink = ConnectingLink()
    private val tv = RecordingPlayer()
    private val tvLink = ConnectingLink("10.0.0.5")
    private var scans = 0

    private val speakers = TargetSource {
        scans++
        listOf(FoundTarget(speaker("bar")) { TargetKit(bar, barLink) })
    }
    private val televisions = TargetSource {
        listOf(FoundTarget(Target("lg", "LG TV", TargetKind.TV)) { TargetKit(tv, tvLink) })
    }

    @After
    fun stop() = scope.cancel()

    private fun decks(vararg sources: TargetSource) =
        TargetDecks(dir, scope, sounds, sources.toList(), clock = { now })

    private fun send(id: String) = TargetQueueRequest(listOf(podcastItem(id)), QueueMode.REPLACE)

    @Test
    fun `every source's targets are listed together, each with its own state`() = runBlocking {
        val decks = decks(speakers, televisions)

        val all = decks.all()

        assertEquals(listOf("bar", "lg"), all.map { it.target.id })
        assertEquals(listOf(TargetKind.SPEAKER, TargetKind.TV), all.map { it.target.kind })
    }

    @Test
    fun `a deck keeps its queue between asks and each target has its own`() = runBlocking {
        val decks = decks(speakers, televisions)

        (decks.deck("bar") ?: error("no bar")).enqueue(send("a"))

        assertSame(decks.deck("bar"), decks.deck("bar"))
        val states = decks.all().associateBy { it.target.id }
        assertEquals(1, states.getValue("bar").entries.size)
        assertTrue(states.getValue("bar").playing)
        assertEquals(0, states.getValue("lg").entries.size)
        assertEquals(1, bar.requests.size)
        assertEquals(0, tv.requests.size)
    }

    @Test
    fun `a target nobody found has no deck`() = runBlocking {
        assertNull(decks(speakers).deck("sofa"))
        assertNotNull(decks(speakers).deck("bar"))
    }

    @Test
    fun `a source that fails does not hide the targets of the others`() = runBlocking {
        val broken = TargetSource { error("bluetooth is off") }

        val all = decks(broken, televisions).all()

        assertEquals(listOf("lg"), all.map { it.target.id })
    }

    @Test
    fun `the sources are looked at once a minute, not on every ask`() = runBlocking {
        val decks = decks(speakers)

        decks.all()
        decks.all()
        now += 30_000
        decks.deck("bar")
        assertEquals(1, scans)

        now += 31_000
        decks.all()
        assertEquals(2, scans)
    }

    @Test
    fun `a player that says its item ended moves its deck on to the next`() = runBlocking {
        val decks = decks(speakers)
        val deck = decks.deck("bar") ?: error("no bar")
        deck.enqueue(TargetQueueRequest(listOf(podcastItem("a"), podcastItem("b")), QueueMode.REPLACE))

        bar.onEnded(false)

        repeat(100) {
            if (bar.requests.size < 2) Thread.sleep(20)
        }
        assertEquals(listOf("https://sound/a", "https://sound/b"), bar.requests.map { it.url })
    }
}
