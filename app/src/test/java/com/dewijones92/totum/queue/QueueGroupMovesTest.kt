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
import org.junit.Test
import kotlin.random.Random

class QueueGroupMovesTest {

    private val album = QueueGroup("album:a", "Album")
    private val season = QueueGroup("show:s", "Season")

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

    private fun snapshot(current: Int = -1) = QueueSnapshot(
        entries = listOf(
            entry("x"),
            entry("a1", album),
            entry("a2", album),
            entry("y"),
            entry("s1", season),
            entry("s2", season),
            entry("s3", season),
        ),
        currentIndex = current,
    )

    private fun QueueSnapshot.ids() = entries.map { it.item.item.id.value }

    private fun QueueSnapshot.playing() = current?.item?.item?.id?.value

    @Test
    fun `a group is one block, and a split group is gathered into its first block`() {
        val split = QueueSnapshot(listOf(entry("a1", album), entry("x"), entry("a2", album)))

        val blocks = split.entries.blocks()

        assertEquals(listOf("album:a", null), blocks.map { it.groupId })
        assertEquals(listOf("a1", "a2"), blocks[0].entries.map { it.item.item.id.value })
    }

    @Test
    fun `moving a group to the top takes all its items, in order, and the cursor follows the playing item`() {
        val moved = snapshot(current = 3).movingGroup(season.id, GroupDestination.TOP)

        assertEquals(listOf("s1", "s2", "s3", "x", "a1", "a2", "y"), moved.ids())
        assertEquals("y", moved.playing())
    }

    @Test
    fun `moving a group to the bottom`() {
        val moved = snapshot(current = 0).movingGroup(album.id, GroupDestination.BOTTOM)

        assertEquals(listOf("x", "y", "s1", "s2", "s3", "a1", "a2"), moved.ids())
        assertEquals("x", moved.playing())
    }

    @Test
    fun `play next puts the whole group straight after what is playing`() {
        val moved = snapshot(current = 1).movingGroup(season.id, GroupDestination.NEXT)

        assertEquals(listOf("x", "a1", "s1", "s2", "s3", "a2", "y"), moved.ids())
        assertEquals("a1", moved.playing())
    }

    @Test
    fun `play next on the playing group gathers only what is still to come`() {
        val split = QueueSnapshot(
            listOf(entry("a1", album), entry("a2", album), entry("x"), entry("a3", album)),
            currentIndex = 1,
        )

        val moved = split.movingGroup(album.id, GroupDestination.NEXT)

        assertEquals(listOf("a1", "a2", "a3", "x"), moved.ids())
        assertEquals("a2", moved.playing())
    }

    @Test
    fun `play next with nothing playing puts it first`() {
        assertEquals("s1", snapshot().movingGroup(season.id, GroupDestination.NEXT).ids().first())
    }

    @Test
    fun `dragging a block one place swaps it with the whole block beside it`() {
        val down = snapshot(current = 0).movingBlock(1, 2)
        assertEquals(listOf("x", "y", "a1", "a2", "s1", "s2", "s3"), down.ids())

        val up = snapshot(current = 0).movingBlock(3, 2)
        assertEquals(listOf("x", "a1", "a2", "s1", "s2", "s3", "y"), up.ids())
        assertEquals("x", up.playing())
    }

    @Test
    fun `a block index out of range changes nothing`() {
        assertEquals(snapshot().ids(), snapshot().movingBlock(0, 9).ids())
    }

    @Test
    fun `shuffling inside a group keeps every other item where it was`() {
        val shuffled = snapshot(current = 0).shufflingWithin(season.id, Random(SEED))

        assertEquals(listOf("x", "a1", "a2", "y"), shuffled.ids().take(4))
        assertEquals(setOf("s1", "s2", "s3"), shuffled.ids().drop(4).toSet())
        assertNotEquals(listOf("s1", "s2", "s3"), shuffled.ids().drop(4))
    }

    @Test
    fun `shuffling inside the playing group leaves what has played and what is playing`() {
        val shuffled = snapshot(current = 4).shufflingWithin(season.id, Random(SEED))

        assertEquals(listOf("x", "a1", "a2", "y", "s1"), shuffled.ids().take(5))
        assertEquals("s1", shuffled.playing())
    }

    @Test
    fun `shuffling the order of groups keeps each group together and in order`() {
        val many = QueueSnapshot(
            listOf(entry("now")) + (1..6).flatMap { g ->
                val group = QueueGroup("g$g", "G$g")
                (1..3).map { entry("g$g-$it", group) }
            },
            currentIndex = 0,
        )

        val shuffled = many.shufflingGroupOrder(Random(SEED))

        assertEquals("now", shuffled.ids().first())
        val order = shuffled.entries.drop(1).blocks()
        assertEquals(6, order.size)
        order.forEach { block ->
            assertEquals((1..3).map { "${block.groupId}-$it" }, block.entries.map { it.item.item.id.value })
        }
        assertNotEquals((1..6).map { "g$it" }, order.map { it.groupId })
    }

    @Test
    fun `one block after what is playing has nothing to shuffle`() {
        val one = QueueSnapshot(listOf(entry("now"), entry("a1", album), entry("a2", album)), currentIndex = 0)

        assertEquals(one, one.shufflingGroupOrder(Random(SEED)))
    }

    @Test
    fun `an item dragged over another item moves by their places in the queue`() {
        assertEquals(QueueDrag.Item(0, 4), snapshot().dragMove("x", "s1"))
    }

    @Test
    fun `an item is never dropped on a group header`() {
        assertEquals(true, snapshot().dragMove("x", queueGroupHeaderKey(season.id)) is QueueDrag.Ignored)
    }

    @Test
    fun `a group header dragged over a row moves its block to that row's block`() {
        assertEquals(QueueDrag.Block(1, 3), snapshot().dragMove(queueGroupHeaderKey(album.id), "s2"))
        assertEquals(QueueDrag.Block(3, 2), snapshot().dragMove(queueGroupHeaderKey(season.id), "y"))
    }

    @Test
    fun `a group header dragged over another header swaps the two groups`() {
        assertEquals(
            QueueDrag.Block(1, 3),
            snapshot().dragMove(queueGroupHeaderKey(album.id), queueGroupHeaderKey(season.id)),
        )
    }

    @Test
    fun `a key that has left the queue is ignored`() {
        assertEquals(true, snapshot().dragMove("gone", "x") is QueueDrag.Ignored)
        assertEquals(true, snapshot().dragMove(queueGroupHeaderKey("gone"), "x") is QueueDrag.Ignored)
    }

    private companion object {
        const val SEED = 7
    }
}
