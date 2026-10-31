package com.dewijones92.totum.ui.common

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.AlbumRef
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.innertube.music.MusicAlbum
import com.dewijones92.totum.innertube.music.MusicReleaseKind
import com.dewijones92.totum.innertube.music.fake.FakeYouTubeMusicCatalogue
import com.dewijones92.totum.music.AlbumYears
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.seconds

class SongFactsAndDetailsTest {

    private val song = MediaItem(
        id = MediaItemId("abcdefghijk"),
        sourceId = SourceId("music:radio:x"),
        title = "Entre dos aguas",
        publishedAt = null,
        duration = 345.seconds,
        author = "Paco de Lucía",
        mediaUrl = HttpUrl.of("https://www.youtube.com/watch?v=abcdefghijk"),
        viewsText = "40M plays",
        contentKind = MediaContentKind.MUSIC,
        album = AlbumRef("MPREb_x", "Fuente y caudal"),
    )

    @Test
    fun `a song row says its artist, its album with the year, and its plays`() {
        assertEquals(
            listOf("🎤 Paco de Lucía", "💿 Fuente y caudal · 1973", "👁️ 40M plays"),
            mediaItemFacts(song, MediaKind.VIDEO, albumYear = "1973"),
        )
    }

    @Test
    fun `a video row is unchanged`() {
        val video = song.copy(contentKind = MediaContentKind.STANDARD, album = null)
        assertEquals(listOf("📺 Paco de Lucía", "👁️ 40M plays"), mediaItemFacts(video, MediaKind.VIDEO))
    }

    @Test
    fun `details list everything known about the item`() {
        val details = itemDetails(
            song.copy(publishedAt = Instant.parse("2026-10-14T18:30:00Z")),
            ZoneOffset.UTC,
            now = Instant.parse("2026-10-14T20:30:00Z"),
        )

        assertTrue(details.contains("🎤 Paco de Lucía"))
        assertTrue(details.contains("💿 Fuente y caudal"))
        assertTrue(details.contains("📅 14 October 2026 · 2 hours ago"))
        assertTrue(details.contains("⏱️ 5:45"))
        assertTrue(details.contains("🎞️ Song"))
        assertTrue(details.contains("🆔 abcdefghijk"))
        assertTrue(details.any { it.startsWith("🔗 https://www.youtube.com/watch") })
    }

    @Test
    fun `an album's year is looked up once and remembered`() = runTest {
        val album = MusicAlbum(
            "MPREb_x", "Fuente y caudal", "Paco de Lucía", null, "1973", MusicReleaseKind.ALBUM,
            null, null, null, null, emptyList(),
        )
        val catalogue = FakeYouTubeMusicCatalogue(albumsById = mapOf(album.browseId to album))
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val years = AlbumYears(catalogue, scope, null)

        years.want("MPREb_x")
        years.want("MPREb_x")

        assertEquals("1973", years.yearOf("MPREb_x"))
        scope.cancel()
    }
}
