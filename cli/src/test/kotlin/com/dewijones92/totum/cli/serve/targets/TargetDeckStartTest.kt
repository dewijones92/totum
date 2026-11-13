package com.dewijones92.totum.cli.serve.targets

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.backup.BackupItem
import com.dewijones92.totum.data.backup.toBackup
import com.dewijones92.totum.data.targets.QueueMode
import com.dewijones92.totum.data.targets.Target
import com.dewijones92.totum.data.targets.TargetAction
import com.dewijones92.totum.data.targets.TargetControl
import com.dewijones92.totum.data.targets.TargetKind
import com.dewijones92.totum.data.targets.TargetQueueRequest
import com.dewijones92.totum.data.targets.TargetTrouble
import com.dewijones92.totum.data.targets.TroubleKind
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class TargetDeckStartTest {

    private class Player : TargetPlayer {
        val requests = mutableListOf<PlayRequest>()
        var stops = 0
        var position: Long? = 1_000
        override fun play(request: PlayRequest) {
            requests += request
        }
        override fun pause(paused: Boolean) = Unit
        override fun seek(positionMs: Long) {
            position = positionMs
        }
        override fun volume(percent: Int) = Unit
        override val volumePercent: Int? = null
        override fun stop() {
            stops++
        }
        override val positionMs: Long? get() = position
        override val durationMs: Long? = 600_000
        override var onEnded: (failed: Boolean) -> Unit = {}
    }

    private class Link(var sink: String? = "bar-sink") : TargetLink {
        var connects = 0
        var gate: CompletableDeferred<Unit>? = null
        override var unreachable: TargetTrouble? = null
        override suspend fun connect(): String? {
            connects++
            gate?.await()
            return sink
        }
        override suspend fun release() = Unit
    }

    private class Sounds : TargetSounds {
        val asked = mutableListOf<String>()
        var backOffUntil: Long? = null
        var refuse: Set<String> = emptySet()
        override suspend fun urlFor(item: PlayableItem, picture: Boolean): String? {
            asked += item.item.id.value
            return item.item.id.value.takeIf { it !in refuse && backOffUntil == null }?.let { "https://sound/$it" }
        }
        override fun whyNone(item: PlayableItem): String = "it is not on this Pi"
        override fun refusedUntil(item: PlayableItem): Long? = backOffUntil
    }

    private val player = Player()
    private val link = Link()
    private val sounds = Sounds()
    private val file = Files.createTempDirectory("deck-start").toFile().resolve("bar.json")
    private var now = 1_000_000L

    private fun deck(kind: TargetKind = TargetKind.SPEAKER) = TargetDeck(
        Target("bar", "LG soundbar", kind),
        player,
        link,
        sounds,
        file,
        clock = { now },
    )

    private fun item(id: String, kind: MediaContentKind = MediaContentKind.STANDARD): BackupItem = PlayableItem(
        MediaItem(
            MediaItemId(id),
            SourceId("feed"),
            "Bluey $id",
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://example.com/$id.mp3"),
            contentKind = kind,
        ),
        PlayHandle.Podcast(),
    ).toBackup()

    private fun send(vararg ids: String, mode: QueueMode = QueueMode.REPLACE) =
        TargetQueueRequest(ids.map(::item), mode)

    @Test
    fun `a request is accepted at once and says it is starting, then the start plays it`() = runBlocking {
        val deck = deck()

        val accepted = deck.accept(send("a", "b")) as EnqueueResult.Accepted

        assertTrue(accepted.state.starting)
        assertFalse(accepted.state.playing)
        assertEquals(listOf("a", "b"), accepted.state.entries.map { it.item.itemId })
        assertTrue("nothing was looked up or connected yet", sounds.asked.isEmpty() && link.connects == 0)

        deck.startPending()

        val after = deck.state()
        assertFalse(after.starting)
        assertTrue(after.playing)
        assertEquals(1, player.requests.size)
    }

    @Test
    fun `a slow link does not hold the answer, and the state keeps saying starting until it connects`() = runBlocking {
        link.gate = CompletableDeferred()
        val deck = deck()

        deck.accept(send("a"))
        val start = async(Dispatchers.Default) { deck.startPending() }
        Thread.sleep(200)

        assertTrue(deck.state().starting)
        assertFalse(deck.state().playing)

        link.gate?.complete(Unit)
        start.await()
        assertFalse(deck.state().starting)
        assertTrue(deck.state().playing)
    }

    @Test
    fun `a target that cannot be reached is not asked for a stream first`() = runBlocking {
        link.sink = null
        val deck = deck()

        deck.enqueue(send("a", "b"))

        assertTrue("it resolved ${sounds.asked} for a target it could not reach", sounds.asked.isEmpty())
        assertEquals(TroubleKind.NOT_CONNECTED, deck.state().trouble?.kind)
        assertEquals("📶 Couldn't reach LG soundbar: it isn't connected (off or out of range)", deck.state().problem)
        assertFalse(deck.state().playing)
        assertFalse(deck.state().starting)
        assertEquals(0, deck.state().at)
    }

    @Test
    fun `a speaker that is switched on by hand says so`() = runBlocking {
        link.sink = null
        link.unreachable = TargetTrouble(TroubleKind.SWITCH_ON)
        val deck = deck()

        deck.enqueue(send("a"))

        assertEquals("📶 Switch LG soundbar on, then try again", deck.state().problem)
        assertEquals(TroubleKind.SWITCH_ON, deck.state().trouble?.kind)
    }

    @Test
    fun `an item with no stream is a structured problem naming the item and the reason`() = runBlocking {
        sounds.refuse = setOf("a")
        val deck = deck()

        deck.enqueue(send("a", "b"))

        val state = deck.state()
        assertTrue(state.playing)
        assertEquals(TroubleKind.COULD_NOT_PLAY, state.trouble?.kind)
        assertEquals("a", state.trouble?.itemId)
        assertEquals("it is not on this Pi", state.trouble?.reason)
        assertEquals("❌ Couldn't play Bluey a: it is not on this Pi", state.problem)
    }

    @Test
    fun `live on a speaker while YouTube is refusing the Pi says when to try again`() = runBlocking {
        sounds.backOffUntil = 1_700_000_000_000
        val deck = deck()

        deck.enqueue(send("live", mode = QueueMode.REPLACE))

        val trouble = deck.state().trouble
        assertEquals(TroubleKind.YOUTUBE_BACKOFF, trouble?.kind)
        assertEquals(1_700_000_000_000, trouble?.untilMs)
        assertTrue(deck.state().problem!!.startsWith("⏳ YouTube is refusing the Pi for now; try again around "))
        assertFalse(deck.state().playing)
    }

    @Test
    fun `a failed item is a structured problem with the player's reason`() = runBlocking {
        val deck = deck()
        deck.enqueue(send("a"))
        now += 5_000

        deck.ended(failed = true)

        assertEquals(TroubleKind.COULD_NOT_PLAY, deck.state().trouble?.kind)
        assertEquals("a", deck.state().trouble?.itemId)
        assertEquals("the player stopped with an error", deck.state().trouble?.reason)
    }

    @Test
    fun `stop then play resumes the item where it was, not from the start`() = runBlocking {
        val deck = deck()
        deck.enqueue(send("a", "b"))
        player.position = 42_000

        deck.control(TargetControl(TargetAction.STOP))
        player.position = null
        assertEquals(42_000, deck.state().positionMs)
        deck.control(TargetControl(TargetAction.PLAY))

        assertEquals(2, player.requests.size)
        assertEquals(42_000, player.requests.last().startAtMs)
        assertEquals(player.requests.first().url, player.requests.last().url)
    }

    @Test
    fun `a restart keeps the place, and play carries on from it`() = runBlocking {
        val first = deck()
        first.enqueue(send("a", "b"))
        player.position = 77_000
        first.tick()
        player.position = null

        val again = deck()
        assertEquals(77_000, again.state().positionMs)
        assertFalse(again.state().playing)
        again.control(TargetControl(TargetAction.PLAY))

        assertEquals(77_000, player.requests.last().startAtMs)
    }

    @Test
    fun `a live item resumes at the edge, never at a saved second`() = runBlocking {
        val deck = deck()
        deck.enqueue(TargetQueueRequest(listOf(item("live", MediaContentKind.LIVE)), QueueMode.REPLACE))
        player.position = 90_000

        deck.control(TargetControl(TargetAction.STOP))
        deck.control(TargetControl(TargetAction.PLAY))

        assertEquals(0, player.requests.last().startAtMs)
    }

    @Test
    fun `an item that played to the end does not resume at its end`() = runBlocking {
        val deck = deck()
        deck.enqueue(send("a"))
        player.position = 599_000
        now += 10_000

        deck.ended(failed = false)
        deck.control(TargetControl(TargetAction.PLAY))

        assertEquals(0, player.requests.last().startAtMs)
    }

    @Test
    fun `skipping to another item starts it from the beginning`() = runBlocking {
        val deck = deck()
        deck.enqueue(send("a", "b"))
        player.position = 30_000

        deck.control(TargetControl(TargetAction.NEXT))

        assertEquals(0, player.requests.last().startAtMs)
    }

    @Test
    fun `a lost connection stops the deck, keeps its place and says the TV was switched off`() = runBlocking {
        val deck = deck(TargetKind.TV)
        deck.enqueue(send("a", "b"))
        player.position = 31_000
        val stopsBefore = player.stops

        deck.lost("the connection to LG TV was LOST")

        val state = deck.state()
        assertFalse(state.playing)
        assertEquals(0, state.at)
        assertEquals(31_000, state.positionMs)
        assertEquals(TroubleKind.SWITCHED_OFF, state.trouble?.kind)
        assertEquals("📺 LG soundbar was switched off", state.problem)
        assertEquals(stopsBefore + 1, player.stops)
        assertEquals("a", state.trouble?.itemId)
    }

    @Test
    fun `play after a lost connection connects again and resumes at the saved place`() = runBlocking {
        val deck = deck(TargetKind.TV)
        deck.enqueue(send("a", "b"))
        player.position = 31_000
        deck.lost("LOST")
        val connectsBefore = link.connects

        deck.control(TargetControl(TargetAction.PLAY))

        assertEquals(connectsBefore + 1, link.connects)
        assertEquals(31_000, player.requests.last().startAtMs)
        assertTrue(deck.state().playing)
        assertNull(deck.state().trouble)
    }

    @Test
    fun `a lost connection with nothing playing changes nothing`() = runBlocking {
        val deck = deck(TargetKind.TV)

        deck.lost("LOST")

        assertNull(deck.state().trouble)
    }

    @Test
    fun `no progress for twenty seconds while playing is a problem, and moving again clears it`() = runBlocking {
        val deck = deck()
        deck.enqueue(send("a"))
        deck.watch()
        now += 10_000
        deck.watch()
        assertNull("ten seconds is not a stall", deck.state().trouble)

        now += 11_000
        deck.watch()
        assertEquals(TroubleKind.STALLED, deck.state().trouble?.kind)
        assertEquals("a", deck.state().trouble?.itemId)
        assertTrue(deck.state().playing)

        player.position = 5_000
        deck.watch()
        assertNull(deck.state().trouble)
    }

    @Test
    fun `a paused deck is never stalled`() = runBlocking {
        val deck = deck()
        deck.enqueue(send("a"))
        deck.control(TargetControl(TargetAction.PAUSE))

        deck.watch()
        now += 60_000
        deck.watch()

        assertNull(deck.state().trouble)
    }

    @Test
    fun `items can be removed by their ids, even when the queue moved under the phone`() = runBlocking {
        val deck = deck()
        deck.enqueue(send("a", "b", "c", "d"))

        deck.control(TargetControl(TargetAction.REMOVE, itemIds = listOf("b", "d", "zzz")))

        assertEquals(listOf("a", "c"), deck.state().entries.map { it.item.itemId })
        assertEquals("a", deck.state().current?.item?.itemId)
    }

    @Test
    fun `removing the playing item by id moves on to the next`() = runBlocking {
        val deck = deck()
        deck.enqueue(send("a", "b", "c"))

        deck.control(TargetControl(TargetAction.REMOVE, itemIds = listOf("a", "b")))

        assertEquals(listOf("c"), deck.state().entries.map { it.item.itemId })
        assertEquals("c", deck.state().current?.item?.itemId)
        assertTrue(deck.state().playing)
    }
}
