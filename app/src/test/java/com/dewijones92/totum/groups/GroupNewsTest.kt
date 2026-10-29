package com.dewijones92.totum.groups

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.SourceGroup
import com.dewijones92.totum.domain.SourceGroupId
import com.dewijones92.totum.domain.SourceId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class GroupNewsTest {

    private var now = Instant.parse("2026-10-29T12:00:00Z")
    private val ben = MediaSource.VideoChannel(
        SourceId("ben"),
        "Ben",
        HttpUrl.of("https://www.youtube.com/channel/UCben")
    )
    private val group = SourceGroup(SourceGroupId("travel"), "Travel", listOf(ben))
    private val uploads = mutableListOf<MediaItem>()
    private val notified = mutableListOf<String>()
    private val store = InMemoryGroupNewsStore()
    private val news =
        GroupNews(store, latest = {
            uploads.toList()
        }, notify = { _, item ->
            notified += item.id.value
            true
        }, clock = { now })

    private fun upload(id: String, at: String) {
        uploads += MediaItem(MediaItemId(id), ben.id, id, Instant.parse(at), duration = null)
    }

    @Test
    fun `the first look only notes the time, so old videos are not announced`() = runTest {
        upload("old", "2026-10-20T10:00:00Z")
        news.check(listOf(group))
        assertEquals(emptyList<String>(), notified)
        assertEquals(0, news.newCount(group.id))
    }

    @Test
    fun `a video published after the last look is new, announced once, and counted`() = runTest {
        news.check(listOf(group))
        upload("fresh", "2026-10-29T15:00:00Z")
        now = Instant.parse("2026-10-29T16:00:00Z")
        news.check(listOf(group))
        news.check(listOf(group))
        assertEquals(listOf("fresh"), notified)
        assertEquals(1, news.newCount(group.id))
    }

    @Test
    fun `opening the group clears the count and says since when, for the markers`() = runTest {
        news.check(listOf(group))
        upload("fresh", "2026-10-29T15:00:00Z")
        now = Instant.parse("2026-10-29T16:00:00Z")
        news.check(listOf(group))

        val since = news.opened(group.id)

        assertEquals(Instant.parse("2026-10-29T12:00:00Z"), since)
        assertEquals(0, news.newCount(group.id))
        assertEquals(now, news.opened(group.id))
    }

    @Test
    fun `what was new last time is known at start-up, before any check`() = runTest {
        val saved =
            InMemoryGroupNewsStore(
                mapOf("travel" to GroupNewsState(seenAt = 1L, freshBy = mapOf("ben" to listOf("a", "b"))))
            )
        val started = GroupNews(saved, latest = { null }, notify = { _, _ -> true }, clock = { now })
        started.warm()
        assertEquals(2, started.newCount(group.id))
    }

    @Test
    fun `a deleted group's count is forgotten, so no badge is left behind`() = runTest {
        news.check(listOf(group))
        upload("fresh", "2026-10-29T15:00:00Z")
        now = Instant.parse("2026-10-29T16:00:00Z")
        news.check(listOf(group))
        assertEquals(1, news.newCount(group.id))

        news.check(emptyList())

        assertEquals(0, news.states.value.values.sumOf { it.fresh.size })
    }

    @Test
    fun `a channel that cannot be read keeps what it already had new`() = runTest {
        var reachable = true
        val flaky =
            GroupNews(
                store,
                latest = { if (reachable) uploads.toList() else null },
                notify = { _, _ -> true },
                clock = { now }
            )
        flaky.check(listOf(group))
        upload("fresh", "2026-10-29T15:00:00Z")
        now = Instant.parse("2026-10-29T16:00:00Z")
        flaky.check(listOf(group))
        reachable = false
        flaky.check(listOf(group))
        assertEquals(1, flaky.newCount(group.id))
    }

    @Test
    fun `opening the group while a check is reading does not bring the count back`() = runTest {
        lateinit var racing: GroupNews
        racing = GroupNews(
            InMemoryGroupNewsStore(),
            latest = {
                racing.opened(group.id)
                uploads.toList()
            },
            notify = { _, _ -> true },
            clock = { now },
        )
        racing.check(listOf(group))
        upload("fresh", "2026-10-29T15:00:00Z")
        now = Instant.parse("2026-10-29T16:00:00Z")
        racing.check(listOf(group))
        assertEquals(0, racing.newCount(group.id))
    }

    @Test
    fun `a channel added to an old group only counts what it uploads after joining`() = runTest {
        news.check(listOf(group))
        now = Instant.parse("2026-10-29T16:00:00Z")
        val cat = MediaSource.VideoChannel(SourceId("cat"), "Cat", HttpUrl.of("https://www.youtube.com/channel/UCcat"))
        val catUploads =
            listOf(
                MediaItem(
                    MediaItemId("cat-old"),
                    cat.id,
                    "cat-old",
                    Instant.parse("2026-10-29T13:00:00Z"),
                    duration = null
                )
            )
        val withCat = GroupNews(
            store,
            latest = { source -> if (source.id == cat.id) catUploads else uploads.toList() },
            notify = { _, item ->
                notified += item.id.value
                true
            },
            clock = { now },
        )
        withCat.check(listOf(group.copy(sources = group.sources + cat)))
        assertEquals(emptyList<String>(), notified)
        assertEquals(0, withCat.newCount(group.id))
    }

    @Test
    fun `a group never looked at has no since`() = runTest {
        assertNull(news.opened(group.id))
    }

    @Test
    fun `a notification that could not be posted is tried again next time`() = runTest {
        var allowed = false
        val picky =
            GroupNews(store, latest = {
                uploads.toList()
            }, notify = { _, item ->
                if (allowed) notified += item.id.value
                allowed
            }, clock = { now })
        picky.check(listOf(group))
        upload("fresh", "2026-10-29T15:00:00Z")
        now = Instant.parse("2026-10-29T16:00:00Z")
        picky.check(listOf(group))
        allowed = true
        picky.check(listOf(group))
        assertEquals(listOf("fresh"), notified)
    }
}
