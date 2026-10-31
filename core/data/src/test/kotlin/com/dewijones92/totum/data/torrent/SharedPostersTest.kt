package com.dewijones92.totum.data.torrent

import org.junit.Assert.assertEquals
import org.junit.Test

class SharedPostersTest {
    private fun result(title: String, imdbId: String? = null, posterUrl: String? = null) = TorrentResult(
        title = title,
        link = TorrentLink.ViaIndexer("/$title"),
        seeders = 1,
        sizeBytes = 1,
        indexer = "x",
        imdbId = imdbId,
        posterUrl = posterUrl,
    )

    @Test
    fun `a copy of the same film borrows the poster of one that has it`() {
        val shared = withSharedPosters(
            listOf(
                result("Night of the Living Dead (1968) 1080p BRRip x264 -YTS", imdbId = "tt0063350"),
                result("Night of the Living Dead (1968) [BluRay] [1080p] [YTS] [YIFY]"),
                result("Night.of.the.Living.Dead.1990.720p"),
            ),
        )

        assertEquals(
            listOf(POSTER, POSTER, null),
            shared.map { it.artworkUrl },
        )
    }

    @Test
    fun `episodes of one show share its poster, and a name with no year or episode borrows nothing`() {
        val shared = withSharedPosters(
            listOf(
                result("The Office S02E03 720p", posterUrl = "https://x/office.jpg"),
                result("The.Office.S05E01.1080p"),
                result("The Office 1080p"),
            ),
        )

        assertEquals(listOf("https://x/office.jpg", "https://x/office.jpg", null), shared.map { it.artworkUrl })
    }

    private companion object {
        const val POSTER = "https://images.metahub.space/poster/medium/tt0063350/img"
    }
}
