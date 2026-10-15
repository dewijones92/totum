package com.dewijones92.totum.uploads

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.pins.Pin
import com.dewijones92.totum.pins.PinnedSource
import com.dewijones92.totum.queue.GroupSpot
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class NewUploadsTest {

    private val channelUrl = "https://www.youtube.com/channel/UCpeepshowpeepshowpeepsh"
    private val peep = MediaSource.VideoChannel(SourceId(channelUrl), "Peep Show", HttpUrl.of(channelUrl))
    private val other = MediaSource.VideoChannel(
        SourceId("https://www.youtube.com/channel/UCotherotherotherotherot"),
        "Other",
        HttpUrl.of("https://www.youtube.com/channel/UCotherotherotherotherot"),
    )
    private val shuffle = Pin.ShuffleAllOf(
        target = "peep",
        title = "Peep Show",
        artUrl = null,
        sources = listOf(PinnedSource.of(peep)),
        includeShorts = false,
    )

    private fun video(id: String, day: Long, short: Boolean = false, source: MediaSource = peep) = MediaItem(
        MediaItemId(id),
        source.id,
        "clip $id",
        publishedAt = Instant.ofEpochSecond(day * 86_400),
        duration = null,
        mediaUrl = HttpUrl.of(
            if (short) "https://www.youtube.com/shorts/$id" else "https://www.youtube.com/watch?v=$id"
        ),
    )

    private val latest = mutableMapOf<SourceId, List<MediaItem>?>()
    private val added = mutableListOf<Triple<String, List<String>, GroupSpot>>()
    private val groupsInQueue = mutableSetOf("shuffle:peep")
    private var inbox = listOf<MediaSource>()
    private var inboxComplete = true
    private var saved = UploadsState()

    private var adoptable: WatchedGroup? = null

    private val uploads = NewUploads(
        latest = { latest[it.id] },
        queue = object : UploadsQueue {
            override fun groupIds() = groupsInQueue
            override fun add(group: QueueGroup, items: List<PlayableItem>, spot: GroupSpot): Int {
                added += Triple(group.id, items.map { it.item.id.value }, spot)
                return items.size
            }
        },
        inboxSources = { InboxSources(inbox, complete = inboxComplete) },
        store = object : UploadsStore {
            override suspend fun load() = saved
            override suspend fun save(state: UploadsState) {
                saved = state
            }
        },
        adopt = { id -> adoptable?.takeIf { it.groupId == id } },
    )

    @Test
    fun `a new upload goes next in the shuffled group, newest first, and only once`() = runTest {
        uploads.remember("shuffle:peep", shuffle, listOf(video("old1", 1), video("old2", 2)))
        latest[peep.id] = listOf(video("old2", 2), video("new1", 10), video("new2", 11))

        uploads.check("test")
        uploads.check("test again")

        assertEquals(listOf(Triple("shuffle:peep", listOf("new2", "new1"), GroupSpot.NEXT)), added)
    }

    @Test
    fun `shorts follow the shuffle's choice`() = runTest {
        uploads.remember("shuffle:peep", shuffle, emptyList())
        latest[peep.id] = listOf(video("s1", 10, short = true), video("v1", 11))

        uploads.check("test")

        assertEquals(listOf("v1"), added.single().second)
    }

    @Test
    fun `a group that left the queue is forgotten`() = runTest {
        uploads.remember("shuffle:peep", shuffle, emptyList())
        groupsInQueue.clear()
        latest[peep.id] = listOf(video("new1", 10))

        uploads.check("test")

        assertTrue(added.isEmpty())
        assertTrue(saved.groups.isEmpty())
    }

    @Test
    fun `an inbox source adds nothing on first look, then its new uploads land at the end of New`() = runTest {
        inbox = listOf(other)
        latest[other.id] = listOf(video("o1", 1, source = other))
        uploads.check("first")
        assertTrue(added.isEmpty())

        latest[other.id] = listOf(
            video("o1", 1, source = other),
            video("o2", 5, source = other),
            video("o3", 6, source = other),
        )
        uploads.check("second")

        assertEquals(listOf(Triple(NewUploads.INBOX.id, listOf("o3", "o2"), GroupSpot.END)), added)
    }

    @Test
    fun `a source with a shuffled group in the queue feeds the group, not the inbox`() = runTest {
        uploads.remember("shuffle:peep", shuffle, emptyList())
        inbox = listOf(peep)
        latest[peep.id] = listOf(video("new1", 10))

        uploads.check("test")

        assertEquals(listOf("shuffle:peep"), added.map { it.first })
    }

    @Test
    fun `pulling one group checks only that group, and a failed source is reported`() = runTest {
        uploads.remember("shuffle:peep", shuffle, emptyList())
        inbox = listOf(other)
        latest[peep.id] = null

        val report = uploads.check("manual", onlyGroup = "shuffle:peep")

        assertEquals(listOf("Peep Show"), report.failed)
        assertTrue(saved.inboxSeen.isEmpty())
    }

    @Test
    fun `a shuffled group from before this feature is adopted, and only unplayed uploads come in`() = runTest {
        adoptable = WatchedGroup("shuffle:peep", shuffle, seen = setOf("queued1", "played1"))
        latest[peep.id] = listOf(video("queued1", 1), video("played1", 2), video("fresh", 12))

        uploads.check("manual", onlyGroup = "shuffle:peep")

        assertEquals(listOf(Triple("shuffle:peep", listOf("fresh"), GroupSpot.NEXT)), added)
        assertTrue("shuffle:peep" in saved.groups)
    }

    @Test
    fun `a podcast feed longer than any cap never offers its back catalogue as new`() = runTest {
        inbox = listOf(other)
        latest[other.id] = (1..6_000L).map { video("e$it", it, source = other) }

        uploads.check("first")
        uploads.check("second")

        assertTrue(added.isEmpty())
    }

    @Test
    fun `uploads a shuffled group already delivered are not offered to the inbox once the group leaves`() = runTest {
        inbox = listOf(peep)
        latest[peep.id] = listOf(video("u0", 1))
        uploads.check("inbox's first look")
        uploads.remember("shuffle:peep", shuffle, listOf(video("u0", 1)))
        latest[peep.id] = listOf(video("u0", 1), video("u1", 10))
        uploads.check("while grouped")
        assertEquals(listOf("u1"), added.single().second)
        groupsInQueue.clear()
        added.clear()

        uploads.check("after the group left")

        assertTrue(added.isEmpty())
    }

    @Test
    fun `pulling a group nobody is watching says so`() = runTest {
        val report = uploads.check("manual", onlyGroup = "shuffle:peep")

        assertEquals(listOf("shuffle:peep"), report.unwatched)
    }

    @Test
    fun `switching the inbox on later starts with a first look, even for a source a group used to cover`() = runTest {
        uploads.remember("shuffle:peep", shuffle, emptyList())
        latest[peep.id] = listOf(video("u0", 1))
        uploads.check("grouped, inbox off")
        groupsInQueue.clear()
        added.clear()
        inbox = listOf(peep)
        latest[peep.id] = listOf(video("u0", 1), video("u1", 5), video("u2", 6))

        uploads.check("inbox switched on")

        assertTrue(added.isEmpty())
    }

    @Test
    fun `a group removed while its feed was being fetched is not brought back`() = runTest {
        uploads.remember("shuffle:peep", shuffle, emptyList())
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val fetching = kotlinx.coroutines.CompletableDeferred<Unit>()
        val slow = NewUploads(
            latest = {
                fetching.complete(Unit)
                gate.await()
                listOf(video("new", 9))
            },
            queue = object : UploadsQueue {
                override fun groupIds() = groupsInQueue
                override fun add(group: QueueGroup, items: List<PlayableItem>, spot: GroupSpot): Int {
                    added += Triple(group.id, items.map { it.item.id.value }, spot)
                    return items.size
                }
            },
            inboxSources = { InboxSources(emptyList(), complete = true) },
            store = object : UploadsStore {
                override suspend fun load() = saved
                override suspend fun save(state: UploadsState) {
                    saved = state
                }
            },
        )
        val running = backgroundScope.async { slow.check("slow") }
        fetching.await()
        groupsInQueue.clear()
        gate.complete(Unit)
        running.await()

        assertTrue(added.isEmpty())
        assertTrue("shuffle:peep" !in saved.groups)
    }

    @Test
    fun `a check that could not list every source keeps the inbox's memory, so later uploads still arrive`() = runTest {
        inbox = listOf(peep)
        latest[peep.id] = listOf(video("u0", 1))
        uploads.check("first look")
        inbox = emptyList()
        inboxComplete = false
        uploads.check("subscriptions did not load")
        inbox = listOf(peep)
        inboxComplete = true
        latest[peep.id] = listOf(video("u0", 1), video("u1", 5))

        uploads.check("subscriptions back")

        assertEquals(listOf(Triple(NewUploads.INBOX.id, listOf("u1"), GroupSpot.END)), added)
    }
}
