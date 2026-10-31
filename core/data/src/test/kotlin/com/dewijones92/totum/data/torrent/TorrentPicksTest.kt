package com.dewijones92.totum.data.torrent

import com.dewijones92.totum.data.search.SearchHit
import com.dewijones92.totum.data.search.TorrentPicks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TorrentPicksTest {

    private fun hit(
        title: String,
        seeders: Int,
        resolution: String? = "1080p",
        trusted: Boolean = false,
        flagged: Boolean = false,
    ) = SearchHit.Torrent(
        title = title,
        subtitle = null,
        artworkUrl = null,
        link = TorrentLink.Magnet("magnet:?xt=urn:btih:$title"),
        seeders = seeders,
        sizeBytes = 1,
        indexer = "x",
        flagged = flagged,
        resolution = resolution,
        trusted = trusted,
    )

    @Test
    fun `the best copy is a trusted 1080p one over a busier untrusted or bigger one`() {
        val hits = listOf(
            hit("cam", 900, flagged = true),
            hit("untrusted 1080p", 300),
            hit("trusted 4K", 200, resolution = "4K", trusted = true),
            hit("trusted 1080p", 60, trusted = true),
        )
        assertEquals("trusted 1080p", TorrentPicks.best(hits)?.title)
    }

    @Test
    fun `nothing flagged or unseeded is ever the best`() {
        assertNull(TorrentPicks.best(listOf(hit("cam", 900, flagged = true), hit("dead", 0))))
    }

    @Test
    fun `the filter keeps one resolution and, if asked, only trusted uploaders`() {
        val hits = listOf(hit("a", 5), hit("b", 5, resolution = "720p"), hit("c", 5, trusted = true))
        assertEquals(listOf("a", "c"), TorrentPicks.filtered(hits, "1080p", trustedOnly = false).map { it.title })
        assertEquals(listOf("c"), TorrentPicks.filtered(hits, null, trustedOnly = true).map { it.title })
    }
}
