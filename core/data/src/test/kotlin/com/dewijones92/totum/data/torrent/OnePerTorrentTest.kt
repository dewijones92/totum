package com.dewijones92.totum.data.torrent

import org.junit.Assert.assertEquals
import org.junit.Test

class OnePerTorrentTest {

    private val hash = "A".repeat(40)

    private fun result(title: String, seeders: Int, indexer: String) =
        TorrentResult(title, TorrentLink.Magnet("magnet:?xt=urn:btih:$hash"), seeders, 1, indexer)

    @Test
    fun `the same torrent from two sites keeps the whole title, not a site's shortened one`() {
        val merged = onePerTorrent(
            listOf(
                result("Pride.and.Prejudice.2005.1080p.BluRay.x264 ...", 30, "Knaben"),
                result("Pride.and.Prejudice.2005.1080p.BluRay.x264.DTS-GROUP", 12, "The Pirate Bay"),
            ),
        ).single()

        assertEquals("Pride.and.Prejudice.2005.1080p.BluRay.x264.DTS-GROUP", merged.title)
        assertEquals(30, merged.seeders)
    }
}
