package com.dewijones92.totum.ui

import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import org.junit.Assert.assertEquals
import org.junit.Test

class UpNextAfterTest {

    private val entries = listOf("a", "b", "c", "d").map { id ->
        QueueEntry(
            PlayableItem(
                MediaItem(MediaItemId(id), SourceId("s"), title = id, publishedAt = null, duration = null),
                PlayHandle.Podcast(),
            ),
        )
    }

    private fun ids(list: List<QueueEntry>) = list.map { it.item.item.id.value }

    @Test
    fun `with the cursor on the queue, up next is what follows it`() {
        assertEquals(listOf("c", "d"), ids(upNextAfter(entries, currentIndex = 1, resumeAfter = null)))
    }

    @Test
    fun `while exploring, up next is what follows the queue's place, not the whole queue`() {
        assertEquals(listOf("c", "d"), ids(upNextAfter(entries, currentIndex = -1, resumeAfter = MediaItemId("b"))))
    }

    @Test
    fun `with no cursor and no place, the whole queue is up next`() {
        assertEquals(listOf("a", "b", "c", "d"), ids(upNextAfter(entries, currentIndex = -1, resumeAfter = null)))
    }
}
