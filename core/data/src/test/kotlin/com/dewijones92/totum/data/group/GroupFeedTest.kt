package com.dewijones92.totum.data.group

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.SourceGroup
import com.dewijones92.totum.domain.SourceGroupId
import com.dewijones92.totum.domain.SourceId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class GroupFeedTest {

    private val channel = MediaSource.VideoChannel(
        SourceId("chan"),
        "A channel",
        HttpUrl.of("https://www.youtube.com/channel/UCaaa"),
    )
    private val feed = MediaSource.PodcastFeed(
        SourceId("pod"),
        "A podcast",
        HttpUrl.of("https://example.com/feed.xml"),
    )
    private val group = SourceGroup(SourceGroupId("g"), "Politics", listOf(channel, feed))

    private fun groupFeed(itemsBySource: Map<SourceId, () -> List<MediaItem>>) =
        GroupFeed { source -> itemsBySource[source.id]?.invoke() ?: emptyList() }

    @Test
    fun `both pillars merge into one newest-first list`() = runTest {
        val result = groupFeed(
            mapOf(
                channel.id to { listOf(item("old video", channel.id, "2026-07-01T00:00:00Z")) },
                feed.id to { listOf(item("new episode", feed.id, "2026-07-29T00:00:00Z")) },
            ),
        ).itemsFor(group)

        assertEquals(listOf("new episode", "old video"), result.map { it.title })
    }

    @Test
    fun `a member that cannot be read does not take the group down`() = runTest {
        val result = groupFeed(
            mapOf(
                channel.id to { throw GroupMemberUnreadable("network") },
                feed.id to { listOf(item("survivor", feed.id, "2026-07-29T00:00:00Z")) },
            ),
        ).itemsFor(group)

        assertEquals(listOf("survivor"), result.map { it.title })
    }

    @Test
    fun `a member you never subscribed to still contributes`() = runTest {
        // The whole reason a membership carries its source: the picker is on every channel
        // page, so a group may name a channel the app has no subscription to look up.
        val stranger = MediaSource.VideoChannel(
            SourceId("stranger"),
            "Never subscribed",
            HttpUrl.of("https://www.youtube.com/channel/UCzzz"),
        )

        val result = groupFeed(
            mapOf(stranger.id to { listOf(item("from a stranger", stranger.id, "2026-07-29T00:00:00Z")) }),
        ).itemsFor(group.with(stranger))

        assertEquals(listOf("from a stranger"), result.map { it.title })
    }

    @Test
    fun `the same item from two members appears once`() = runTest {
        val shared = item("cross-posted", channel.id, "2026-07-29T00:00:00Z")

        val result = groupFeed(
            mapOf(channel.id to { listOf(shared) }, feed.id to { listOf(shared) }),
        ).itemsFor(group)

        assertEquals(1, result.size)
    }

    @Test
    fun `an undated item sorts last rather than being dropped`() = runTest {
        val result = groupFeed(
            mapOf(
                channel.id to { listOf(item("no date", channel.id, publishedAt = null)) },
                feed.id to { listOf(item("dated", feed.id, "2026-07-01T00:00:00Z")) },
            ),
        ).itemsFor(group)

        assertEquals(listOf("dated", "no date"), result.map { it.title })
    }

    @Test
    fun `an empty group is an empty feed and asks nothing`() = runTest {
        var asked = 0
        val result = GroupFeed { _ ->
            asked++
            emptyList()
        }.itemsFor(SourceGroup(SourceGroupId("g"), "Empty"))

        assertEquals(emptyList<MediaItem>(), result)
        assertEquals(0, asked)
    }

    @Test
    fun `channel pages that only give an age still merge newest first across channels`() = runTest {
        val now = Instant.parse("2026-10-29T12:00:00Z")
        val ben = MediaSource.VideoChannel(SourceId("ben"), "Ben", HttpUrl.of("https://www.youtube.com/channel/UCben"))
        val cat = MediaSource.VideoChannel(SourceId("cat"), "Cat", HttpUrl.of("https://www.youtube.com/channel/UCcat"))
        fun aged(id: String, source: SourceId, text: String) =
            MediaItem(
                id = MediaItemId(id),
                sourceId = source,
                title = id,
                publishedAt = null,
                publishedText = text,
                duration = null
            )
        val feed = GroupFeed(
            ChannelSourceItems(
                pageOf = { source ->
                    if (source.id == ben.id) {
                        listOf(aged("ben-new", ben.id, "4 days ago"), aged("ben-old", ben.id, "3 weeks ago"))
                    } else {
                        listOf(aged("cat-new", cat.id, "3 days ago"), aged("cat-old", cat.id, "2 weeks ago"))
                    }
                },
                feedOf = { null },
                clock = { now },
            ),
        )

        val merged = feed.itemsFor(SourceGroup(SourceGroupId("travel"), "Travel", listOf(ben, cat)))

        assertEquals(listOf("cat-new", "ben-new", "cat-old", "ben-old"), merged.map { it.id.value })
    }

    @Test
    fun `older videos take their real publish day and sit correctly among the other channels`() = runTest {
        val now = Instant.parse("2026-10-29T12:00:00Z")
        val cat = MediaSource.VideoChannel(SourceId("cat"), "Cat", HttpUrl.of("https://www.youtube.com/channel/UCcat"))
        val bald = MediaSource.VideoChannel(
            SourceId("bald"),
            "Bald",
            HttpUrl.of("https://www.youtube.com/channel/UCbald")
        )
        fun aged(id: String, source: SourceId, text: String) =
            MediaItem(
                id = MediaItemId(id),
                sourceId = source,
                title = id,
                publishedAt = null,
                publishedText = text,
                duration = null
            )
        val days = mapOf("cat-may" to java.time.LocalDate.of(2026, 5, 14))
        val feed = GroupFeed(
            ChannelSourceItems(
                pageOf = { source ->
                    if (source.id == cat.id) {
                        listOf(aged("cat-jun", cat.id, "4 months ago"), aged("cat-may", cat.id, "4 months ago"))
                    } else {
                        listOf(aged("bald-jun", bald.id, "4 months ago"))
                    }
                },
                feedOf = { source ->
                    when (source.id) {
                        cat.id -> listOf(item("cat-jun", cat.id, "2026-06-14T15:29:00Z"))
                        else -> listOf(item("bald-jun", bald.id, "2026-06-02T16:30:00Z"))
                    }
                },
                dayOf = { id -> days[id.value] },
                clock = { now },
            ),
        )

        val merged = feed.itemsFor(SourceGroup(SourceGroupId("travel"), "Travel", listOf(cat, bald)))

        assertEquals(listOf("cat-jun", "bald-jun", "cat-may"), merged.map { it.id.value })
    }

    @Test
    fun `each channel's account progress is read with the group, and a failure there loses nothing`() = runTest {
        val ben = MediaSource.VideoChannel(SourceId("ben"), "Ben", HttpUrl.of("https://www.youtube.com/channel/UCben"))
        val cat = MediaSource.VideoChannel(SourceId("cat"), "Cat", HttpUrl.of("https://www.youtube.com/channel/UCcat"))
        val asked = mutableListOf<SourceId>()
        val feed = GroupFeed(
            ChannelSourceItems(
                pageOf = { source -> listOf(item("${source.id.value}-1", source.id, "2026-10-01T00:00:00Z")) },
                feedOf = { null },
                accountProgressOf = { source ->
                    asked += source.id
                    if (source.id == cat.id) error("signed-in browse failed")
                },
            ),
        )

        val merged = feed.itemsFor(SourceGroup(SourceGroupId("travel"), "Travel", listOf(ben, cat)))

        assertEquals(setOf(ben.id, cat.id), asked.toSet())
        assertEquals(setOf("ben-1", "cat-1"), merged.map { it.id.value }.toSet())
    }

    private fun item(title: String, sourceId: SourceId, publishedAt: String?) = MediaItem(
        id = MediaItemId(title),
        sourceId = sourceId,
        title = title,
        publishedAt = publishedAt?.let(Instant::parse),
        duration = null,
    )
}
