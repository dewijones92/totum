package com.dewijones92.totum.data.torrent

import com.dewijones92.totum.data.search.SearchHit
import com.dewijones92.totum.data.search.SearchOutcome
import com.dewijones92.totum.data.search.SearchQuery
import com.dewijones92.totum.data.search.TorrentSearchSource
import com.dewijones92.totum.data.torrent.fake.FakeHomeTorrentServer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TenBitHevcComesFirstTest {

    private val server = FakeHomeTorrentServer()

    private fun result(title: String, seeders: Int) =
        TorrentResult(title, TorrentLink.Magnet("magnet:?xt=urn:btih:$title"), seeders, 1_000_000_000, indexer = "TPB")

    private suspend fun order(prefers: Boolean = true): List<String> {
        val source = TorrentSearchSource(server, prefersTenBitHevc = { prefers })
        return (source.search(SearchQuery("x"), limit = 10, after = null) as SearchOutcome.Success)
            .page.items.filterIsInstance<SearchHit.Torrent>().map { it.title }
    }

    @Test
    fun `among equally healthy copies the 10-bit x265 one comes first`() = runTest {
        server.results = listOf(
            result("Shrek 2001 1080p BluRay x264", 60),
            result("Shrek 2001 1080p BluRay x265 10bit AAC 5.1-Tigole", 25),
        )
        assertEquals("Shrek 2001 1080p BluRay x265 10bit AAC 5.1-Tigole", order().first())
    }

    @Test
    fun `a dying 10-bit copy never beats a healthy one`() = runTest {
        server.results = listOf(
            result("Zog 2018 1080p WEB x264", 30),
            result("Zog 2018 1080p WEB HEVC 10-bit", 2),
        )
        assertEquals("Zog 2018 1080p WEB x264", order().first())
    }

    @Test
    fun `a phone that cannot decode it keeps the plain order`() = runTest {
        server.results = listOf(
            result("Shrek 2001 1080p BluRay x264", 60),
            result("Shrek 2001 1080p BluRay x265 10bit", 25),
        )
        assertEquals("Shrek 2001 1080p BluRay x264", order(prefers = false).first())
    }

    @Test
    fun `10-bit HEVC is recognised however the name spells it`() {
        listOf(
            "Film 2020 1080p x265 10bit",
            "Film.2020.2160p.HEVC.10-bit",
            "Film 2020 [1080p] [HEVC Main10]",
            "Film 2020 1080p Hi10 x265",
            "Film 2020 1080p h265 10 bit",
        ).forEach { assertTrue(it, ReleaseQuality.isTenBitHevc(it)) }
        listOf("Film 2020 1080p x264 10bit", "Film 2020 1080p x265", "Film 10 2020 x265").forEach {
            assertFalse(it, ReleaseQuality.isTenBitHevc(it))
        }
    }

    @Test
    fun `the quality chip says 10-bit`() {
        assertEquals("1080p · BluRay · HEVC · 10-bit", ReleaseQuality.qualityOf("Shrek 2001 1080p BluRay x265 10bit"))
    }
}
