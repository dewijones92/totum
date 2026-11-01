package com.dewijones92.totum.ui.common

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.domain.SourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentsAreShownAsTorrentsTest {
    private val film = MediaItem(
        id = MediaItemId("torrent:2db6546a:1"),
        sourceId = SourceId("torrent"),
        title = "Toy Story 5",
        publishedAt = null,
        duration = null,
        author = "Toy.Story.5.2026.1080p.x265",
        mediaUrl = HttpUrl.of("https://totum.example/ts/stream/toy.mkv?link=2db6546a&index=1&play"),
    )

    @Test
    fun `a torrent film's maker line says torrent, not podcast`() {
        val maker = mediaItemFacts(film, MediaKind.PODCAST).first()
        assertTrue(maker, maker.startsWith(FactEmoji.TORRENT))
    }

    @Test
    fun `a torrent is shown as a torrent wherever it is described`() {
        assertEquals(Shown.TORRENT, shownAs(film, MediaKind.PODCAST))
        assertEquals(Shown.TORRENT, shownAs(film.id, MediaKind.PODCAST, MediaContentKind.STANDARD))
        assertEquals(
            Shown.PODCAST,
            shownAs(film.copy(id = MediaItemId("ep1"), sourceId = SourceId("feed")), MediaKind.PODCAST)
        )
        assertEquals(Shown.MUSIC, shownAs(film.id, MediaKind.VIDEO, MediaContentKind.MUSIC))
    }
}
