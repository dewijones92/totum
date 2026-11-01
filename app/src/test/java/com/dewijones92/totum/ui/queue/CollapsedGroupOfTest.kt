package com.dewijones92.totum.ui.queue

import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.asPlayable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CollapsedGroupOfTest {

    private val group = QueueGroup("artist-all:x", "Craig Trompeter · All songs")
    private val entry = QueueEntry(
        MediaItem(MediaItemId("a"), SourceId("s"), "Rondeau", publishedAt = null, duration = null).asPlayable(),
        group,
    )

    @Test
    fun `the playing item names its group when the group is collapsed`() {
        assertEquals(group, collapsedGroupOf(entry) { it == group.id })
    }

    @Test
    fun `an open group or no group needs no line`() {
        assertNull(collapsedGroupOf(entry) { false })
        assertNull(collapsedGroupOf(entry.copy(group = null)) { true })
    }
}
