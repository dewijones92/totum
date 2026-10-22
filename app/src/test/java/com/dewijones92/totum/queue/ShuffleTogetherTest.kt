package com.dewijones92.totum.queue

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.data.queue.QueueSnapshot
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.random.Random

class ShuffleTogetherTest {

    private val artistA = QueueGroup("artist:a", "Artist A")
    private val artistB = QueueGroup("artist:b", "Artist B")
    private val together = QueueGroup("together:1", "🔀 Artist A + Artist B")

    private fun entry(id: String, group: QueueGroup? = null) = QueueEntry(
        PlayableItem(
            MediaItem(
                id = MediaItemId(id),
                sourceId = SourceId("feed"),
                title = id,
                publishedAt = null,
                duration = null,
                mediaUrl = HttpUrl.of("https://feed.test/$id.mp3"),
            ),
            PlayHandle.Podcast(),
        ),
        group,
    )

    private val queue = QueueSnapshot(
        entries = listOf(
            entry("now"),
            entry("x"),
            entry("a1", artistA),
            entry("a2", artistA),
            entry("a3", artistA),
            entry("y"),
            entry("b1", artistB),
            entry("b2", artistB),
            entry("b3", artistB),
        ),
        currentIndex = 0,
    )

    private val both = listOf("a1", "a2", "a3", "b1", "b2", "b3").map(::MediaItemId).toSet()

    private fun QueueSnapshot.ids() = entries.map { it.item.item.id.value }

    @Test
    fun `two groups become one shuffled group where the first of them was`() {
        val after = queue.shufflingTogether(both, together, Random(7))

        assertEquals(listOf("now", "x"), after.ids().take(2))
        assertEquals(both.map { it.value }.toSet(), after.ids().subList(2, 8).toSet())
        assertEquals("y", after.ids().last())
        assertEquals(List(6) { together }, after.entries.subList(2, 8).map { it.group })
        assertNotEquals(
            "the two artists are mixed, not end to end",
            listOf("a1", "a2", "a3"),
            after.ids().subList(2, 5)
        )
        assertEquals(0, after.currentIndex)
    }

    @Test
    fun `the playing item and what has played stay where they are`() {
        val playingA2 = queue.copy(currentIndex = 3)

        val after = playingA2.shufflingTogether(both, together, Random(3))

        assertEquals("a2", after.current?.item?.item?.id?.value)
        assertEquals(artistA, after.current?.group)
        assertEquals(listOf("now", "x", "a1", "a2"), after.ids().take(4))
        assertEquals("only what is still to play is mixed", 4, after.entries.count { it.group == together })
    }

    @Test
    fun `fewer than two items to mix leaves the queue alone`() {
        assertSame(queue, queue.shufflingTogether(setOf(MediaItemId("a1")), together, Random(1)))
    }

    @Test
    fun `the title names the groups it came from`() {
        assertEquals(
            "🔀 Artist A + Artist B",
            queue.entries.filter { it.item.item.id in both }.togetherTitle("🔀 Shuffled together")
        )
        assertEquals(
            "🔀 Shuffled together",
            listOf(entry("x"), entry("y")).togetherTitle("🔀 Shuffled together"),
        )
    }
}
