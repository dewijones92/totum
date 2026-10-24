package com.dewijones92.totum.data.torrent

import com.dewijones92.totum.data.search.SearchHit
import com.dewijones92.totum.data.search.SearchOutcome
import com.dewijones92.totum.data.search.SearchQuery
import com.dewijones92.totum.data.search.SearchSection
import com.dewijones92.totum.data.search.TorrentSearchSource
import com.dewijones92.totum.data.search.asSection
import com.dewijones92.totum.data.torrent.fake.FakeHomeTorrentServer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Torrents arriving through the same seam as podcasts and videos.
 *
 * The point of this class is that the feature reaches the UI as one more `SearchHit` — so what
 * is worth testing is the ordering and the subtitle, because with twenty copies of one film
 * those two things ARE the user's decision.
 */
class TorrentSearchSourceTest {

    private val server = FakeHomeTorrentServer()
    private val source = TorrentSearchSource(server)

    private fun result(title: String, seeders: Int, size: Long = 1_000_000_000) =
        TorrentResult(title, TorrentLink.Magnet("magnet:?xt=urn:btih:$title"), seeders, size, indexer = "TPB")

    @Test
    fun `a slow home server reaches the section as slow, not as unreachable`() = runTest {
        server.failure = "the home server's indexers are slow to answer"
        server.slow = true

        val section = source.search(SearchQuery("x"), limit = 10, after = null).asSection { it.page.items }

        assertTrue((section as SearchSection.Failed).slow)
    }

    @Test
    fun `results come back best-seeded first`() = runTest {
        server.results = listOf(result("thin", 2), result("healthy", 40), result("middling", 9))

        val outcome = source.search(SearchQuery("x"), limit = 10, after = null)

        val titles = (outcome as SearchOutcome.Success).page.items.map { it.title }
        assertEquals(listOf("healthy", "middling", "thin"), titles)
    }

    /**
     * Seeders and size are the subtitle because they are what the choice is made on. A result
     * with no seeders will never play, however good its name looks.
     */
    @Test
    fun `the subtitle carries seeders and size`() = runTest {
        server.results = listOf(result("a film", seeders = 24, size = 1_740_000_000))

        val outcome = source.search(SearchQuery("x"), limit = 10, after = null)

        val hit = (outcome as SearchOutcome.Success).page.items.single()
        assertTrue("got: ${hit.subtitle}", hit.subtitle!!.contains("24 seeders"))
        assertTrue("got: ${hit.subtitle}", hit.subtitle!!.contains("1.74 GB"))
    }

    @Test
    fun `a torrent hit carries its magnet for the server to add`() = runTest {
        server.results = listOf(result("a film", 5))

        val outcome = source.search(SearchQuery("x"), limit = 10, after = null)

        val hit = (outcome as SearchOutcome.Success).page.items.single() as SearchHit.Torrent
        assertTrue((hit.link as TorrentLink.Magnet).uri.startsWith("magnet:"))
    }

    /**
     * The home server is only reachable at home or over wg-home, so being unable to reach it is
     * the ordinary case of being elsewhere. It must surface as a failure the UI can explain, not
     * as an empty result that looks like "nothing found".
     */
    @Test
    fun `an unreachable server is a failure, not an empty list`() = runTest {
        server.failure = "could not reach the home server"

        val outcome = source.search(SearchQuery("x"), limit = 10, after = null)

        assertTrue("expected Failure, got $outcome", outcome is SearchOutcome.Failure)
    }

    /** Prowlarr answers in one shot, so a second page would repeat the first. */
    @Test
    fun `there is no second page to fetch`() = runTest {
        server.results = listOf(result("a film", 5))

        val outcome = source.search(SearchQuery("x"), limit = 10, after = com.dewijones92.totum.common.PageToken("2"))

        assertEquals(emptyList<SearchHit>(), (outcome as SearchOutcome.Success).page.items)
    }
}

