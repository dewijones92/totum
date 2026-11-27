package com.dewijones92.totum.data.queue

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayNextStacksInOrderTest {

    private fun item(id: String) = PlayableItem(
        MediaItem(
            MediaItemId(id),
            SourceId("s"),
            id,
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://x.test/$id.mp3"),
        ),
        PlayHandle.Podcast(),
    )

    private fun queueOf(vararg ids: String, playing: Int = 0) =
        QueueSnapshot(ids.map { QueueEntry(item(it)) }, playing)

    private fun QueueSnapshot.ids() = entries.map { it.item.item.id.value }

    private fun QueueSnapshot.playNext(vararg ids: String, group: QueueGroup? = null) =
        insertingAllNext(ids.map(::item), group)

    private fun QueueSnapshot.playingMovesTo(index: Int) = copy(currentIndex = index)

    @Test
    fun `play next on A then B then C plays them in that order`() {
        val queue = queueOf("playing", "x", "y")
            .playNext("a")
            .playNext("b")
            .playNext("c")

        assertEquals(listOf("playing", "a", "b", "c", "x", "y"), queue.ids())
        assertEquals(0, queue.currentIndex)
    }

    @Test
    fun `with nothing playing the stack still builds from the head in order`() {
        val queue = queueOf("x", "y", playing = -1)
            .playNext("a")
            .playNext("b")
            .playNext("c")

        assertEquals(listOf("a", "b", "c", "x", "y"), queue.ids())
    }

    @Test
    fun `the chain starts again when the playing item changes`() {
        val stacked = queueOf("playing", "x").playNext("a").playNext("b")
        val advanced = stacked.playingMovesTo(1)

        val queue = advanced.playNext("d")

        assertEquals(listOf("playing", "a", "d", "b", "x"), queue.ids())
    }

    @Test
    fun `the chain starts again when the last play next has been removed`() {
        val stacked = queueOf("playing", "x").playNext("a").playNext("b")
        val withoutTail = stacked.removing { it.item.item.id.value == "b" }

        val queue = withoutTail.playNext("d")

        assertEquals(listOf("playing", "d", "a", "x"), queue.ids())
    }

    @Test
    fun `the chain starts again when the last play next is no longer ahead of the playing item`() {
        val stacked = queueOf("playing", "x").playNext("a").playNext("b")
        val tailBehind = stacked.copy(
            entries = listOf("b", "playing", "a", "x").map { id ->
                stacked.entries.first { it.item.item.id.value == id }
            },
            currentIndex = 1,
        )

        val queue = tailBehind.playNext("d")

        assertEquals(listOf("b", "playing", "d", "a", "x"), queue.ids())
    }

    @Test
    fun `a whole group stacks after the last play next and the next play next goes after the group`() {
        val album = QueueGroup("album", "Album")
        val queue = queueOf("playing", "x")
            .playNext("a")
            .playNext("t1", "t2", "t3", group = album)
            .playNext("c")

        assertEquals(listOf("playing", "a", "t1", "t2", "t3", "c", "x"), queue.ids())
        assertEquals(listOf(null, null, album, album, album, null, null), queue.entries.map { it.group })
    }

    @Test
    fun `play next again on the item that is already last in the chain leaves the order alone`() {
        val stacked = queueOf("playing", "x").playNext("a").playNext("b")

        val queue = stacked.playNext("b")

        assertEquals(listOf("playing", "a", "b", "x"), queue.ids())
    }

    @Test
    fun `play next on an earlier item of the chain moves it to the end of the chain`() {
        val stacked = queueOf("playing", "x").playNext("a").playNext("b").playNext("c")

        val queue = stacked.playNext("a")

        assertEquals(listOf("playing", "b", "c", "a", "x"), queue.ids())
    }

    @Test
    fun `an item already queued further down joins the chain in its place`() {
        val queue = queueOf("playing", "x", "y", "z")
            .playNext("a")
            .playNext("z")
            .playNext("b")

        assertEquals(listOf("playing", "a", "z", "b", "x", "y"), queue.ids())
    }
}
