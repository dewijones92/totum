package com.dewijones92.totum.data.group

import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.SourceId
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class GroupDatesTest {

    private val now = Instant.parse("2026-10-29T12:00:00Z")
    private val channel = SourceId("ben")

    private fun page(id: String, text: String?) = MediaItem(
        id = MediaItemId(id),
        sourceId = channel,
        title = id,
        publishedAt = null,
        publishedText = text,
        duration = null,
    )

    private fun feed(id: String, at: String) = MediaItem(
        id = MediaItemId(id),
        sourceId = channel,
        title = "$id (feed)",
        publishedAt = Instant.parse(at),
        duration = null,
    )

    @Test
    fun `a feed date wins over the page's rough one, and the page keeps its own details`() {
        val dated = datedChannelItems(
            exact = listOf(feed("a", "2026-10-25T15:49:00Z")),
            page = listOf(page("a", "4 days ago")),
            observedAt = now,
        )
        assertEquals(Instant.parse("2026-10-25T15:49:00Z"), dated.single().publishedAt)
        assertEquals("a", dated.single().title)
    }

    @Test
    fun `older page items get a date from their age, never newer than the item before them`() {
        val dated = datedChannelItems(
            exact = listOf(feed("a", "2026-10-25T15:49:00Z")),
            page = listOf(page("a", "4 days ago"), page("b", "1 month ago"), page("c", "1 month ago"), page("d", null)),
            observedAt = now,
        )
        assertEquals(listOf("a", "b", "c", "d"), dated.sortedByDescending { it.publishedAt }.map { it.id.value })
        assertEquals(Instant.parse("2026-09-29T12:00:00Z"), dated[1].publishedAt)
        assertEquals(dated[2].publishedAt, dated[3].publishedAt)
    }

    @Test
    fun `a feed item missing from the page (a Short, a live) is kept with its exact date`() {
        val dated = datedChannelItems(
            exact = listOf(feed("short", "2026-10-27T09:00:00Z"), feed("a", "2026-10-25T15:49:00Z")),
            page = listOf(page("a", "4 days ago")),
            observedAt = now,
        )
        assertEquals(setOf("short", "a"), dated.map { it.id.value }.toSet())
    }

    @Test
    fun `an age that would put a page item after a newer feed item is held below the one before it`() {
        val dated = datedChannelItems(
            exact = listOf(feed("b", "2026-10-20T10:00:00Z")),
            page = listOf(page("a", "2 weeks ago"), page("b", "9 days ago")),
            observedAt = now,
        )
        assertEquals(listOf("a", "b"), dated.sortedByDescending { it.publishedAt }.map { it.id.value })
    }
}
