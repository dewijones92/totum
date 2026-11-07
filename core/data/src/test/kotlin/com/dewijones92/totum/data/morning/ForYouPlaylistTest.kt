package com.dewijones92.totum.data.morning

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.playlist.LocalPlaylistStore
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

class ForYouPlaylistTest {
    private val id = PlaylistId("for-you")

    private fun playable(name: String) = PlayableItem(
        MediaItem(MediaItemId(name), SourceId("for-you"), name, null, duration = null),
        PlayHandle.Video(HttpUrl.parse("https://www.youtube.com/watch?v=$name")!!),
    )

    private class Watched(private val inner: InMemoryLocalPlaylistStore) : LocalPlaylistStore by inner {
        val sizes = mutableListOf<Int>()

        override suspend fun addItem(id: PlaylistId, item: PlayableItem) {
            inner.addItem(id, item)
            sizes += inner.observeItems(id).first().size
        }

        override suspend fun removeItem(id: PlaylistId, itemId: MediaItemId) {
            inner.removeItem(id, itemId)
            sizes += inner.observeItems(id).first().size
        }
    }

    @Test
    fun `the new list is filled before the old one is cleared, so a kill never leaves it empty`() = runTest {
        val inner = InMemoryLocalPlaylistStore()
        inner.ensure(id, "🤖 For you")
        listOf("old1", "old2", "kept").forEach { inner.addItem(id, playable(it)) }
        val store = Watched(inner)

        store.replaceContents(id, "🤖 For you", listOf(playable("new1"), playable("kept"), playable("new2")))

        assertTrue("sizes while swapping: ${store.sizes}", store.sizes.all { it >= 3 })
        assertEquals(
            setOf("kept", "new1", "new2"),
            inner.observeItems(id).first().map { it.item.id.value }.toSet(),
        )
    }
}
