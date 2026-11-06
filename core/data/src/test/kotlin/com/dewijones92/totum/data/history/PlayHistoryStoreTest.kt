package com.dewijones92.totum.data.history

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayHistoryStoreTest {

    private fun video(id: String) = PlayableItem(
        item = MediaItem(MediaItemId(id), SourceId("ch"), id, publishedAt = null, duration = null),
        handle = PlayHandle.Video(HttpUrl.of("https://youtube.com/watch?v=$id")),
    )

    private suspend fun ids(store: InMemoryPlayHistoryStore) =
        store.observe().first().map { it.item.id.value }

    @Test
    fun `most-recently-played comes first`() = runTest {
        val store = InMemoryPlayHistoryStore()
        store.record(video("a"))
        store.record(video("b"))
        assertEquals(listOf("b", "a"), ids(store))
    }

    @Test
    fun `replaying an item moves it to the front without duplicating`() = runTest {
        val store = InMemoryPlayHistoryStore()
        store.record(video("a"))
        store.record(video("b"))
        store.record(video("a"))
        assertEquals(listOf("a", "b"), ids(store))
    }

    @Test
    fun `history is capped at the limit, dropping the oldest`() = runTest {
        val store = InMemoryPlayHistoryStore(limit = 2)
        store.record(video("a"))
        store.record(video("b"))
        store.record(video("c"))
        assertEquals(listOf("c", "b"), ids(store))
    }

    @Test
    fun `clear empties the history`() = runTest {
        val store = InMemoryPlayHistoryStore()
        store.record(video("a"))
        store.clear()
        assertTrue(ids(store).isEmpty())
    }

    @Test
    fun `clearing hands back what was there, most recent first`() = runTest {
        val store = InMemoryPlayHistoryStore()
        store.record(video("a"))
        store.record(video("b"))

        val cleared = store.clear()

        assertEquals(listOf("b", "a"), cleared.map { it.item.id.value })
        assertTrue(ids(store).isEmpty())
    }

    @Test
    fun `restoring puts the cleared history back in its old order`() = runTest {
        val store = InMemoryPlayHistoryStore()
        listOf("a", "b", "c").forEach { store.record(video(it)) }

        store.restore(store.clear())

        assertEquals(listOf("c", "b", "a"), ids(store))
    }

    @Test
    fun `what was played during the undo window stays in front and is not duplicated`() = runTest {
        val store = InMemoryPlayHistoryStore()
        listOf("a", "b", "c").forEach { store.record(video(it)) }
        val cleared = store.clear()
        store.record(video("x"))
        store.record(video("b"))

        store.restore(cleared)

        assertEquals(listOf("b", "x", "c", "a"), ids(store))
    }

    @Test
    fun `restoring never takes the history past its cap`() = runTest {
        val store = InMemoryPlayHistoryStore(limit = 3)
        listOf("a", "b", "c").forEach { store.record(video(it)) }
        val cleared = store.clear()
        store.record(video("x"))
        store.record(video("y"))

        store.restore(cleared)

        assertEquals(listOf("y", "x", "c"), ids(store))
    }
}
