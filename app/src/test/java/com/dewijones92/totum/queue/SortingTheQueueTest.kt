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
import org.junit.Test

class SortingTheQueueTest {

    private val peep = QueueGroup("shuffle:peep", "Peep Show")
    private val news = QueueGroup("new", "📥 New")

    private fun entry(id: String, group: QueueGroup?) = QueueEntry(
        PlayableItem(
            MediaItem(
                MediaItemId(id),
                SourceId("s"),
                id,
                publishedAt = null,
                duration = null,
                mediaUrl = HttpUrl.of("https://x.test/$id.mp3")
            ),
            PlayHandle.Podcast(),
        ),
        group,
    )

    private val byTitle: (List<QueueEntry>) -> List<QueueEntry> = { it.sortedBy { e -> e.item.item.title } }

    private fun QueueSnapshot.ids() = entries.map { it.item.item.id.value }

    private val queue = QueueSnapshot(
        entries = listOf(
            entry("p3", peep),
            entry("p9", peep),
            entry("p1", peep),
            entry("mine-b", null),
            entry("p5", peep),
            entry("n2", news),
            entry("n1", news),
            entry("mine-a", null),
        ),
        currentIndex = 1,
    )

    @Test
    fun `sorting a group reorders only its items after what is playing, in their own places`() {
        val sorted = queue.sortedAfterCurrent(byTitle) { it?.id == peep.id }

        assertEquals(listOf("p3", "p9", "p1", "mine-b", "p5", "n2", "n1", "mine-a"), sorted.ids())
        assertEquals("p9", sorted.entries[sorted.currentIndex].item.item.id.value)
    }

    @Test
    fun `sorting up next sorts within every group and leaves each group in its places`() {
        val sorted = queue.sortedAfterCurrent(byTitle)

        assertEquals(listOf("p3", "p9", "p1", "mine-a", "p5", "n1", "n2", "mine-b"), sorted.ids())
        assertEquals(1, sorted.currentIndex)
    }
}
