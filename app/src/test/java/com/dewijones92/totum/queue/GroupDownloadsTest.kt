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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class GroupDownloadsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val album = QueueGroup("album:a", "An album")
    private val shuffle = QueueGroup("shuffle:channel:x", "A channel")

    private fun entry(id: String, group: QueueGroup? = null) = QueueEntry(
        PlayableItem(
            MediaItem(
                id = MediaItemId(id),
                sourceId = SourceId("s"),
                title = id,
                publishedAt = null,
                duration = null,
                mediaUrl = HttpUrl.of("https://example.com/$id.mp3")
            ),
            PlayHandle.Podcast(),
        ),
        group,
    )

    @Test
    fun `a group joining the queue is asked about, and one already there on the first run is not`() {
        val firstRun = emptyMap<String, GroupDownload>().trackedFor(setOf("album:a"), firstRun = true)
        assertEquals(mapOf("album:a" to GroupDownload.YES), firstRun)

        val later = firstRun.trackedFor(setOf("album:a", "shuffle:channel:x"), firstRun = false)
        assertEquals(mapOf("album:a" to GroupDownload.YES, "shuffle:channel:x" to GroupDownload.ASK), later)
    }

    @Test
    fun `a group that leaves the queue is forgotten, so adding it again asks again`() {
        val gone = mapOf("album:a" to GroupDownload.NO).trackedFor(emptySet(), firstRun = false)
        assertEquals(emptyMap<String, GroupDownload>(), gone)
        assertEquals(mapOf("album:a" to GroupDownload.ASK), gone.trackedFor(setOf("album:a"), firstRun = false))
    }

    @Test
    fun `only a yes lets a group's items download, and items outside a group always may`() {
        val choices = mapOf("album:a" to GroupDownload.YES, "shuffle:channel:x" to GroupDownload.ASK)
        assertTrue(GroupDownloads.fetchesAutomatically(entry("t1", album), choices))
        assertFalse(GroupDownloads.fetchesAutomatically(entry("v1", shuffle), choices))
        assertFalse(
            GroupDownloads.fetchesAutomatically(
                entry("v1", shuffle),
                choices + ("shuffle:channel:x" to GroupDownload.NO)
            )
        )
        assertTrue(GroupDownloads.fetchesAutomatically(entry("single"), choices))
    }

    @Test
    fun `choices survive a restart, and the groups already queued on the first run keep downloading`() = runTest {
        val file = temp.root.resolve("group-downloads.txt")
        val queue = MutableStateFlow(QueueSnapshot(listOf(entry("t1", album))))
        val hydrated = MutableStateFlow(true)
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher + Job())
        val first = GroupDownloads(file, scope, dispatcher).also { it.start(queue, hydrated) }
        assertTrue(first.allows("album:a"))

        queue.value = QueueSnapshot(listOf(entry("t1", album), entry("v1", shuffle)))
        assertEquals(GroupDownload.ASK, first.choices.value["shuffle:channel:x"])
        first.choose("shuffle:channel:x", GroupDownload.NO, "test")

        val second = GroupDownloads(file, scope, dispatcher)
        assertEquals(
            mapOf("album:a" to GroupDownload.YES, "shuffle:channel:x" to GroupDownload.NO),
            second.choices.value
        )
        scope.cancel()
    }
}
