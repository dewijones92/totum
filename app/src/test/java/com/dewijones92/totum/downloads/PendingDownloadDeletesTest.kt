package com.dewijones92.totum.downloads

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.download.fake.FakeDownloadManager
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.DownloadedMedia
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PendingDownloadDeletesTest {

    private class MemoryStore(var ids: Set<MediaItemId> = emptySet()) : PendingDeleteStore {
        val saves = mutableListOf<Set<MediaItemId>>()
        override fun load() = ids
        override fun save(ids: Set<MediaItemId>) {
            this.ids = ids
            saves += ids
        }
    }

    private fun playable(id: String, title: String = id) = PlayableItem(
        item = MediaItem(MediaItemId(id), SourceId("ch"), title, publishedAt = null, duration = null),
        handle = PlayHandle.Video(HttpUrl.of("https://youtube.com/watch?v=$id")),
    )

    private fun id(value: String) = MediaItemId(value)

    private class Rig(
        val inner: FakeDownloadManager,
        val store: MemoryStore,
        val deletes: PendingDownloadDeletes,
    )

    private fun TestScope.rig(
        vararg held: String,
        stored: Set<MediaItemId> = emptySet(),
        locked: Set<MediaItemId> = emptySet(),
    ): Rig {
        val inner = FakeDownloadManager()
        held.forEach { inner.place(DownloadedMedia(playable(it, "Title $it"), "/fake/$it.mp4", false)) }
        val store = MemoryStore(stored)
        val deletes = PendingDownloadDeletes(
            inner = inner,
            scope = backgroundScope,
            store = store,
            isLocked = { it in locked },
            windowMs = WINDOW,
            now = { currentTime },
        )
        return Rig(inner, store, deletes)
    }

    private suspend fun Rig.visible() = deletes.observeDownloaded().first().map { it.item.id.value }

    private suspend fun Rig.onDisk() = inner.observeDownloaded().first().map { it.item.id.value }

    @Test
    fun `a deleted download is hidden everywhere at once but its file stays until the window ends`() = runTest {
        val rig = rig("a", "b")

        rig.deletes.deleteSoon(id("a"))

        assertEquals(listOf("b"), rig.visible())
        assertEquals(listOf("a", "b"), rig.onDisk())
        assertFalse(id("a") in rig.deletes.observeDownloads().first())
        assertEquals(DownloadState.NotDownloaded, rig.deletes.observe(id("a")).first())
        assertTrue(rig.deletes.observeRecords().first().none { it.item.item.id == id("a") })
        assertEquals(setOf(id("a")), rig.store.ids)
    }

    @Test
    fun `undo inside the window brings it back and the file is never deleted`() = runTest {
        val rig = rig("a")
        rig.deletes.deleteSoon(id("a"))

        advanceTimeBy(400)
        rig.deletes.undo(listOf(id("a")), "test")
        advanceTimeBy(WINDOW * 3)
        runCurrent()

        assertEquals(listOf("a"), rig.visible())
        assertEquals(listOf("a"), rig.onDisk())
        assertTrue(rig.deletes.pending.value.isEmpty())
        assertTrue(rig.store.ids.isEmpty())
    }

    @Test
    fun `without an undo the file goes when the window ends and not a moment before`() = runTest {
        val rig = rig("a")
        rig.deletes.deleteSoon(id("a"))

        advanceTimeBy(ANNOUNCE_MS + WINDOW - 50)
        runCurrent()
        assertEquals(listOf("a"), rig.onDisk())

        advanceTimeBy(100)
        runCurrent()

        assertEquals(emptyList<String>(), rig.onDisk())
        assertTrue(rig.deletes.pending.value.isEmpty())
        assertTrue(rig.store.ids.isEmpty())
    }

    @Test
    fun `deletes made close together are one offer with every id and no single title`() = runTest {
        val rig = rig("a", "b", "c")
        val offers = mutableListOf<DeleteOffer>()
        backgroundScope.launch { rig.deletes.offers.collect { offers += it } }
        runCurrent()

        rig.deletes.deleteSoon(id("a"))
        advanceTimeBy(100)
        rig.deletes.deleteSoon(id("b"))
        advanceTimeBy(ANNOUNCE_MS + 10)
        runCurrent()

        assertEquals(1, offers.size)
        assertEquals(listOf(id("a"), id("b")), offers.single().ids)
        assertNull(offers.single().title)
    }

    @Test
    fun `a single delete is offered with its title`() = runTest {
        val rig = rig("a")
        val offers = mutableListOf<DeleteOffer>()
        backgroundScope.launch { rig.deletes.offers.collect { offers += it } }
        runCurrent()

        rig.deletes.deleteSoon(id("a"))
        advanceTimeBy(ANNOUNCE_MS + 10)
        runCurrent()

        assertEquals("Title a", offers.single().title)
    }

    @Test
    fun `a later batch waits for the earlier window so two offers never overlap`() = runTest {
        val rig = rig("a", "b")
        rig.deletes.deleteSoon(id("a"))
        advanceTimeBy(ANNOUNCE_MS + 10)
        runCurrent()
        rig.deletes.deleteSoon(id("b"))
        advanceTimeBy(ANNOUNCE_MS + 10)
        runCurrent()

        advanceTimeBy(WINDOW - ANNOUNCE_MS - 100)
        runCurrent()
        assertEquals("the first window is still open", listOf("a", "b"), rig.onDisk())

        advanceTimeBy(200)
        runCurrent()
        assertEquals("the first window has ended, the second has not begun", listOf("b"), rig.onDisk())

        advanceTimeBy(WINDOW)
        runCurrent()
        assertEquals(emptyList<String>(), rig.onDisk())
    }

    @Test
    fun `undo after one of the offered ids already went only takes back what is still held`() = runTest {
        val rig = rig("a", "b")
        rig.deletes.deleteSoon(id("a"))
        advanceTimeBy(ANNOUNCE_MS + WINDOW + 50)
        runCurrent()
        rig.deletes.deleteSoon(id("b"))

        rig.deletes.undo(listOf(id("a"), id("b")), "test")

        assertEquals(listOf("b"), rig.visible())
        assertEquals(listOf("b"), rig.onDisk())
    }

    @Test
    fun `a locked download is handed to the inner manager and never hidden or offered`() = runTest {
        val rig = rig("a", locked = setOf(id("a")))
        val offers = mutableListOf<DeleteOffer>()
        backgroundScope.launch { rig.deletes.offers.collect { offers += it } }
        runCurrent()

        rig.deletes.deleteSoon(id("a"))
        advanceTimeBy(ANNOUNCE_MS + 10)
        runCurrent()

        assertTrue(rig.deletes.pending.value.isEmpty())
        assertTrue(offers.isEmpty())
        assertTrue(rig.store.saves.isEmpty())
    }

    @Test
    fun `deletes still pending when the app stopped are finished at the next start`() = runTest {
        val inner = FakeDownloadManager()
        inner.place(DownloadedMedia(playable("a"), "/fake/a.mp4", false))
        inner.place(DownloadedMedia(playable("b"), "/fake/b.mp4", false))
        val store = MemoryStore(setOf(id("a")))

        val deletes = PendingDownloadDeletes(inner, backgroundScope, store, { false }, WINDOW) { currentTime }

        assertEquals("hidden from the first frame", setOf(id("a")), deletes.pending.value)
        assertEquals(listOf("b"), deletes.observeDownloaded().first().map { it.item.id.value })
        runCurrent()
        assertEquals(listOf("b"), inner.observeDownloaded().first().map { it.item.id.value })
        assertTrue(deletes.pending.value.isEmpty())
        assertTrue(store.ids.isEmpty())
    }

    @Test
    fun `asking for the same item again takes the pending delete back first`() = runTest {
        val rig = rig("a")
        rig.deletes.deleteSoon(id("a"))

        rig.deletes.download(playable("a"), audioOnly = false, maxHeight = null)
        advanceTimeBy(ANNOUNCE_MS + WINDOW * 2)
        runCurrent()

        assertTrue(rig.deletes.pending.value.isEmpty())
        assertEquals(listOf("a"), rig.onDisk())
    }

    @Test
    fun `an immediate delete of a pending item removes it from the hidden set too`() = runTest {
        val rig = rig("a")
        rig.deletes.deleteSoon(id("a"))

        rig.deletes.delete(id("a"))

        assertEquals(emptyList<String>(), rig.onDisk())
        assertTrue(rig.deletes.pending.value.isEmpty())
        assertTrue(rig.store.ids.isEmpty())
    }

    private companion object {
        const val WINDOW = 12_000L
        const val ANNOUNCE_MS = 300L
    }
}
