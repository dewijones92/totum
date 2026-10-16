package com.dewijones92.totum.domain

import com.dewijones92.totum.common.HttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

class ItemFiltersTest {

    private val now = Instant.parse("2026-10-16T12:00:00Z")

    private fun item(
        id: String,
        length: Duration?,
        age: Duration?,
        music: Boolean = false,
        video: Boolean = true,
    ) = MediaItem(
        id = MediaItemId(id),
        sourceId = SourceId("s"),
        title = id,
        publishedAt = age?.let { now.minusSeconds(it.inWholeSeconds) },
        duration = length,
        mediaUrl = HttpUrl.of(if (video) "https://www.youtube.com/watch?v=$id" else "https://feed.test/$id.mp3"),
        contentKind = if (music) MediaContentKind.MUSIC else MediaContentKind.STANDARD,
    )

    @Test
    fun `no filter lets everything through`() {
        assertTrue(ItemFilters().accepts(item("a", null, null), downloaded = false, now))
        assertFalse(ItemFilters().isActive)
    }

    @Test
    fun `length bands are half-open and an unknown length is not shown under one`() {
        val under10 = ItemFilters(length = LengthFilter.UNDER_10)
        assertTrue(under10.accepts(item("a", 9.minutes, null), false, now))
        assertFalse(under10.accepts(item("b", 10.minutes, null), false, now))
        assertFalse(under10.accepts(item("c", null, null), false, now))
        assertTrue(ItemFilters(length = LengthFilter.OVER_60).accepts(item("d", 61.minutes, null), false, now))
    }

    @Test
    fun `downloaded, kind and age filters combine`() {
        val filters = ItemFilters(
            downloaded = DownloadedFilter.DOWNLOADED,
            kind = KindFilter.TALK,
            within = PublishedWithin.WEEK,
        )
        assertTrue(filters.accepts(item("talk", 20.minutes, 2.days), downloaded = true, now))
        assertFalse("not downloaded", filters.accepts(item("talk", 20.minutes, 2.days), downloaded = false, now))
        assertFalse("music", filters.accepts(item("song", 3.minutes, 2.days, music = true), downloaded = true, now))
        assertFalse("too old", filters.accepts(item("old", 20.minutes, 9.days), downloaded = true, now))
        assertEquals(3, filters.activeCount)
    }

    @Test
    fun `video means a non-music video and leaves podcasts out`() {
        val videos = ItemFilters(kind = KindFilter.VIDEO)
        assertTrue(videos.accepts(item("v", 5.minutes, null), false, now))
        assertFalse(videos.accepts(item("p", 5.minutes, null, video = false), false, now))
        assertFalse(videos.accepts(item("s", 5.minutes, null, music = true), false, now))
    }
}
