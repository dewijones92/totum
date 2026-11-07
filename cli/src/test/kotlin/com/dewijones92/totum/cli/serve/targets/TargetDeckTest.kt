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
import com.dewijones92.totum.data.targets.TargetState
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.RepeatMode
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
        var vol: Int? = null
        var failureText: String? = null
        val volumeCommands = mutableListOf<Int>()
        override fun play(request: PlayRequest) {
            requests += request
            played += "${request.url}@${request.destination}"
            paused = false
            stopped = false
        }
        override fun pause(paused: Boolean) { this.paused = paused }
        override fun seek(positionMs: Long) = Unit
        override fun volume(percent: Int) {
            volumeCommands += percent
            vol = percent
        }
        override val volumePercent: Int? get() = vol
        override fun stop() { stopped = true }
        override var positionMs: Long? = 1_000
        override var durationMs: Long? = 60_000
        var foundLive = false
        override val live: Boolean get() = foundLive
        override var onEnded: (failed: Boolean) -> Unit = {}
        override val failure: String? get() = failureText
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
    private var now = 1_000_000L
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
        clock = { now },
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
        assertEquals(listOf(40), player.volumeCommands)
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
    fun `a play never sends a volume, and only a volume action from the phone does`() = runBlocking {
        val deck = deck()
        deck.enqueue(TargetQueueRequest(listOf(item("a"), item("b"), item("c")), QueueMode.REPLACE))
        deck.control(TargetControl(TargetAction.NEXT))
        deck.control(TargetControl(TargetAction.JUMP, index = 2))
        deck.control(TargetControl(TargetAction.PAUSE))
        deck.control(TargetControl(TargetAction.PLAY))
        deck.ended(failed = false)
        assertEquals(emptyList<Int>(), player.volumeCommands)

        deck.control(TargetControl(TargetAction.VOLUME, volume = 40))
        deck.control(TargetControl(TargetAction.PREVIOUS))
        deck.control(TargetControl(TargetAction.NEXT))
        assertEquals(listOf(40), player.volumeCommands)
    }

    @Test
    fun `the volume shown is what the device says, and unknown when it cannot say`() = runBlocking {
        val deck = deck()
        deck.enqueue(TargetQueueRequest(listOf(item("a")), QueueMode.REPLACE))
        assertEquals(null, deck.state().volume)

        player.vol = 1
        assertEquals(1, deck.state().volume)
    }

    @Test
    fun `a volume saved by an older deck is neither applied nor saved again`() = runBlocking {
        val file = dir.resolve("bar.json")
        DeckStore(file).save(
            TargetState(Target("bar", "LG soundbar", TargetKind.SPEAKER), volume = 70, sound = SoundSettings()),
            "bar",
        )
        assertTrue(file.readText().contains("\"volume\":70"))

        val deck = deck()
        deck.enqueue(TargetQueueRequest(listOf(item("a")), QueueMode.REPLACE))

        assertEquals(emptyList<Int>(), player.volumeCommands)
        assertEquals(null, deck.state().volume)
        assertEquals(null, DeckStore(file).restore("bar")?.volume)
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
    fun `an item that played to its end is reported at its length, not at a finished TV's 0`() = runBlocking {
        val deck = deck(TargetKind.TV)
        deck.enqueue(TargetQueueRequest(listOf(youtube("aaaaaaaaaaa")), QueueMode.REPLACE, from = owner))
        player.positionMs = 0

        deck.ended(failed = false)

        assertEquals(listOf("aaaaaaaaaaa@60000 finished=true"), reported)
    }

    @Test
    fun `progress of an item the owner queued is reported when it ends, one the wife queued is not`() = runBlocking {
        val deck = deck(TargetKind.TV)
        deck.enqueue(
            TargetQueueRequest(listOf(youtube("aaaaaaaaaaa")), QueueMode.REPLACE, from = owner)
        )
        deck.ended(failed = false)
        assertEquals(listOf("aaaaaaaaaaa@60000 finished=true"), reported)

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

    private fun playedFor(seconds: Long) {
        now += seconds * 1_000
    }

    private fun lastUrl() = player.played.last().substringBefore('@')

    @Test
    fun `repeat this one starts the item again each time it ends`() = runBlocking {
        val deck = deck()
        deck.enqueue(TargetQueueRequest(listOf(item("a"), item("b")), QueueMode.REPLACE, repeat = RepeatMode.ONE))

        repeat(3) {
            playedFor(60)
            deck.ended(failed = false)
        }

        assertEquals(List(4) { "https://sound/a@bar-sink" }, player.played)
        assertEquals("a", deck.state().current?.item?.itemId)
        assertTrue(deck.state().playing)
        assertEquals(RepeatMode.ONE, deck.state().repeat)
        assertEquals("the speaker is kept, not handed back and reconnected", 1, link.connects)
        assertEquals(0, link.released)
    }

    @Test
    fun `repeat the queue goes round again from the top after the last item`() = runBlocking {
        val deck = deck()
        deck.enqueue(TargetQueueRequest(listOf(item("a"), item("b")), QueueMode.REPLACE, repeat = RepeatMode.QUEUE))

        repeat(3) {
            playedFor(60)
            deck.ended(failed = false)
        }

        assertEquals(
            listOf("https://sound/a", "https://sound/b", "https://sound/a", "https://sound/b"),
            player.played.map { it.substringBefore('@') },
        )
        assertTrue(deck.state().playing)
    }

    @Test
    fun `with repeat off the end of the queue still stops and hands the speaker back`() = runBlocking {
        val deck = deck()
        deck.enqueue(TargetQueueRequest(listOf(item("a")), QueueMode.REPLACE))

        playedFor(60)
        deck.ended(failed = false)

        assertFalse(deck.state().playing)
        assertEquals(1, link.released)
        assertEquals(RepeatMode.OFF, deck.state().repeat)
    }

    @Test
    fun `an item that fails is not repeated, so a broken file cannot loop`() = runBlocking {
        val deck = deck()
        deck.enqueue(TargetQueueRequest(listOf(item("a"), item("b")), QueueMode.REPLACE, repeat = RepeatMode.ONE))

        playedFor(60)
        deck.ended(failed = true)
        assertEquals("moves on to the next", "https://sound/b", lastUrl())
        deck.ended(failed = true)

        assertEquals(listOf("https://sound/a", "https://sound/b"), player.played.map { it.substringBefore('@') })
        assertFalse(deck.state().playing)
    }

    @Test
    fun `an item that ends within two seconds is not repeated either`() = runBlocking {
        val deck = deck()
        deck.enqueue(TargetQueueRequest(listOf(item("a"), item("b")), QueueMode.REPLACE, repeat = RepeatMode.ONE))

        playedFor(1)
        deck.ended(failed = false)

        assertEquals("https://sound/b", lastUrl())
        assertEquals(2, player.played.size)
    }

    @Test
    fun `the next button still skips under repeat this one`() = runBlocking {
        val deck = deck()
        deck.enqueue(TargetQueueRequest(listOf(item("a"), item("b")), QueueMode.REPLACE, repeat = RepeatMode.ONE))

        deck.control(TargetControl(TargetAction.NEXT))

        assertEquals("b", deck.state().current?.item?.itemId)
        assertEquals(RepeatMode.ONE, deck.state().repeat)
    }

    @Test
    fun `a repeat control sets it, a request without one leaves it, and it survives a restart`() = runBlocking {
        val deck = deck()
        deck.enqueue(TargetQueueRequest(listOf(item("a")), QueueMode.REPLACE))
        assertEquals(RepeatMode.OFF, deck.state().repeat)

        val answered = deck.control(TargetControl(TargetAction.REPEAT, repeat = RepeatMode.QUEUE))
        assertEquals(RepeatMode.QUEUE, answered.repeat)
        deck.enqueue(TargetQueueRequest(listOf(item("b")), QueueMode.END))
        assertEquals(RepeatMode.QUEUE, deck.state().repeat)

        assertEquals(RepeatMode.QUEUE, deck().state().repeat)
        deck.control(TargetControl(TargetAction.REPEAT, repeat = RepeatMode.OFF))
        assertEquals(RepeatMode.OFF, deck().state().repeat)
    }

    @Test
    fun `a mode the target cannot honour yet is refused and the old one kept`() = runBlocking {
        val deck = deck()
        deck.enqueue(TargetQueueRequest(listOf(item("a")), QueueMode.REPLACE, repeat = RepeatMode.ONE))

        val answered = deck.control(TargetControl(TargetAction.REPEAT, repeat = RepeatMode.GROUP_SHUFFLED))

        assertEquals(RepeatMode.ONE, answered.repeat)
        deck.enqueue(TargetQueueRequest(listOf(item("b")), QueueMode.END, repeat = RepeatMode.QUEUE_SHUFFLED))
        assertEquals(RepeatMode.ONE, deck.state().repeat)
    }

    @Test
    fun `an item with no sound is a problem the target shows, in words, until the next item plays`() = runBlocking {
        val said = object : TargetSounds {
            override suspend fun urlFor(item: PlayableItem, picture: Boolean): String? =
                "https://sound/${item.item.id.value}".takeIf { item.item.id.value != "missing" }

            override fun whyNone(item: PlayableItem) = "that stream is not available"
        }
        val deck = TargetDeck(speaker(), player, link, said, dir.resolve("problem.json"), clock = { now })

        deck.enqueue(TargetQueueRequest(listOf(item("missing")), QueueMode.REPLACE))
        assertFalse(deck.state().playing)
        assertEquals("❌ Couldn't play Bluey missing: that stream is not available", deck.state().problem)

        deck.enqueue(TargetQueueRequest(listOf(item("b")), QueueMode.PLAY))
        assertTrue(deck.state().playing)
        assertEquals(null, deck.state().problem)
    }

    @Test
    fun `a player that fails shows what it said, and the next item that plays clears it`() = runBlocking {
        val deck = deck()
        deck.enqueue(TargetQueueRequest(listOf(item("a")), QueueMode.REPLACE))
        player.failureText = "the player exited with code 2"

        playedFor(60)
        deck.ended(failed = true)

        assertFalse(deck.state().playing)
        assertEquals("❌ Couldn't play Bluey a: the player exited with code 2", deck.state().problem)

        deck.enqueue(TargetQueueRequest(listOf(item("b")), QueueMode.PLAY))
        assertEquals(null, deck.state().problem)
    }

    @Test
    fun `when an item fails and the queue moves on, the next item plays and the problem stays in view until it ends`() =
        runBlocking {
            val deck = deck()
            deck.enqueue(TargetQueueRequest(listOf(item("a"), item("b"), item("c")), QueueMode.REPLACE))
            player.failureText = "mpv could not open the stream"

            playedFor(60)
            deck.ended(failed = true)

            assertTrue("b plays", deck.state().playing)
            assertEquals("https://sound/b@bar-sink", player.played.last())
            assertEquals(
                "❌ Couldn't play Bluey a: mpv could not open the stream",
                deck.state().problem,
            )

            playedFor(60)
            deck.ended(failed = false)

            assertTrue("c plays", deck.state().playing)
            assertEquals("the next item ending well clears it", null, deck.state().problem)
        }

    @Test
    fun `an item passed over for want of a stream stays named while the next one plays`() = runBlocking {
        val deck = deck()

        deck.enqueue(TargetQueueRequest(listOf(item("missing"), item("b")), QueueMode.REPLACE))

        assertTrue(deck.state().playing)
        assertTrue(deck.state().problem!!.contains("Couldn't play Bluey missing"))
    }

    @Test
    fun `skipping to another item from the phone clears a problem that was showing`() = runBlocking {
        val deck = deck()
        deck.enqueue(TargetQueueRequest(listOf(item("a"), item("b"), item("c")), QueueMode.REPLACE))
        deck.ended(failed = true)
        assertTrue(deck.state().problem != null)

        deck.control(TargetControl(TargetAction.NEXT))

        assertEquals(null, deck.state().problem)
    }

    @Test
    fun `a player that fails without a reason still says it failed`() = runBlocking {
        val deck = deck()
        deck.enqueue(TargetQueueRequest(listOf(item("a")), QueueMode.REPLACE))

        deck.ended(failed = true)

        assertEquals("❌ Couldn't play Bluey a: the player stopped with an error", deck.state().problem)
    }

    @Test
    fun `stopping from the phone clears a problem that was showing`() = runBlocking {
        val deck = deck()
        deck.enqueue(TargetQueueRequest(listOf(item("a")), QueueMode.REPLACE))
        deck.ended(failed = true)
        assertTrue(deck.state().problem != null)

        deck.control(TargetControl(TargetAction.STOP))

        assertEquals(null, deck.state().problem)
    }

    @Test
    fun `the TV is handed back on STOP, not between the items of a queue`() = runBlocking {
        val deck = deck(TargetKind.TV)
        deck.enqueue(TargetQueueRequest(listOf(item("a"), item("b")), QueueMode.REPLACE))

        deck.control(TargetControl(TargetAction.NEXT))
        assertEquals("moving to the next item keeps the receiver app open", 0, link.released)

        deck.control(TargetControl(TargetAction.STOP))
        assertEquals(1, link.released)
        assertFalse(deck.state().linked)
    }

    @Test
    fun `a live item reports no length whatever the player says, and says it is live`() = runBlocking {
        player.durationMs = DVR_WINDOW_MS
        val deck = deck(TargetKind.TV)

        deck.enqueue(TargetQueueRequest(listOf(item("radio", MediaContentKind.LIVE)), QueueMode.REPLACE))

        val state = deck.state()
        assertEquals(null, state.durationMs)
        assertTrue(state.live)
    }

    @Test
    fun `an item the player found live reports no length even when its kind does not say so`() = runBlocking {
        player.durationMs = DVR_WINDOW_MS
        player.foundLive = true
        val deck = deck(TargetKind.TV)

        deck.enqueue(TargetQueueRequest(listOf(item("radio")), QueueMode.REPLACE))

        assertEquals(null, deck.state().durationMs)
        assertTrue(deck.state().live)
    }

    @Test
    fun `an item that is not live keeps the length the player reports`() = runBlocking {
        val deck = deck(TargetKind.TV)

        deck.enqueue(TargetQueueRequest(listOf(item("talk")), QueueMode.REPLACE))

        assertEquals(60_000L, deck.state().durationMs)
        assertFalse(deck.state().live)
    }
}

private const val DVR_WINDOW_MS = 43_199_000L
