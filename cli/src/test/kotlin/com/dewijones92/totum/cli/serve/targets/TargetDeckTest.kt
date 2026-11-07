package com.dewijones92.totum.cli.serve.targets

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.backup.BackupItem
import com.dewijones92.totum.data.backup.toBackup
import com.dewijones92.totum.data.targets.QueueMode
import com.dewijones92.totum.data.targets.Requester
import com.dewijones92.totum.data.targets.SoundSettings
import com.dewijones92.totum.data.targets.Target
import com.dewijones92.totum.data.targets.TargetAction
import com.dewijones92.totum.data.targets.TargetControl
import com.dewijones92.totum.data.targets.TargetKind
import com.dewijones92.totum.data.targets.TargetQueueRequest
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.playback.VolumeBoost
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class TargetDeckTest {

    private class FakePlayer : TargetPlayer {
        val played = mutableListOf<String>()
        val requests = mutableListOf<PlayRequest>()
        var paused = false
        var stopped = false
        var vol = -1
        override fun play(request: PlayRequest) {
            requests += request
            played += "${request.url}@${request.destination}"
            paused = false
            stopped = false
            vol = request.volume
        }
        override fun pause(paused: Boolean) { this.paused = paused }
        override fun seek(positionMs: Long) = Unit
        override fun volume(percent: Int) { vol = percent }
        override fun stop() { stopped = true }
        override val positionMs: Long? = 1_000
        override val durationMs: Long? = 60_000
        override var onEnded: (failed: Boolean) -> Unit = {}
    }

    private class FakeLink(var sink: String? = "bar-sink") : TargetLink {
        var connects = 0
        var released = 0
        override suspend fun connect(): String? = sink.also { connects++ }
        override suspend fun release() { released++ }
    }

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
    private val player = FakePlayer()
    private val link = FakeLink()
    private val dir = Files.createTempDirectory("speakers").toFile()
    private val sounds = TargetSounds { item, _ ->
        item.item.id.value.takeIf { it != "missing" }?.let { "https://sound/$it" }
    }
    private val reported = mutableListOf<String>()
    private val reporter = ProgressReporter { entry, position, _, finished ->
        reported += "${entry.item.itemId}@$position finished=$finished"
    }
    private val lookedAhead = mutableListOf<String>()
    private fun deck(kind: TargetKind = TargetKind.SPEAKER, ownerId: String? = "dewi") = TargetDeck(
        Target("bar", "LG soundbar", kind),
        player,
        link,
        sounds,
        dir.resolve("bar.json"),
        lookAhead = {
            lookedAhead += it.item.id.value
        },
        reporter = reporter,
        ownerId = { ownerId },
    )

    @Test
    fun `a queue sent to the speaker plays its first item on the speaker's sink, then each next one`() = runBlocking {
        val deck = deck()
        deck.enqueue(TargetQueueRequest(listOf(item("a"), item("b")), QueueMode.REPLACE))
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
        deck().enqueue(TargetQueueRequest(listOf(item("missing"), item("b")), QueueMode.REPLACE))
        assertEquals(listOf("https://sound/b@bar-sink"), player.played)
        link.sink = null
        val deck = deck()
        deck.enqueue(TargetQueueRequest(listOf(item("c")), QueueMode.REPLACE))
        assertFalse(deck.state().playing)
        assertTrue(deck.state().problem!!.isNotBlank())
    }

    @Test
    fun `controls pause, skip, jump, set the volume, remove and stop`() = runBlocking {
        val deck = deck()
        deck.enqueue(TargetQueueRequest(listOf(item("a"), item("b"), item("c")), QueueMode.REPLACE))
        deck.control(TargetControl(TargetAction.PAUSE))
        assertTrue(player.paused)
        deck.control(TargetControl(TargetAction.PLAY))
        assertFalse(player.paused)
        deck.control(TargetControl(TargetAction.NEXT))
        assertEquals("b", deck.state().current?.item?.itemId)
        deck.control(TargetControl(TargetAction.JUMP, index = 2))
        assertEquals("https://sound/c@bar-sink", player.played.last())
        deck.control(TargetControl(TargetAction.VOLUME, volume = 40))
        assertEquals(40, player.vol)
        deck.control(TargetControl(TargetAction.REMOVE, index = 0))
        assertEquals(listOf("b", "c"), deck.state().entries.map { it.item.itemId })
        deck.control(TargetControl(TargetAction.STOP))
        assertTrue(player.stopped)
        assertFalse(deck.state().playing)
        assertEquals(1, link.released)
    }

    @Test
    fun `adding to an idle speaker starts it, and a group comes with its items`() = runBlocking {
        val deck = deck()
        deck.enqueue(
            TargetQueueRequest(listOf(item("a"), item("b")), QueueMode.END, groupId = "bluey", groupTitle = "Bluey")
        )
        assertEquals(listOf("https://sound/a@bar-sink"), player.played)
        assertEquals("Bluey", deck.state().entries.first().groupTitle)
    }

    @Test
    fun `when an item starts the next one is looked up ahead, so the hand-over does not wait for it`() = runBlocking {
        val deck = deck()
        deck.enqueue(TargetQueueRequest(listOf(item("a"), item("b"), item("c")), QueueMode.REPLACE))
        assertEquals(listOf("b"), lookedAhead)
        deck.ended(failed = false)
        assertEquals(listOf("b", "c"), lookedAhead)
    }

    @Test
    fun `the queue survives a restart, paused rather than playing by itself`() = runBlocking {
        deck().enqueue(TargetQueueRequest(listOf(item("a"), item("b")), QueueMode.REPLACE))
        val again = deck()
        assertEquals(listOf("a", "b"), again.state().entries.map { it.item.itemId })
        assertFalse(again.state().playing)
    }

    private fun youtube(id: String): BackupItem = PlayableItem(
        MediaItem(MediaItemId(id), SourceId("chan"), "Peppa $id", publishedAt = null, duration = null),
        PlayHandle.Video(HttpUrl.of("https://www.youtube.com/watch?v=$id")),
    ).toBackup()

    private val owner = Requester("dewi", "Dewi's phone")
    private val wife = Requester("wife", "Wife's phone")

    @Test
    fun `an item shows who added it, and the deck remembers it across a restart`() = runBlocking {
        val deck = deck()
        deck.enqueue(TargetQueueRequest(listOf(item("a")), QueueMode.REPLACE, from = wife))
        deck.enqueue(TargetQueueRequest(listOf(item("b")), QueueMode.END, from = owner))

        assertEquals(listOf("Wife's phone", "Dewi's phone"), deck.state().entries.map { it.addedBy })
        assertEquals(listOf(false, true), deck.state().entries.map { it.reportsToYouTube })
        val again = deck().state()
        assertEquals(listOf("Wife's phone", "Dewi's phone"), again.entries.map { it.addedBy })
    }

    @Test
    fun `progress of an item the owner queued is reported when it ends, one the wife queued is not`() = runBlocking {
        val deck = deck(TargetKind.TV)
        deck.enqueue(
            TargetQueueRequest(listOf(youtube("aaaaaaaaaaa")), QueueMode.REPLACE, from = owner)
        )
        deck.ended(failed = false)
        assertEquals(listOf("aaaaaaaaaaa@1000 finished=true"), reported)

        reported.clear()
        deck.enqueue(TargetQueueRequest(listOf(youtube("bbbbbbbbbbb")), QueueMode.REPLACE, from = wife))
        deck.ended(failed = false)
        assertTrue(reported.isEmpty())
    }

    @Test
    fun `pausing and a tick report where it had got to, a failure does not report a finish`() = runBlocking {
        val deck = deck(TargetKind.TV)
        deck.enqueue(
            TargetQueueRequest(listOf(youtube("aaaaaaaaaaa"), youtube("bbbbbbbbbbb")), QueueMode.REPLACE, from = owner)
        )
        deck.tick()
        deck.control(TargetControl(TargetAction.PAUSE))
        assertEquals(listOf("aaaaaaaaaaa@1000 finished=false", "aaaaaaaaaaa@1000 finished=false"), reported)

        reported.clear()
        deck.control(TargetControl(TargetAction.PLAY))
        deck.ended(failed = true)
        assertTrue(reported.isEmpty())
    }

    @Test
    fun `a send starts at the item and second it was at, with a picture only where the target has one`() = runBlocking {
        val tv = deck(TargetKind.TV)
        tv.enqueue(
            TargetQueueRequest(
                listOf(youtube("aaaaaaaaaaa"), youtube("bbbbbbbbbbb")),
                QueueMode.REPLACE,
                startItemId = "bbbbbbbbbbb",
                startAtMs = 42_000,
            )
        )
        assertEquals("bbbbbbbbbbb", tv.state().current?.item?.itemId)
        assertEquals(42_000L, player.requests.last().startAtMs)
        assertTrue(player.requests.last().picture)

        deck(TargetKind.SPEAKER).enqueue(TargetQueueRequest(listOf(youtube("ccccccccccc")), QueueMode.REPLACE))
        assertFalse(player.requests.last().picture)
    }

    @Test
    fun `changing the sound restarts the playing item where it was, and is remembered`() = runBlocking {
        val deck = deck()
        deck.enqueue(TargetQueueRequest(listOf(item("a")), QueueMode.REPLACE))
        assertFalse(player.requests.last().sound.changesSound)

        deck.control(TargetControl(TargetAction.SOUND, sound = SoundSettings(skipSilence = true)))

        assertEquals(2, player.requests.size)
        assertTrue(player.requests.last().sound.skipSilence)
        assertEquals(1_000L, player.requests.last().startAtMs)
        assertTrue(deck().state().sound.skipSilence)
    }

    @Test
    fun `play now inserts after what is playing and starts the new item, keeping the rest`() = runBlocking {
        val deck = deck()
        deck.enqueue(TargetQueueRequest(listOf(item("a"), item("b")), QueueMode.REPLACE))

        deck.enqueue(TargetQueueRequest(listOf(item("x"), item("y")), QueueMode.PLAY))

        assertEquals(listOf("a", "x", "y", "b"), deck.state().entries.map { it.item.itemId })
        assertEquals("x", deck.state().current?.item?.itemId)
        assertEquals("https://sound/x@bar-sink", player.played.last())
    }

    @Test
    fun `skip silence is applied to talk and never to music or a book, by the same rule as the phone`() = runBlocking {
        val deck = deck()
        deck.control(
            TargetControl(TargetAction.SOUND, sound = SoundSettings(skipSilence = true, boost = VolumeBoost.AUTO))
        )
        deck.enqueue(
            TargetQueueRequest(
                listOf(item("talk"), item("song", MediaContentKind.MUSIC), item("book", MediaContentKind.AUDIOBOOK)),
                QueueMode.REPLACE,
            )
        )

        assertTrue("talk is cut", player.requests.last().sound.skipSilence)
        deck.ended(failed = false)
        assertFalse("a song is not cut", player.requests.last().sound.skipSilence)
        assertEquals("but the boost still applies", VolumeBoost.AUTO, player.requests.last().sound.boost)
        deck.ended(failed = false)
        assertFalse("a book is not cut", player.requests.last().sound.skipSilence)
        assertTrue("and the deck's own choice is untouched", deck.state().sound.skipSilence)
    }

    @Test
    fun `only the phone the Pi recorded as having handed the sign-in over is the owner`() = runBlocking {
        val impostor = Requester("wife", "Wife's phone")
        val deck = deck(TargetKind.TV, ownerId = "dewi")

        deck.enqueue(TargetQueueRequest(listOf(item("a")), QueueMode.REPLACE, from = impostor))
        deck.enqueue(TargetQueueRequest(listOf(item("b")), QueueMode.END, from = Requester("dewi", "Dewi's Pixel 7")))

        assertEquals(listOf(false, true), deck.state().entries.map { it.reportsToYouTube })
    }

    @Test
    fun `with nobody recorded as the owner nothing reports`() = runBlocking {
        val deck = deck(TargetKind.TV, ownerId = null)

        deck.enqueue(TargetQueueRequest(listOf(item("a")), QueueMode.REPLACE, from = owner))

        assertEquals(listOf(false), deck.state().entries.map { it.reportsToYouTube })
    }

    @Test
    fun `play next of an item already queued moves it to just after what plays, and nothing restarts`() = runBlocking {
        val deck = deck()
        deck.enqueue(TargetQueueRequest(listOf(item("a"), item("b"), item("c")), QueueMode.REPLACE))
        val starts = player.played.size

        deck.enqueue(TargetQueueRequest(listOf(item("c")), QueueMode.NEXT))

        assertEquals(listOf("a", "c", "b"), deck.state().entries.map { it.item.itemId })
        assertEquals("a", deck.state().current?.item?.itemId)
        assertEquals(starts, player.played.size)
    }

    @Test
    fun `a request with nothing playable is refused, not answered as if it worked`() = runBlocking {
        val deck = deck()

        val result = deck.enqueue(
            TargetQueueRequest(listOf(item("x").copy(playbackType = "VIDEO", handle = null)), QueueMode.PLAY)
        )

        assertTrue(result is EnqueueResult.Refused)
        assertTrue((result as EnqueueResult.Refused).why.contains("LG soundbar"))
    }

    @Test
    fun `an unreachable target is connected to before the deck is locked, once per request`() = runBlocking {
        link.sink = null
        val deck = deck()

        deck.enqueue(TargetQueueRequest(listOf(item("a")), QueueMode.REPLACE))

        assertEquals(1, link.connects)
        assertTrue(deck.state().problem!!.isNotBlank())
    }
}
