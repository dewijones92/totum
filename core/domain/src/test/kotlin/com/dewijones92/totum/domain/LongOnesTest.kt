package com.dewijones92.totum.domain

import com.dewijones92.totum.common.HttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class LongOnesTest {

    private fun channelUrl(letter: Char) = "https://www.youtube.com/channel/UC${letter.toString().repeat(22)}"

    private fun video(
        id: String,
        channel: Char,
        hoursAgo: Int,
        kind: MediaContentKind = MediaContentKind.STANDARD,
        watch: String = "https://www.youtube.com/watch?v=$id",
    ) = MediaItem(
        id = MediaItemId(id),
        sourceId = SourceId("ytfeed:SUBSCRIPTIONS"),
        title = id,
        publishedAt = Instant.parse("2026-11-20T12:00:00Z").minusSeconds(hoursAgo * 3600L),
        duration = null,
        sourceUrl = HttpUrl.of(channelUrl(channel)),
        mediaUrl = HttpUrl.of(watch),
        contentKind = kind,
    )

    private fun lengths(vararg known: Pair<String, Duration>): (MediaItem) -> Duration? {
        val byId = known.toMap()
        return { byId[it.id.value] }
    }

    @Test
    fun `only videos over the minimum are chosen, newest first, with their length filled in`() {
        val items = listOf(video("short", 'a', 1), video("long-old", 'b', 5), video("long-new", 'c', 2))

        val pick = pickLongOnes(
            items,
            lengths("short" to 12.minutes, "long-old" to 50.minutes, "long-new" to 31.minutes),
        )

        assertEquals(listOf("long-new", "long-old"), pick.chosen.map { it.id.value })
        assertEquals(listOf(31.minutes, 50.minutes), pick.chosen.map { it.duration })
        assertEquals(1, pick.tooShort)
    }

    @Test
    fun `exactly twenty minutes counts as long and a second less does not`() {
        val items = listOf(video("exact", 'a', 1), video("under", 'b', 2))

        val pick = pickLongOnes(items, lengths("exact" to 20.minutes, "under" to 20.minutes - 1.seconds))

        assertEquals(listOf("exact"), pick.chosen.map { it.id.value })
    }

    @Test
    fun `no channel takes more than two of the twelve and the next channel fills the gap`() {
        val busy = (1..6).map { video("busy$it", 'a', it) }
        val others = (1..12).map { video("other$it", ('b' + it), 10 + it) }
        val all = (busy + others).associate { it.id.value to 40.minutes }

        val pick = pickLongOnes(busy + others, lengths(*all.toList().toTypedArray()))

        assertEquals(12, pick.chosen.size)
        assertEquals(2, pick.chosen.count { it.id.value.startsWith("busy") })
        val fromBusy = pick.chosen.filter { it.id.value.startsWith("busy") }.map { it.id.value }
        assertEquals(listOf("busy1", "busy2"), fromBusy)
        assertEquals(4, pick.heldBack)
    }

    @Test
    fun `a podcast show is one source, so it cannot flood the list either`() {
        val feed = "https://feeds.example.com/show.rss"
        val episodes = (1..5).map {
            MediaItem(
                id = MediaItemId("ep$it"),
                sourceId = SourceId(feed),
                title = "ep$it",
                publishedAt = Instant.parse("2026-11-20T12:00:00Z").minusSeconds(it * 3600L),
                duration = 45.minutes,
            )
        }
        val videos = (1..11).map { video("v$it", 'a' + it, 20 + it) }

        val pick = pickLongOnes(episodes + videos, { it.duration ?: 30.minutes })

        assertEquals(12, pick.chosen.size)
        assertEquals(2, pick.chosen.count { it.id.value.startsWith("ep") })
    }

    @Test
    fun `it stops at the limit even with more long videos behind`() {
        val items = (1..20).map { video("v$it", 'a' + it, it) }

        val pick = pickLongOnes(items, { 30.minutes }, LongOnesRules(limit = 5))

        assertEquals(listOf("v1", "v2", "v3", "v4", "v5"), pick.chosen.map { it.id.value })
        assertEquals(5, pick.considered)
    }

    @Test
    fun `Shorts of any length, lives and music are never chosen`() {
        val items = listOf(
            video("tagged-short", 'a', 1, kind = MediaContentKind.SHORT),
            video("url-short", 'b', 2, watch = "https://www.youtube.com/shorts/url-short"),
            video("live", 'c', 3, kind = MediaContentKind.LIVE),
            video("music", 'd', 4, kind = MediaContentKind.MUSIC),
            video("fine", 'e', 5),
        )

        val pick = pickLongOnes(items, { 60.minutes })

        assertEquals(listOf("fine"), pick.chosen.map { it.id.value })
    }

    @Test
    fun `a stream that has ended is an ordinary long video`() {
        val pick = pickLongOnes(listOf(video("replay", 'a', 1, kind = MediaContentKind.WAS_LIVE)), { 3.minutes * 40 })

        assertEquals(listOf("replay"), pick.chosen.map { it.id.value })
    }

    @Test
    fun `an item with no date cannot be placed and is left out`() {
        val undated = video("undated", 'a', 1).copy(publishedAt = null)

        assertTrue(pickLongOnes(listOf(undated), { 60.minutes }).chosen.isEmpty())
    }

    @Test
    fun `items whose length is not known yet are handed back newest first to be looked up`() {
        val items = listOf(video("unknown-old", 'a', 9), video("known", 'b', 3), video("unknown-new", 'c', 1))

        val pick = pickLongOnes(items, lengths("known" to 40.minutes))

        assertEquals(listOf("known"), pick.chosen.map { it.id.value })
        assertEquals(listOf("unknown-new", "unknown-old"), pick.unknownLength.map { it.id.value })
    }

    @Test
    fun `an unknown from a channel already at its cap is not worth a look-up`() {
        val items = listOf(video("a1", 'a', 1), video("a2", 'a', 2), video("a3", 'a', 3), video("b1", 'b', 4))

        val pick = pickLongOnes(items, lengths("a1" to 30.minutes, "a2" to 30.minutes))

        assertEquals(listOf("b1"), pick.unknownLength.map { it.id.value })
    }

    @Test
    fun `the same video listed twice is one candidate`() {
        val once = video("dup", 'a', 1)

        val pick = pickLongOnes(listOf(once, once.copy(title = "again")), { 30.minutes })

        assertEquals(1, pick.chosen.size)
    }

    @Test
    fun `a channel is the same source whichever listing the video came through`() {
        val viaFeed = video("x", 'a', 1)
        val viaRss = video("y", 'a', 2).copy(sourceId = SourceId(channelUrl('a')))
        val third = video("z", 'a', 3).copy(sourceId = SourceId(channelUrl('a')))

        val pick = pickLongOnes(listOf(viaFeed, viaRss, third), { 30.minutes })

        assertEquals(listOf("x", "y"), pick.chosen.map { it.id.value })
    }
}
