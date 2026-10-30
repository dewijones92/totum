package com.dewijones92.totum.ui.queue

import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayingRowIndexTest {

    private fun entry(id: String, group: String? = null) = QueueEntry(
        PlayableItem(
            MediaItem(MediaItemId(id), SourceId("s"), id, publishedAt = null, duration = null),
            PlayHandle.Podcast()
        ),
        group?.let { QueueGroup(it, it) },
    )

    private val queue = listOf(
        entry("a"),
        entry("b1", "B"),
        entry("b2", "B"),
        entry("b3", "B"),
        entry("c"),
        entry("b4", "B"),
        entry("d1", "D"),
        entry("d2", "D"),
    )

    @Test
    fun `the jump lands on the row the queue draws, its Added by you headings and top spacer counted`() {
        assertEquals(2, playingRowIndex(queue, 0, emptySet()))
        assertEquals(4, playingRowIndex(queue, 1, emptySet()))
        assertEquals(8, playingRowIndex(queue, 4, emptySet()))
        assertEquals(10, playingRowIndex(queue, 5, emptySet()))
        assertEquals(13, playingRowIndex(queue, 7, emptySet()))
    }

    @Test
    fun `a collapsed group above is one header, and its second run shows nothing`() {
        assertEquals(5, playingRowIndex(queue, 4, setOf("B")))
        assertEquals(8, playingRowIndex(queue, 7, setOf("B")))
    }

    @Test
    fun `collapse all collapses every group, and expands them when all are collapsed`() {
        assertEquals(setOf("B", "D"), toggledAll(setOf("B", "D"), setOf("B")))
        assertEquals(emptySet<String>(), toggledAll(setOf("B", "D"), setOf("B", "D")))
    }
}
