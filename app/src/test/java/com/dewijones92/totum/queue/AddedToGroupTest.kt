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

class AddedToGroupTest {

    private val peep = QueueGroup("shuffle:peep", "Peep Show")

    private fun item(id: String) = PlayableItem(
        MediaItem(
            MediaItemId(id),
            SourceId("s"),
            id,
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://www.youtube.com/watch?v=$id"),
        ),
        PlayHandle.Video(HttpUrl.of("https://www.youtube.com/watch?v=$id")),
    )

    private fun snapshot(current: Int, vararg entries: Pair<String, QueueGroup?>) =
        QueueSnapshot(entries.map { (id, g) -> QueueEntry(item(id), g) }, currentIndex = current)

    private fun QueueSnapshot.ids() = entries.map { it.item.item.id.value }

    @Test
    fun `while the group is playing, new items go straight after what is playing`() {
        val before = snapshot(2, "a" to null, "p1" to peep, "p2" to peep, "p3" to peep, "b" to null)

        val after = before.addedTo(peep, listOf(item("new")), GroupSpot.NEXT)

        assertEquals(listOf("a", "p1", "p2", "new", "p3", "b"), after.ids())
        assertEquals(2, after.currentIndex)
    }

    @Test
    fun `while something else plays, new items go to the head of the group`() {
        val before = snapshot(0, "a" to null, "p1" to peep, "p2" to peep, "b" to null)

        val after = before.addedTo(peep, listOf(item("new")), GroupSpot.NEXT)

        assertEquals(listOf("a", "new", "p1", "p2", "b"), after.ids())
        assertEquals(0, after.currentIndex)
    }

    @Test
    fun `at the end of the group, the cursor behind it moves along`() {
        val before = snapshot(3, "p1" to peep, "p2" to peep, "a" to null, "b" to null)

        val after = before.addedTo(peep, listOf(item("new")), GroupSpot.END)

        assertEquals(listOf("p1", "p2", "new", "a", "b"), after.ids())
        assertEquals(4, after.currentIndex)
    }

    @Test
    fun `a group that is not in the queue starts at the end`() {
        val inbox = QueueGroup("inbox", "📥 New")
        val after = snapshot(0, "a" to null).addedTo(inbox, listOf(item("n1"), item("n2")), GroupSpot.END)

        assertEquals(listOf("a", "n1", "n2"), after.ids())
        assertEquals(inbox, after.entries.last().group)
    }
}
