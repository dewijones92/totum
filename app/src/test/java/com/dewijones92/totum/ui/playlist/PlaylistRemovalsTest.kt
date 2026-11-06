package com.dewijones92.totum.ui.playlist

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.playlist.fake.InMemoryLocalPlaylistStore
import com.dewijones92.totum.domain.LocalPlaylist
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.PlaylistId
import com.dewijones92.totum.domain.SourceId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistRemovalsTest {

    private fun video(id: String) = PlayableItem(
        item = MediaItem(MediaItemId(id), SourceId("ch"), id, publishedAt = null, duration = null),
        handle = PlayHandle.Video(HttpUrl.of("https://youtube.com/watch?v=$id")),
    )

    private suspend fun InMemoryLocalPlaylistStore.names() = observePlaylists().first().map { it.name }

    private suspend fun InMemoryLocalPlaylistStore.playlist(name: String) =
        observePlaylists().first().first { it.name == name }

    @Test
    fun `deleting playlists removes them and undo puts every one back with its items in its place`() = runTest {
        val store = InMemoryLocalPlaylistStore()
        val road = store.create("Road trip")
        val gym = store.create("Gym")
        val sleep = store.create("Sleep")
        store.addItem(road, video("r1"))
        store.addItem(road, video("r2"))
        store.addItem(sleep, video("s1"))
        val removals = PlaylistRemovals(store, this)

        val held = removals.delete(store.observePlaylists().first().filter { it.id != gym })!!
        advanceUntilIdle()

        assertEquals(listOf("Gym"), store.names())
        assertEquals(listOf("Road trip", "Sleep"), held.summary)

        removals.restore(held)
        advanceUntilIdle()

        assertEquals(listOf("Road trip", "Gym", "Sleep"), store.names())
        assertEquals(listOf("r1", "r2"), store.observeItems(road).first().map { it.item.id.value })
        assertEquals(listOf("s1"), store.observeItems(sleep).first().map { it.item.id.value })
        assertEquals(2, store.playlist("Road trip").itemCount)
    }

    @Test
    fun `undo asked before the delete has finished still waits for it and then restores`() = runTest {
        val store = InMemoryLocalPlaylistStore()
        val id = store.create("Quick")
        store.addItem(id, video("q1"))
        val removals = PlaylistRemovals(store, this)

        val held = removals.delete(listOf(LocalPlaylist(id, "Quick", 1)))!!
        removals.restore(held)
        advanceUntilIdle()

        assertEquals(listOf("Quick"), store.names())
        assertEquals(listOf("q1"), store.observeItems(id).first().map { it.item.id.value })
    }

    @Test
    fun `nothing chosen offers no undo and touches nothing`() = runTest {
        val store = InMemoryLocalPlaylistStore()
        store.create("Keep")

        assertNull(PlaylistRemovals(store, this).delete(emptyList()))
        advanceUntilIdle()

        assertEquals(listOf("Keep"), store.names())
    }

    @Test
    fun `a playlist that is already gone is skipped and the rest are still held`() = runTest {
        val store = InMemoryLocalPlaylistStore()
        val real = store.create("Real")
        store.addItem(real, video("x"))
        val removals = PlaylistRemovals(store, this)

        val held = removals.delete(
            listOf(LocalPlaylist(PlaylistId("ghost"), "Ghost", 0), LocalPlaylist(real, "Real", 1))
        )!!
        removals.restore(held)
        advanceUntilIdle()

        assertEquals(listOf("Real"), store.names())
        assertEquals(listOf("x"), store.observeItems(real).first().map { it.item.id.value })
    }
}
