package com.dewijones92.totum.ui.history

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HistoryClearingTest {

    private fun video(id: String) = PlayableItem(
        item = MediaItem(MediaItemId(id), SourceId("ch"), id, publishedAt = null, duration = null),
        handle = PlayHandle.Video(HttpUrl.of("https://youtube.com/watch?v=$id")),
    )

    private suspend fun InMemoryPlayHistoryStore.ids() = observe().first().map { it.item.id.value }

    @Test
    fun `clearing empties the history and undo brings back the whole of it in order`() = runTest {
        val store = InMemoryPlayHistoryStore()
        listOf("a", "b", "c").forEach { store.record(video(it)) }
        val clearing = HistoryClearing(store, this)

        val held = clearing.clear(3)!!
        advanceUntilIdle()
        assertEquals(emptyList<String>(), store.ids())
        assertEquals(3, held.summary)

        clearing.restore(held)
        advanceUntilIdle()
        assertEquals(listOf("c", "b", "a"), store.ids())
    }

    @Test
    fun `what was played during the undo window stays on top of the restored history`() = runTest {
        val store = InMemoryPlayHistoryStore()
        listOf("a", "b").forEach { store.record(video(it)) }
        val clearing = HistoryClearing(store, this)

        val held = clearing.clear(2)!!
        advanceUntilIdle()
        store.record(video("new"))
        clearing.restore(held)
        advanceUntilIdle()

        assertEquals(listOf("new", "b", "a"), store.ids())
    }

    @Test
    fun `an empty history offers no undo`() = runTest {
        val store = InMemoryPlayHistoryStore()

        assertNull(HistoryClearing(store, this).clear(0))
    }
}
