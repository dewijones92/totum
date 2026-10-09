package com.dewijones92.totum.domain

import com.dewijones92.totum.common.HttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class FillingTheAlbumTest {

    private fun item(
        album: AlbumRef? = null,
        kind: MediaContentKind = MediaContentKind.STANDARD,
        channel: String? = null,
    ) =
        MediaItem(
            id = MediaItemId("Nn_CcTBataE"),
            sourceId = SourceId("s"),
            title = "Sevillana",
            publishedAt = null,
            duration = null,
            contentKind = kind,
            album = album,
            sourceUrl = channel?.let { HttpUrl.of("https://www.youtube.com/channel/$it") },
        )

    private val fromTheAlbumPage =
        item(AlbumRef("MPREb_ky8xEro8eK9", "Guitar Recital"), MediaContentKind.MUSIC, "UCJkuPtgOZnrjZkSb6JPEwoA")

    @Test
    fun `a stored copy learns its album, its artist and that it is a song`() {
        val filled = item().fillingSilenceFrom(fromTheAlbumPage)

        assertEquals(AlbumRef("MPREb_ky8xEro8eK9", "Guitar Recital"), filled.album)
        assertEquals(MediaContentKind.MUSIC, filled.contentKind)
        assertEquals("https://www.youtube.com/channel/UCJkuPtgOZnrjZkSb6JPEwoA", filled.sourceUrl?.value)
    }

    @Test
    fun `what a copy already says is kept`() {
        val known = item(AlbumRef("MPREb_other", "Live in Madrid"), MediaContentKind.MUSIC, "UCother")

        assertEquals(known, known.fillingSilenceFrom(fromTheAlbumPage))
    }
}
