package com.dewijones92.totum.data.group

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.SourceGroup
import com.dewijones92.totum.domain.SourceGroupId
import com.dewijones92.totum.domain.SourceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

class GroupFeedKeptListsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val channel = MediaSource.VideoChannel(
        SourceId("chan"),
        "A channel",
        HttpUrl.of("https://www.youtube.com/channel/UCaaa"),
    )
    private val podcast = MediaSource.PodcastFeed(
        SourceId("pod"),
        "A podcast",
        HttpUrl.of("https://example.com/feed.xml"),
    )
    private val group = SourceGroup(SourceGroupId("g"), "Politics", listOf(channel, podcast))
    private val other = SourceGroup(SourceGroupId("h"), "Music", listOf(channel))

    private fun item(title: String, source: SourceId, published: String) = MediaItem(
        id = MediaItemId(title),
        sourceId = source,
        title = title,
        publishedAt = Instant.parse(published),
        duration = null,
        mediaUrl = HttpUrl.of("https://feed.test/${title.replace(' ', '-')}.mp3"),
    )

    private val reads = mutableMapOf<SourceId, () -> List<MediaItem>>()

    private fun feedOver(file: File?) = GroupFeed(
        { source -> reads.getValue(source.id)() },
        file,
        Dispatchers.Unconfined,
    )

    private fun healthy() {
        reads[channel.id] = { listOf(item("old video", channel.id, "2026-07-01T00:00:00Z")) }
        reads[podcast.id] = { listOf(item("new episode", podcast.id, "2026-07-29T00:00:00Z")) }
    }

    @Test
    fun `a list survives a restart and comes back whole, newest first`() = runTest {
        healthy()
        val file = folder.root.resolve("group-feed.json")
        feedOver(file).itemsFor(group)

        val after = feedOver(file).lastKnown(group)

        assertEquals(listOf("new episode", "old video"), after?.map { it.title })
        assertEquals(listOf(podcast.id, channel.id), after?.map { it.sourceId })
        assertEquals(Instant.parse("2026-07-29T00:00:00Z"), after?.first()?.publishedAt)
    }

    @Test
    fun `nothing is shown for a group that was never loaded, and no file appears before the first load`() = runTest {
        val file = folder.root.resolve("group-feed.json")

        val fresh = feedOver(file)

        assertNull(fresh.lastKnown(group))
        assertFalse(file.exists())
    }

    @Test
    fun `a member removed from the group is not shown from the kept list after a restart`() = runTest {
        healthy()
        val file = folder.root.resolve("group-feed.json")
        feedOver(file).itemsFor(group)

        val after = feedOver(file).lastKnown(group.copy(sources = listOf(channel)))

        assertEquals(listOf("old video"), after?.map { it.title })
    }

    @Test
    fun `a corrupt file starts empty and is replaced by the next good load`() = runTest {
        healthy()
        val file = folder.newFile("group-feed.json").also { it.writeText("{ this is not json") }

        val feed = feedOver(file)
        assertNull(feed.lastKnown(group))
        assertTrue(feed.keptLists.value.isEmpty())

        feed.itemsFor(group)

        assertEquals(listOf("new episode", "old video"), feedOver(file).lastKnown(group)?.map { it.title })
    }

    @Test
    fun `a member that fails keeps its rows from the last list, in the file as well`() = runTest {
        healthy()
        val file = folder.root.resolve("group-feed.json")
        val first = feedOver(file)
        first.itemsFor(group)

        reads[channel.id] = { throw GroupMemberUnreadable("network") }
        reads[podcast.id] = { listOf(item("newer episode", podcast.id, "2026-08-01T00:00:00Z")) }
        val shown = first.itemsFor(group)

        assertEquals(listOf("newer episode"), shown.map { it.title })
        assertEquals(listOf("newer episode", "old video"), first.lastKnown(group)?.map { it.title })
        assertEquals(listOf("newer episode", "old video"), feedOver(file).lastKnown(group)?.map { it.title })
    }

    @Test
    fun `a member that reads fine and has nothing is not carried over`() = runTest {
        healthy()
        val first = feedOver(null)
        first.itemsFor(group)

        reads[channel.id] = { emptyList() }
        first.itemsFor(group)

        assertEquals(listOf("new episode"), first.lastKnown(group)?.map { it.title })
    }

    @Test
    fun `a member that fails on the very first load leaves nothing to carry`() = runTest {
        reads[channel.id] = { throw GroupMemberUnreadable("network") }
        reads[podcast.id] = { listOf(item("new episode", podcast.id, "2026-07-29T00:00:00Z")) }

        val feed = feedOver(null)
        feed.itemsFor(group)

        assertEquals(listOf("new episode"), feed.lastKnown(group)?.map { it.title })
    }

    @Test
    fun `every member failing keeps the whole earlier list rather than blanking the page`() = runTest {
        healthy()
        val first = feedOver(null)
        first.itemsFor(group)

        reads[channel.id] = { throw GroupMemberUnreadable("offline") }
        reads[podcast.id] = { throw GroupMemberUnreadable("offline") }
        val shown = first.itemsFor(group)

        assertTrue(shown.isEmpty())
        assertEquals(listOf("new episode", "old video"), first.lastKnown(group)?.map { it.title })
    }

    @Test
    fun `two groups loading together both end up in the file`() = runTest {
        healthy()
        val file = folder.root.resolve("group-feed.json")
        val feed = feedOver(file)

        listOf(async { feed.itemsFor(group) }, async { feed.itemsFor(other) }).awaitAll()

        val reread = feedOver(file)
        assertEquals(listOf("new episode", "old video"), reread.lastKnown(group)?.map { it.title })
        assertEquals(listOf("old video"), reread.lastKnown(other)?.map { it.title })
        assertEquals(setOf(group.id, other.id), reread.keptLists.value.keys)
    }

    @Test
    fun `a list that cannot be saved stays in memory and does not fail the load`() = runTest {
        healthy()
        val blocked = folder.newFolder("group-feed.json")
        blocked.resolve("occupied").writeText("x")

        val feed = feedOver(blocked)
        val shown = feed.itemsFor(group)

        assertEquals(2, shown.size)
        assertEquals(listOf("new episode", "old video"), feed.lastKnown(group)?.map { it.title })
        assertTrue("the directory in the way is untouched", blocked.isDirectory && blocked.resolve("occupied").exists())
    }

    @Test
    fun `without a file the lists live in memory only`() = runTest {
        healthy()
        val feed = feedOver(null)

        feed.itemsFor(group)

        assertEquals(listOf("new episode", "old video"), feed.lastKnown(group)?.map { it.title })
        assertEquals(0, folder.root.listFiles()?.size ?: 0)
    }

    @Test
    fun `a group of three hundred channels with fifteen videos each is kept and comes back whole`() = runTest {
        val channels = (1..CHANNELS).map { n ->
            MediaSource.VideoChannel(
                SourceId("c$n"),
                "Channel $n",
                HttpUrl.of("https://www.youtube.com/channel/UC$n"),
            )
        }
        channels.forEachIndexed { n, source ->
            reads[source.id] = {
                (1..PER_CHANNEL).map { v ->
                    val at = Instant.parse("2026-07-01T00:00:00Z").plusSeconds((n * 100 + v).toLong())
                    item("c$n v$v", source.id, at.toString())
                }
            }
        }
        val big = SourceGroup(SourceGroupId("big"), "Everything", channels)
        val file = folder.root.resolve("group-feed.json")

        val shown = feedOver(file).itemsFor(big)
        val back = feedOver(file).lastKnown(big)

        assertEquals(CHANNELS * PER_CHANNEL, shown.size)
        assertEquals(shown.map { it.id }, back?.map { it.id })
        val dates = shown.map { it.publishedAt!! }
        assertEquals(dates.sortedDescending(), dates)
    }

    private companion object {
        const val CHANNELS = 300
        const val PER_CHANNEL = 15
    }
}
