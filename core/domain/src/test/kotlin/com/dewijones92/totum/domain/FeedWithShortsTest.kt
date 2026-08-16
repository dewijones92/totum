package com.dewijones92.totum.domain

import com.dewijones92.totum.common.HttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shorts threaded through the feed, in the same list as the videos and the live streams.
 *
 * Dewi, 2026-08-16: *"I want YouTube Shorts, YouTube live streams, YouTube videos to be all treated
 * the same … always displayed everywhere but just tagged."*
 *
 * Interleaved rather than sorted because a Short carries no date — YouTube's Shorts tiles have a
 * title, a thumbnail and a view count and nothing else, so a chronological merge is not on offer.
 * These pin the spacing so it cannot quietly become "all the Shorts at the end", which is what any
 * naive concatenation would do.
 */
class FeedWithShortsTest {

    private fun video(n: Int) = item("v$n", MediaContentKind.STANDARD)

    private fun short(n: Int) = item("s$n", MediaContentKind.SHORT)

    private fun item(id: String, kind: MediaContentKind) = MediaItem(
        id = MediaItemId(id),
        sourceId = SourceId("youtube"),
        title = id,
        publishedAt = null,
        duration = null,
        mediaUrl = HttpUrl.of("https://www.youtube.com/watch?v=$id"),
        contentKind = kind,
    )

    private fun idsOf(items: List<MediaItem>) = items.map { it.id.value }

    @Test
    fun `a short appears after every fifth video`() {
        val merged = interleaveShorts(List(10) { video(it) }, List(2) { short(it) })

        assertEquals(
            listOf("v0", "v1", "v2", "v3", "v4", "s0", "v5", "v6", "v7", "v8", "v9", "s1"),
            idsOf(merged),
        )
    }

    /** The top of the list is where the newest video belongs; opening on a Short reads wrongly. */
    @Test
    fun `the feed never opens on a short`() {
        val merged = interleaveShorts(List(10) { video(it) }, List(5) { short(it) })

        assertEquals("v0", idsOf(merged).first())
    }

    /**
     * Everything fetched is shown. Dropping the overflow would make "how many Shorts you see"
     * depend on how long the video feed happened to be, which nobody could predict from the screen.
     */
    @Test
    fun `shorts that do not fit are still shown`() {
        val merged = interleaveShorts(List(6) { video(it) }, List(4) { short(it) })

        assertEquals(10, merged.size)
        (0 until 4).forEach { n ->
            assertTrue("s$n missing from $merged", idsOf(merged).contains("s$n"))
        }
    }

    /** The videos keep the order the feed gave them — that order is the feed's whole meaning. */
    @Test
    fun `the videos keep their order`() {
        val merged = interleaveShorts(List(12) { video(it) }, List(3) { short(it) })

        assertEquals(List(12) { "v$it" }, idsOf(merged).filter { it.startsWith("v") })
    }

    /** A Short the feed already supplied keeps its place rather than appearing twice. */
    @Test
    fun `a short already in the feed is not added again`() {
        val feed = listOf(video(0), short(0), video(1))

        val merged = interleaveShorts(feed, listOf(short(0), short(1)))

        assertEquals(1, idsOf(merged).count { it == "s0" })
    }

    @Test
    fun `nothing to add leaves the feed untouched`() {
        val feed = List(3) { video(it) }

        assertEquals(feed, interleaveShorts(feed, emptyList()))
    }

    /** Shorts with no videos yet — a feed that has not loaded must not swallow them. */
    @Test
    fun `shorts alone are still a list`() {
        val merged = interleaveShorts(emptyList(), List(2) { short(it) })

        assertEquals(listOf("s0", "s1"), idsOf(merged))
    }

    /** A nonsense spacing must not divide by zero or lose anything. */
    @Test
    fun `a spacing of zero is survivable`() {
        val merged = interleaveShorts(List(3) { video(it) }, List(2) { short(it) }, everyNth = 0)

        assertEquals(5, merged.size)
    }

    /** They stay tagged: the badge is the whole point of listing them together. */
    @Test
    fun `threaded shorts keep their SHORT kind`() {
        val merged = interleaveShorts(List(5) { video(it) }, listOf(short(0)))

        assertEquals(
            MediaContentKind.SHORT,
            merged.first { it.id.value == "s0" }.contentKind,
        )
    }
}
