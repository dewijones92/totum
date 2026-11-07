package com.dewijones92.totum.queue

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.data.targets.toTargetEntry
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TidyUndoStoreTest {

    @get:Rule val folder = TemporaryFolder()

    private var now = 10_000_000L

    private fun held(id: String, index: Int, group: QueueGroup? = null) = TidyUndoStore.Held(
        index,
        QueueEntry(
            PlayableItem(
                MediaItem(
                    MediaItemId(id),
                    SourceId("feed"),
                    "Episode $id",
                    publishedAt = null,
                    duration = null,
                    mediaUrl = HttpUrl.of("https://feeds.example.com/$id.mp3"),
                ),
                PlayHandle.Podcast(),
            ),
            group,
        ).toTargetEntry(),
    )

    @Test
    fun `a held batch is taken once, inside its window, and then it is gone`() {
        val store = TidyUndoStore(null, { now }, windowMs = 1_000)
        store.hold(listOf(held("a", 3), held("b", 7)))

        now += 999
        val taken = store.take("test")

        assertEquals(listOf(3, 7), taken?.held?.map { it.index })
        assertNull(store.take("again"))
    }

    @Test
    fun `a batch exactly as old as the window can still be taken and one a moment older cannot`() {
        val onTheLine = TidyUndoStore(null, { now }, windowMs = 1_000)
        onTheLine.hold(listOf(held("a", 0)))
        now += 1_000
        assertNotNull(onTheLine.take("on the line"))

        val past = TidyUndoStore(null, { now }, windowMs = 1_000)
        past.hold(listOf(held("a", 0)))
        now += 1_001
        assertNull(past.take("past"))
    }

    @Test
    fun `a batch taken past its window is also forgotten, not kept for another try`() {
        val store = TidyUndoStore(null, { now }, windowMs = 1_000)
        store.hold(listOf(held("a", 0)))
        now += 5_000
        assertNull(store.take("late"))

        now -= 5_000
        assertNull(store.take("early again"))
    }

    @Test
    fun `a newer batch replaces an older one`() {
        val store = TidyUndoStore(null, { now })
        store.hold(listOf(held("old", 0)))
        store.hold(listOf(held("new", 1)))

        assertEquals(listOf("new"), store.take("test")?.held?.map { it.entry.item.itemId })
    }

    @Test
    fun `a batch survives the process because it is written to its file`() {
        val file = folder.newFile("queue-tidy-undo.json")
        TidyUndoStore(file, { now }).hold(listOf(held("a", 4, QueueGroup("g", "Peep Show"))))

        val reopened = TidyUndoStore(file, { now + 60_000 }).take("after a restart")

        assertEquals(listOf(4), reopened?.held?.map { it.index })
        assertEquals("g", reopened?.held?.single()?.entry?.groupId)
        assertFalse(file.exists())
    }

    @Test
    fun `a dismissed batch is forgotten, in memory and on disk`() {
        val file = folder.newFile("queue-tidy-undo.json")
        val store = TidyUndoStore(file, { now })
        store.hold(listOf(held("a", 0)))
        assertTrue(file.exists())

        store.clear("swiped away")

        assertFalse(file.exists())
        assertNull(TidyUndoStore(file, { now }).take("after"))
        assertNull(store.take("after"))
    }

    @Test
    fun `an unreadable file holds nothing instead of failing`() {
        val file = folder.newFile("queue-tidy-undo.json")
        file.writeText("{ not json")

        assertNull(TidyUndoStore(file, { now }).take("test"))
    }

    @Test
    fun `nothing held means nothing to take`() {
        assertNull(TidyUndoStore(null, { now }).take("test"))
    }
}
