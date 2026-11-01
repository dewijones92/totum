package com.dewijones92.totum.downloads.home

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.data.queue.QueueSnapshot
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeAheadTest {

    private val show = QueueGroup("shuffle:show", "A show")
    private val other = QueueGroup("album:x", "An album")

    private fun entry(id: String, group: QueueGroup? = show) = QueueEntry(
        PlayableItem(
            MediaItem(
                MediaItemId(id),
                SourceId("feed"),
                id,
                publishedAt = null,
                duration = null,
                mediaUrl = HttpUrl.of("https://example.com/$id.mp3"),
            ),
            PlayHandle.Podcast(),
        ),
        group,
    )

    private val queue = listOf(entry("e1"), entry("e2"), entry("s1", other), entry("e3"), entry("e4"), entry("e5"))

    private fun ids(items: List<PlayableItem>) = items.map { it.item.id.value }

    @Test
    fun `the next N after what is playing in the group are wanted`() {
        val snapshot = QueueSnapshot(queue, currentIndex = 1)
        assertEquals(listOf("e3", "e4"), ids(aheadFor(snapshot, mapOf(show.id to 2), { false }, { false })))
    }

    @Test
    fun `a group not playing starts from its first member`() {
        val snapshot = QueueSnapshot(queue, currentIndex = 2)
        assertEquals(listOf("e1", "e2"), ids(aheadFor(snapshot, mapOf(show.id to 2), { false }, { false })))
    }

    @Test
    fun `finished episodes are passed over and ones already there count towards N`() {
        val snapshot = QueueSnapshot(queue, currentIndex = 0)
        val wanted = aheadFor(
            snapshot,
            mapOf(show.id to 2),
            present = { it.value == "e3" },
            finished = { it.item.id.value == "e2" },
        )
        assertEquals(listOf("e4"), ids(wanted))
    }

    @Test
    fun `with nothing playing it counts from the queue's place, not the group's top`() {
        val snapshot = QueueSnapshot(queue, currentIndex = -1, anchor = MediaItemId("e2"))
        assertEquals(listOf("e3", "e4"), ids(aheadFor(snapshot, mapOf(show.id to 2), { false }, { false })))
    }

    @Test
    fun `nothing is sent before home's list has been read, and nothing already there is sent then`() = runTest {
        val sent = mutableListOf<String>()
        val snapshot = MutableStateFlow(QueueSnapshot(queue, currentIndex = 0))
        val there = MutableStateFlow<Set<MediaItemId>?>(null)
        val ahead = HomeAhead(null, backgroundScope)
        ahead.choose(show.id, 2, "test")
        ahead.start(snapshot, there, { false }) { sent += it.item.id.value }
        runCurrent()
        assertTrue("sent before home was read: $sent", sent.isEmpty())
        there.value = setOf(MediaItemId("e2"), MediaItemId("e3"))
        runCurrent()
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `an item cancelled at home is not sent again, and a refused send is tried again later`() = runTest {
        val sent = mutableListOf<String>()
        var clock = 0L
        val snapshot = MutableStateFlow(QueueSnapshot(queue, currentIndex = 0))
        val there = MutableStateFlow<Set<MediaItemId>?>(setOf(MediaItemId("e2")))
        val ahead = HomeAhead(null, backgroundScope, now = { clock })
        ahead.choose(show.id, 2, "test")
        ahead.start(snapshot, there, { false }) { sent += it.item.id.value }
        runCurrent()
        assertEquals(listOf("e3"), sent)
        there.value = emptySet()
        runCurrent()
        assertEquals("e2 was at home and went: cancelled, not sent again", listOf("e3"), sent)
        clock += 31 * 60_000L
        snapshot.value = QueueSnapshot(queue, currentIndex = 0, anchor = null).copy()
        there.value = setOf()
        snapshot.value = QueueSnapshot(queue + entry("e9", null), currentIndex = 0)
        runCurrent()
        assertEquals("e3 never arrived: asked again after a while", listOf("e3", "e3"), sent)
    }

    @Test
    fun `nothing is sent twice while the home job is still on its way`() = runTest {
        val sent = mutableListOf<String>()
        val snapshot = MutableStateFlow(QueueSnapshot(queue, currentIndex = 0))
        val there = MutableStateFlow<Set<MediaItemId>?>(emptySet())
        val ahead = HomeAhead(null, backgroundScope)
        ahead.start(snapshot, there, { false }) { sent += it.item.id.value }
        ahead.choose(show.id, 2, "test")
        runCurrent()
        snapshot.value = snapshot.value.copy()
        snapshot.value = QueueSnapshot(queue + entry("e6"), currentIndex = 0)
        runCurrent()
        assertEquals(listOf("e2", "e3"), sent)
        there.value = setOf(MediaItemId("e2"))
        snapshot.value = QueueSnapshot(queue, currentIndex = 1)
        runCurrent()
        assertEquals(listOf("e2", "e3", "e4"), sent)
    }
}
