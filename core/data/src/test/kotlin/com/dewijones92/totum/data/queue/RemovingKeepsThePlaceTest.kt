package com.dewijones92.totum.data.queue

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemovingKeepsThePlaceTest {

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

    private fun queueOf(vararg ids: String, playing: Int = -1, anchor: String? = null) =
        QueueSnapshot(ids.map { QueueEntry(item(it)) }, playing, anchor?.let(::MediaItemId))

    private fun QueueSnapshot.ids() = entries.map { it.item.item.id.value }

    @Test
    fun `removing the anchor moves the place to the entry before it`() {
        val after = queueOf("a", "b", "c", "d", anchor = "c").removing { it.item.item.id.value == "c" }

        assertEquals(listOf("a", "b", "d"), after.ids())
        assertEquals(MediaItemId("b"), after.anchor)
        assertEquals(1, after.place)
    }

    @Test
    fun `removing the anchor with nothing before it leaves no place`() {
        val after = queueOf("a", "b", anchor = "a").removing { it.item.item.id.value == "a" }

        assertNull(after.anchor)
        assertEquals(-1, after.place)
    }

    @Test
    fun `removing entries behind the anchor leaves it alone`() {
        val after = queueOf("a", "b", "c", anchor = "b").removing { it.item.item.id.value == "c" }

        assertEquals(MediaItemId("b"), after.anchor)
        assertEquals(1, after.place)
    }

    @Test
    fun `removing the playing entry hands the place to the entry before it`() {
        val after = queueOf("a", "b", "c", playing = 1).removing { it.item.item.id.value == "b" }

        assertEquals(-1, after.currentIndex)
        assertEquals(MediaItemId("a"), after.anchor)
        assertEquals(0, after.place)
    }

    @Test
    fun `removing other entries keeps the cursor on the playing one`() {
        val after = queueOf("a", "b", "c", "d", playing = 2).removing { it.item.item.id.value == "a" }

        assertEquals(1, after.currentIndex)
        assertNull(after.anchor)
    }

    @Test
    fun `a run that includes the anchor lands after the entry before it`() {
        val after = queueOf("a", "b", "c", "d", "e", anchor = "d")
            .insertingAllAtPlace(listOf(item("x"), item("d"), item("y")), QueueGroup("album:z", "Z"))

        assertEquals(listOf("a", "b", "c", "x", "d", "y", "e"), after.ids())
    }
}
