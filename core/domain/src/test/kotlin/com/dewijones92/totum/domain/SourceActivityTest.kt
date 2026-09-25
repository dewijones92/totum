package com.dewijones92.totum.domain

import com.dewijones92.totum.common.HttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class SourceActivityTest {

    private val show = MediaSource.PodcastFeed(
        SourceId("https://feeds.example.com/show.rss"),
        "The Show",
        HttpUrl.of("https://feeds.example.com/show.rss"),
    )
    private val channel = MediaSource.VideoChannel(
        SourceId("https://www.youtube.com/channel/UCaaaaaaaaaaaaaaaaaaaaaa"),
        "A Channel",
        HttpUrl.of("https://www.youtube.com/channel/UCaaaaaaaaaaaaaaaaaaaaaa"),
    )
    private val quiet = MediaSource.VideoChannel(
        SourceId("https://www.youtube.com/channel/UCqqqqqqqqqqqqqqqqqqqqqq"),
        "quiet channel",
        HttpUrl.of("https://www.youtube.com/channel/UCqqqqqqqqqqqqqqqqqqqqqq"),
    )

    private fun item(id: String, sourceId: String, at: String?, sourceUrl: String? = null) = MediaItem(
        id = MediaItemId(id),
        sourceId = SourceId(sourceId),
        title = id,
        publishedAt = at?.let(Instant::parse),
        duration = null,
        sourceUrl = sourceUrl?.let(HttpUrl::of),
    )

    @Test
    fun `sources of both pillars sort by their newest upload, newest first`() {
        val items = listOf(
            item("ep-old", show.id.value, "2026-09-01T00:00:00Z"),
            item("ep-new", show.id.value, "2026-09-20T00:00:00Z"),
            item(
                "vid",
                "ytfeed:SUBSCRIPTIONS",
                "2026-09-22T00:00:00Z",
                sourceUrl = "https://www.youtube.com/channel/UCaaaaaaaaaaaaaaaaaaaaaa/videos",
            ),
        )

        val ranked = latestUploadFirst(listOf(show, quiet, channel), items)

        assertEquals(listOf("A Channel", "The Show", "quiet channel"), ranked.map { it.source.title })
        assertEquals("vid", ranked[0].latest?.id?.value)
        assertEquals("ep-new", ranked[1].latest?.id?.value)
        assertNull(ranked[2].latest)
    }

    @Test
    fun `a channel matches its videos by channel id, not by the listing they arrived in`() {
        val fromFeed =
            item(
                "v",
                "ytfeed:SUBSCRIPTIONS",
                "2026-09-20T00:00:00Z",
                "https://www.youtube.com/channel/UCaaaaaaaaaaaaaaaaaaaaaa"
            )

        val ranked = latestUploadFirst(listOf(channel, quiet, show), listOf(fromFeed))

        assertEquals("v", ranked.single { it.source == channel }.latest?.id?.value)
        assertNull(ranked.single { it.source == quiet }.latest)
        assertNull(ranked.single { it.source == show }.latest)
    }

    @Test
    fun `an item with no date says nothing about how recent its source is`() {
        val ranked = latestUploadFirst(listOf(show), listOf(item("undated", show.id.value, null)))

        assertNull(ranked.single().latest)
    }

    @Test
    fun `sources with no uploads fall back to title order and a source listed twice appears once`() {
        val zed = show.copy(id = SourceId("z"), title = "Zed")
        val ranked = latestUploadFirst(listOf(zed, quiet, show, show), emptyList())

        assertEquals(listOf("quiet channel", "The Show", "Zed"), ranked.map { it.source.title })
    }
}
