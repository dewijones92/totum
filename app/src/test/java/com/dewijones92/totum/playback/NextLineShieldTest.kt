package com.dewijones92.totum.playback

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.data.queue.QueueSnapshot
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.RepeatMode
import com.dewijones92.totum.domain.SourceId
import org.junit.Assert.assertEquals
import org.junit.Test

class NextLineShieldTest {

    private fun media(id: String, title: String) = MediaItem(
        MediaItemId(id),
        SourceId("feed"),
        title,
        publishedAt = null,
        duration = null,
        mediaUrl = HttpUrl.of("https://example.com/$id.mp3"),
    )

    private fun entry(id: String, title: String, group: QueueGroup? = null) =
        QueueEntry(PlayableItem(media(id, title), PlayHandle.Podcast()), group)

    private val hidden: (MediaItem) -> String = { "🙈 Title hidden" }
    private val tennis = QueueGroup("t", "Tennis")

    private fun inputs(
        entries: List<QueueEntry>,
        at: Int = 0,
        repeat: RepeatMode = RepeatMode.OFF,
        autoPlay: Boolean = true,
        explore: Boolean = false,
        sleepStopsAfter: MediaItemId? = null,
        playing: MediaItem? = entries.getOrNull(at)?.item?.item,
    ) = NextLineInputs(QueueSnapshot(entries, at), playing, repeat, autoPlay, explore, sleepStopsAfter)

    private fun line(inputs: NextLineInputs, titleOf: (MediaItem) -> String = MediaItem::title) =
        nextLineOf(inputs, passOver = { null }, titleOf = titleOf)

    @Test
    fun `the next line names the next entry by its title`() {
        val next = line(inputs(listOf(entry("a", "First"), entry("b", "Second"))))

        assertEquals(NextLine.Queued("Second"), next)
    }

    @Test
    fun `a shielded next entry is named by what the shield shows, never its own title`() {
        val queue = listOf(entry("a", "First"), entry("b", "Alcaraz beats Sinner"))

        assertEquals(NextLine.Queued("🙈 Title hidden"), line(inputs(queue), hidden))
    }

    @Test
    fun `repeating a group names its first entry through the shield too`() {
        val queue = listOf(entry("a", "Alcaraz wins", tennis), entry("b", "Sinner wins", tennis))

        val next = line(inputs(queue, at = 1, repeat = RepeatMode.GROUP), hidden)

        assertEquals(NextLine.Queued("🙈 Title hidden"), next)
    }

    @Test
    fun `repeating the queue names its first entry through the shield too`() {
        val queue = listOf(entry("a", "Alcaraz wins"), entry("b", "Sinner wins"))

        val next = line(inputs(queue, at = 1, repeat = RepeatMode.QUEUE), hidden)

        assertEquals(NextLine.Queued("🙈 Title hidden"), next)
    }

    @Test
    fun `a reshuffled round has no known next entry, so there is nothing to hide or show`() {
        val queue = listOf(entry("a", "A"), entry("b", "B"))

        assertEquals(
            NextLine.Unknown("the queue reshuffles for its next round"),
            line(inputs(queue, at = 1, repeat = RepeatMode.QUEUE_SHUFFLED)),
        )
    }

    @Test
    fun `each reason the next is not known is said in words`() {
        val queue = listOf(entry("a", "A"), entry("b", "B"))

        assertEquals(NextLine.Unknown("nothing is playing"), line(inputs(queue, playing = null)))
        assertEquals(
            NextLine.Unknown("the sleep timer stops after it"),
            line(inputs(queue, sleepStopsAfter = MediaItemId("a"))),
        )
        assertEquals(NextLine.Unknown("repeat one replays it"), line(inputs(queue, repeat = RepeatMode.ONE)))
        assertEquals(NextLine.Unknown("auto-play next is off"), line(inputs(queue, autoPlay = false)))
        assertEquals(NextLine.Unknown("nothing is queued after it"), line(inputs(queue, at = 1)))
    }

    @Test
    fun `explore mode says the next is a pick, not a queued title`() {
        val queue = listOf(entry("a", "A"), entry("b", "B"))

        assertEquals(NextLine.ExplorePicks, line(inputs(queue, explore = true)))
    }
}
