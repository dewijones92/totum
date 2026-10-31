package com.dewijones92.totum.data.search

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.torrent.TorrentCategory
import com.dewijones92.totum.data.torrent.TorrentLink
import org.junit.Assert.assertEquals
import org.junit.Test

class AudiobookPicksTest {

    private fun book(title: String) = SearchHit.Book(
        title = title,
        subtitle = null,
        artworkUrl = null,
        feedUrl = HttpUrl.of("https://archive.org/metadata/${title.replace(' ', '_')}"),
        identifier = title.replace(' ', '_'),
        librivox = true,
    )

    private fun torrent(title: String, seeders: Int) = SearchHit.Torrent(
        title = title,
        subtitle = null,
        artworkUrl = null,
        link = TorrentLink.Magnet("magnet:?xt=urn:btih:$title"),
        seeders = seeders,
        sizeBytes = 1,
        indexer = "x",
        category = TorrentCategory.AUDIOBOOKS,
    )

    @Test
    fun `one list, best title match first, free before torrents when equally good, then most seeded`() {
        val merged = AudiobookPicks.merged(
            "pride and prejudice",
            books = listOf(book("Sense and Sensibility"), book("Pride and Prejudice")),
            torrents = listOf(
                torrent("Pride and Prejudice - Jane Austen [MP3]", 3),
                torrent("Pride and Prejudice BBC", 40)
            ),
        )

        assertEquals(
            listOf(
                "Pride and Prejudice",
                "Pride and Prejudice BBC",
                "Pride and Prejudice - Jane Austen [MP3]",
                "Sense and Sensibility"
            ),
            merged.map { it.title },
        )
    }

    @Test
    fun `equally good free books and torrents take turns, so both show on the first page`() {
        val merged = AudiobookPicks.merged(
            "pride and prejudice",
            books = (1..3).map { book("Pride and Prejudice version $it") },
            torrents = listOf(torrent("Pride and Prejudice A", 5), torrent("Pride and Prejudice B", 50)),
        )

        assertEquals(
            listOf(
                "Pride and Prejudice version 1",
                "Pride and Prejudice B",
                "Pride and Prejudice version 2",
                "Pride and Prejudice A",
                "Pride and Prejudice version 3",
            ),
            merged.map { it.title },
        )
    }
}
