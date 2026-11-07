package com.dewijones92.totum.playback

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.data.queue.QueueSnapshot
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.settings.RepeatMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NextLineTest {

    private val peep = QueueGroup("peep", "Peep Show")
    private val other = QueueGroup("other", "Other")

    private fun entry(id: String, group: QueueGroup? = null, kind: MediaContentKind = MediaContentKind.STANDARD) =
        QueueEntry(
            PlayableItem(
                MediaItem(
                    id = MediaItemId(id),
                    sourceId = SourceId("feed"),
                    title = "Title $id",
                    publishedAt = null,
                    duration = null,
                    mediaUrl = HttpUrl.of("https://feeds.example.com/$id.mp3"),
                    contentKind = kind,
                ),
                PlayHandle.Podcast(),
            ),
            group,
        )

    private fun inputs(
        entries: List<QueueEntry>,
        playing: String?,
        repeat: RepeatMode = RepeatMode.OFF,
        autoPlay: Boolean = true,
        explore: Boolean = false,
        sleepStopsAfter: String? = null,
    ): NextLineInputs {
        val at = entries.indexOfFirst { it.item.item.id.value == playing }
        return NextLineInputs(
            snapshot = QueueSnapshot(entries, at),
            playing = entries.getOrNull(at)?.item?.item,
            repeat = repeat,
            autoPlay = autoPlay,
            explore = explore,
            sleepStopsAfter = sleepStopsAfter?.let(::MediaItemId),
        )
    }

    private fun line(
        input: NextLineInputs,
        passOver: (PlayableItem) -> String? = { null },
    ) = nextLineOf(input, passOver)

    private val three = listOf(entry("a"), entry("b"), entry("c"))

    @Test
    fun `something queued after it is named`() {
        assertEquals(NextLine.Queued("Title b"), line(inputs(three, "a")))
        assertEquals(NextLine.Queued("Title c"), line(inputs(three, "b")))
    }

    @Test
    fun `the title shown is the one the caller's rule gives, not the raw title`() {
        val shown = nextLineOf(inputs(three, "a"), { null }) { "hidden ${it.id.value}" }

        assertEquals(NextLine.Queued("hidden b"), shown)
    }

    @Test
    fun `nothing playing, nothing after it and auto-play off each say why there is no line`() {
        assertTrue(line(inputs(three, null)) is NextLine.Unknown)
        assertEquals("nothing is queued after it", (line(inputs(three, "c")) as NextLine.Unknown).why)
        assertEquals("auto-play next is off", (line(inputs(three, "a", autoPlay = false)) as NextLine.Unknown).why)
    }

    @Test
    fun `repeat one names nothing because it replays itself`() {
        assertEquals("repeat one replays it", (line(inputs(three, "a", RepeatMode.ONE)) as NextLine.Unknown).why)
    }

    @Test
    fun `the sleep timer stopping after the item outranks everything else`() {
        val stops = inputs(three, "a", RepeatMode.QUEUE, explore = true, sleepStopsAfter = "a")

        assertEquals("the sleep timer stops after it", (line(stops) as NextLine.Unknown).why)
    }

    @Test
    fun `a sleep timer stopping after some other item does not hide the line`() {
        assertEquals(NextLine.Queued("Title b"), line(inputs(three, "a", sleepStopsAfter = "c")))
    }

    @Test
    fun `explore says explore picks and never names a queued item`() {
        assertEquals(NextLine.ExplorePicks, line(inputs(three, "a", explore = true)))
    }

    @Test
    fun `auto-play off outranks explore and repeat one outranks explore`() {
        assertTrue(line(inputs(three, "a", explore = true, autoPlay = false)) is NextLine.Unknown)
        assertTrue(line(inputs(three, "a", RepeatMode.ONE, explore = true)) is NextLine.Unknown)
    }

    @Test
    fun `repeat queue names the first item of the queue after the last one`() {
        assertEquals(NextLine.Queued("Title a"), line(inputs(three, "c", RepeatMode.QUEUE)))
    }

    @Test
    fun `repeat queue keeps to the kind that is playing`() {
        val mixed = listOf(entry("song", kind = MediaContentKind.MUSIC), entry("talkA"), entry("talkB"))

        assertEquals(NextLine.Queued("Title talkA"), line(inputs(mixed, "talkB", RepeatMode.QUEUE)))
    }

    @Test
    fun `a reshuffled queue round has no known first item`() {
        assertEquals(
            "the queue reshuffles for its next round",
            (line(inputs(three, "c", RepeatMode.QUEUE_SHUFFLED)) as NextLine.Unknown).why,
        )
    }

    @Test
    fun `repeat group names the next item of the group, then the group's first after its last`() {
        val grouped = listOf(entry("a", peep), entry("b", peep), entry("c", peep), entry("z", other))

        assertEquals(NextLine.Queued("Title b"), line(inputs(grouped, "a", RepeatMode.GROUP)))
        assertEquals(NextLine.Queued("Title a"), line(inputs(grouped, "c", RepeatMode.GROUP)))
    }

    @Test
    fun `repeat group on an item outside any group carries on with the queue`() {
        val mixed = listOf(entry("a"), entry("b"), entry("c"))

        assertEquals(NextLine.Queued("Title b"), line(inputs(mixed, "a", RepeatMode.GROUP)))
        assertEquals("nothing is queued after it", (line(inputs(mixed, "c", RepeatMode.GROUP)) as NextLine.Unknown).why)
    }

    @Test
    fun `a reshuffled group round has no known first item`() {
        val grouped = listOf(entry("a", peep), entry("b", peep))

        assertEquals(
            "the group reshuffles for its next round",
            (line(inputs(grouped, "b", RepeatMode.GROUP_SHUFFLED)) as NextLine.Unknown).why,
        )
    }

    @Test
    fun `an item the advance would pass over is not named, the next one is`() {
        val passed = { item: PlayableItem -> "finished".takeIf { item.item.id.value == "b" } }

        assertEquals(NextLine.Queued("Title c"), line(inputs(three, "a"), passed))
    }

    @Test
    fun `when everything after it is passed over there is no line`() {
        val passEverything = { _: PlayableItem -> "finished" }

        assertTrue(line(inputs(three, "a"), passEverything) is NextLine.Unknown)
    }

    @Test
    fun `the advance stays in music or talk so the other kind is not named`() {
        val mixed = listOf(entry("talk"), entry("song", kind = MediaContentKind.MUSIC), entry("talk2"))

        assertEquals(NextLine.Queued("Title talk2"), line(inputs(mixed, "talk")))
    }

    @Test
    fun `only the other kind after it means no line rather than a wrong one`() {
        val mixed = listOf(entry("talk"), entry("song", kind = MediaContentKind.MUSIC))

        assertTrue(line(inputs(mixed, "talk")) is NextLine.Unknown)
    }

    @Test
    fun `diagnostic names say which line it was`() {
        assertEquals("queued:Title b", NextLine.Queued("Title b").diagName)
        assertEquals("explore-picks", NextLine.ExplorePicks.diagName)
        assertEquals("none:why", NextLine.Unknown("why").diagName)
    }

    @Test
    fun `the context says what the notification must show and for which item`() {
        val queued = contextOf(emptyList(), NextAfter(MediaItemId("a"), NextLine.Queued("Title b")))
        assertEquals("Title b", queued.nextTitle)
        assertEquals("a", queued.nextAfter)
        assertFalse(queued.explorePicksNext)
        assertFalse(queued.repeatOne)

        val explore = contextOf(emptyList(), NextAfter(MediaItemId("a"), NextLine.ExplorePicks))
        assertNull(explore.nextTitle)
        assertTrue(explore.explorePicksNext)

        val none = contextOf(emptyList(), NextAfter(null, NextLine.Unknown("nothing is playing")))
        assertNull(none.nextTitle)
        assertNull(none.nextAfter)
        assertFalse(none.explorePicksNext)
    }

    @Test
    fun `the context carries repeat one from the active modes`() {
        val repeating = contextOf(listOf(ActiveMode.RepeatOne), NextAfter(MediaItemId("a"), NextLine.Unknown("x")))

        assertTrue(repeating.repeatOne)
    }
}