/**
 * Parsing Prowlarr's real response shape.
 *
 * Separate from the adapter tests because this is where a live API surprised me: `magnetUrl` is
 * NOT a magnet, it is a download-proxy URL carrying the API key, and `guid` is where the magnet
 * actually lives. Captured from the running service on 2026-08-01 rather than from documentation.
 */
class ProwlarrShapeTest {

    private val realShape = """
        [{
          "title": "Dragnet  (Crime Drama 1954)  Jack Webb  720p",
          "seeders": 4,
          "size": 1011075711,
          "indexer": "The Pirate Bay",
          "magnetUrl": "http://prowlarr:9696/1/download?apikey=SECRET&link=abc",
          "guid": "magnet:?xt=urn:btih:3D20C3419D1C4012E2AD431480EFD530DAF8EFCC"
        }]
    """.trimIndent()

    @Test
    fun `the magnet is taken from the field that IS one, not the one named like one`() {
        val parsed = parseProwlarr(realShape)!!

        val result = parsed.single()
        assertTrue(
            "must not hand the streaming server a download-proxy URL: ${result.link}",
            (result.link as? TorrentLink.Magnet)?.uri?.startsWith("magnet:") == true,
        )
        assertEquals(4, result.seeders)
        assertEquals(1_011_075_711L, result.sizeBytes)
        assertEquals("The Pirate Bay", result.indexer)
    }

    /**
     * The crash this prevents was real, on a device, mid-search (2026-08-01). Both services
     * behind the home gate answer an unknown path with their own web UI and HTTP 200, so a
     * misrouted request arrives as HTML — and parsing that as JSON threw straight up through
     * the search coroutine and killed the app. A search that cannot be read is a failed search.
     */
    @Test
    fun `a result whose page address ends in its info hash still gets a magnet`() {
        val hash = "B84153225C3112B9B386E30846FD1DA1F1E2C3A4"
        val yts = """[{"title":"Night of the Living Dead (1968) 1080p",""" +
            """"guid":"https://yts.gg/torrent/download/$hash",""" +
            """"magnetUrl":"http://prowlarr:9696/2/download?apikey=k","seeders":13,"size":1,"indexer":"YTS"},""" +
            """{"title":"No hash here","guid":"https://example.test/page/42","seeders":5,"size":1}]"""

        val parsed = parseProwlarr(yts)!!

        assertEquals(listOf("Night of the Living Dead (1968) 1080p"), parsed.map { it.title })
        assertEquals(
            TorrentLink.Magnet("magnet:?xt=urn:btih:$hash&dn=Night%20of%20the%20Living%20Dead%20%281968%29%201080p"),
            parsed.single().link,
        )
    }

    @Test
    fun `a body that is not JSON is null, never an exception`() {
        assertNull(parseProwlarr("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"/>"))
        assertNull(parseProwlarr(""))
    }

    /** Valid JSON of the wrong SHAPE is equally not a search response. */
    @Test
    fun `a JSON object rather than an array is null`() {
        assertNull(parseProwlarr("""{"error":"unauthorised"}"""))
    }

    @Test
    fun `a result with no magnet anywhere is dropped rather than half-built`() {
        val noMagnet = """[{"title":"x","seeders":1,"size":1,"magnetUrl":"http://prowlarr/1/download?apikey=S"}]"""

        assertEquals(emptyList<TorrentResult>(), parseProwlarr(noMagnet))
    }

    @Test
    fun `a 1337x result with only the indexer's download link is kept, without the key in it`() {
        val leet = """[{"title":"Night of the Living Dead (1968) [BluRay] [1080p]",""" +
            """"guid":"https://1337x.to/torrent/3714507/Night-of-the-Living-Dead-1968/",""" +
            """"downloadUrl":"http://prowlarr:9696/4/download?apikey=SECRET&link=bTRzVk5Y&file=Night",""" +
            """"seeders":120,"size":1623497637,"indexer":"1337x"}]"""

        val parsed = parseProwlarr(leet)!!.single()

        assertEquals(TorrentLink.ViaIndexer("/4/download?link=bTRzVk5Y&file=Night"), parsed.link)
        assertEquals(120, parsed.seeders)
    }
}
