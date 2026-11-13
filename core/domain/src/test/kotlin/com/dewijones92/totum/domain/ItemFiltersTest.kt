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

    @Test
    fun `video means anything with a picture, so a Jellyfin film and a video torrent count`() {
        val videos = ItemFilters(kind = KindFilter.VIDEO)
        val jellyfinFilm = item("jellyfin:abc", 90.minutes, null, video = false).copy(
            mediaUrl = HttpUrl.of("https://pi.test/jellyfin/stream/abc"),
        )
        val torrentFilm = item("torrent:def", 90.minutes, null, video = false).copy(
            mediaUrl = HttpUrl.of("https://pi.test/torrent/stream/def.mkv"),
        )
        val torrentAudio = item("torrent:ghi", 60.minutes, null, video = false).copy(
            mediaUrl = HttpUrl.of("https://pi.test/torrent/stream/ghi.mp3"),
        )

        assertTrue("Jellyfin film", videos.accepts(jellyfinFilm, false, now))
        assertTrue("video torrent", videos.accepts(torrentFilm, false, now))
        assertFalse("sound torrent", videos.accepts(torrentAudio, false, now))
    }

    @Test
    fun `plays offline accepts by the extra answer and not by the download answer`() {
        val offline = ItemFilters(downloaded = DownloadedFilter.PLAYS_OFFLINE)
        val a = item("a", 5.minutes, null)

        assertTrue(offline.accepts(a, downloaded = false, now, playsOffline = true))
        assertFalse(offline.accepts(a, downloaded = true, now, playsOffline = false))
        assertTrue(offline.isActive)
        assertEquals(1, offline.activeCount)
    }

    @Test
    fun `plays offline defaults to downloaded wherever the caller has no better answer`() {
        val offline = ItemFilters(downloaded = DownloadedFilter.PLAYS_OFFLINE)
        val a = item("a", 5.minutes, null)

        assertTrue(offline.accepts(a, downloaded = true, now))
        assertFalse(offline.accepts(a, downloaded = false, now))
    }

    @Test
    fun `downloaded and not downloaded ignore the plays offline answer`() {
        val a = item("a", 5.minutes, null)

        assertTrue(ItemFilters(downloaded = DownloadedFilter.DOWNLOADED).accepts(a, true, now, playsOffline = false))
        assertTrue(
            ItemFilters(downloaded = DownloadedFilter.NOT_DOWNLOADED).accepts(a, false, now, playsOffline = true)
        )
        assertFalse(
            ItemFilters(downloaded = DownloadedFilter.NOT_DOWNLOADED).accepts(a, true, now, playsOffline = true)
        )
    }

    @Test
    fun `the live filter keeps what is live now, by the registry's answer when there is one`() {
        val live = ItemFilters(kind = KindFilter.LIVE)
        val standard = item("a", 5.minutes, null)
        val streaming = standard.copy(contentKind = MediaContentKind.LIVE)

        assertFalse(live.accepts(standard, false, now))
        assertTrue(live.accepts(streaming, false, now))
        assertTrue(
            "the registry says it is live though the item was saved standard",
            live.accepts(standard, false, now, liveNow = true)
        )
        assertFalse("the registry says it ended", live.accepts(streaming, false, now, liveNow = false))
    }
}
