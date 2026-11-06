package com.dewijones92.totum.data.playlist

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.playlist.fake.InMemoryLocalPlaylistStore
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.PlaylistId
import com.dewijones92.totum.domain.SourceId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPlaylistStoreTest {

    private val store = InMemoryLocalPlaylistStore()

    private fun video(id: String) = PlayableItem(
        item = MediaItem(MediaItemId(id), SourceId("ch"), id, publishedAt = null, duration = null),
        handle = PlayHandle.Video(HttpUrl.of("https://youtube.com/watch?v=$id")),
    )

    private fun podcast(id: String) = PlayableItem(
        item = MediaItem(
            MediaItemId(id),
            SourceId("feed"),
            id,
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://feeds.example.com/$id.mp3"),
        ),
        handle = PlayHandle.Podcast(),
    )

    private suspend fun items(id: PlaylistId) = store.observeItems(id).first()

    @Test
    fun `create then add mixes both pillars in order`() = runTest {
        val id = store.create("Mix")
        store.addItem(id, video("v1"))
        store.addItem(id, podcast("p1"))
        store.addItem(id, video("v2"))

        assertEquals(listOf("v1", "p1", "v2"), items(id).map { it.item.id.value })
        val playlist = store.observePlaylists().first().single()
        assertEquals("Mix", playlist.name)
        assertEquals(3, playlist.itemCount)
    }

    @Test
    fun `inserting at an index puts the item back in its old place and clamps past the end`() = runTest {
        val id = store.create("P")
        store.addItem(id, video("v1"))
        store.addItem(id, video("v3"))

        store.insertItem(id, video("v2"), 1)
        store.insertItem(id, video("v4"), 99)
        store.insertItem(id, video("v2"), 0)

        assertEquals(listOf("v1", "v2", "v3", "v4"), items(id).map { it.item.id.value })
    }

    @Test
    fun `adding the same item id twice is a no-op`() = runTest {
        val id = store.create("P")
        store.addItem(id, video("v1"))
        store.addItem(id, video("v1"))
        assertEquals(1, items(id).size)
    }

    @Test
    fun `rename, remove item and delete`() = runTest {
        val id = store.create("Old")
        store.addItem(id, video("v1"))
        store.addItem(id, podcast("p1"))

        store.rename(id, "New")
        assertEquals("New", store.observePlaylists().first().single().name)

        store.removeItem(id, MediaItemId("v1"))
        assertEquals(listOf("p1"), items(id).map { it.item.id.value })

        store.delete(id)
        assertTrue(store.observePlaylists().first().isEmpty())
    }

    @Test
    fun `podcast item keeps its enclosure, video keeps its watch handle`() = runTest {
        val id = store.create("P")
        store.addItem(id, video("v1"))
        store.addItem(id, podcast("p1"))
        val byId = items(id).associateBy { it.item.id.value }

        assertTrue(byId.getValue("v1").handle is PlayHandle.Video)
        assertTrue(byId.getValue("p1").handle is PlayHandle.Podcast)
        assertEquals("https://feeds.example.com/p1.mp3", byId.getValue("p1").item.mediaUrl?.value)
    }

    @Test
    fun `a deleted playlist comes back with its id, name, items and place in the list`() = runTest {
        val first = store.create("First")
        val middle = store.create("Middle")
        val last = store.create("Last")
        store.addItem(middle, video("v1"))
        store.addItem(middle, podcast("p1"))
        store.addItem(middle, video("v2"))

        val removed = store.delete(middle)!!
        assertEquals(listOf("First", "Last"), store.observePlaylists().first().map { it.name })

        store.restore(removed)

        assertEquals(listOf("First", "Middle", "Last"), store.observePlaylists().first().map { it.name })
        assertEquals(listOf(first, middle, last), store.observePlaylists().first().map { it.id })
        assertEquals(listOf("v1", "p1", "v2"), items(middle).map { it.item.id.value })
        assertEquals(3, store.observePlaylists().first()[1].itemCount)
    }

    @Test
    fun `deleting a playlist that is not there returns nothing`() = runTest {
        assertEquals(null, store.delete(PlaylistId("nope")))
    }

    @Test
    fun `restoring a playlist that exists again changes nothing`() = runTest {
        val id = store.create("Mine")
        store.addItem(id, video("v1"))
        val removed = store.delete(id)!!
        store.restore(removed)
        store.addItem(id, video("v2"))

        store.restore(removed)

        assertEquals(listOf("v1", "v2"), items(id).map { it.item.id.value })
        assertEquals(1, store.observePlaylists().first().size)
    }

    @Test
    fun `several deleted playlists restore in any order and keep their places`() = runTest {
        val ids = listOf("A", "B", "C", "D").map { store.create(it) }
        val removedB = store.delete(ids[1])!!
        val removedC = store.delete(ids[2])!!

        store.restore(removedC)
        store.restore(removedB)

        assertEquals(listOf("A", "B", "C", "D"), store.observePlaylists().first().map { it.name })
    }
}
