package com.dewijones92.totum.data.torrent

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

class ApibayTest {

    private val server = MockWebServer()

    @Before fun setUp() = server.start()

    @After fun tearDown() = server.close()

    private fun apibay() = Apibay(OkHttpClient(), base = server.url("").toString().trimEnd('/'))

    @Test
    fun `each result carries its uploader's standing and becomes a magnet`() = runTest {
        server.enqueue(
            MockResponse.Builder().code(200).body(
                """[{"id":"1","name":"Night of the Living Dead 1968 1080p","info_hash":"${"A".repeat(40)}",""" +
                    """"leechers":"15","seeders":"104","size":"1600000000","num_files":"5","username":"someone",""" +
                    """"added":"1600000000","status":"vip","category":"207","imdb":""},""" +
                    """{"id":"2","name":"Night of the Living Dead 1968 720p","info_hash":"${"B".repeat(40)}",""" +
                    """"leechers":"1","seeders":"3","size":"700000000","num_files":"1","username":"another",""" +
                    """"added":"1600000000","status":"member","category":"207","imdb":""}]""",
            ).build(),
        )

        val found = apibay().search("night of the living dead 1968") as TorrentSearchResult.Success

        assertEquals(listOf(Uploader.VIP, Uploader.MEMBER), found.results.map { it.uploader })
        val first = found.results.first()
        assertEquals("someone", first.uploaderName)
        assertEquals(15, first.leechers)
        assertEquals(5, first.fileCount)
        assertEquals(Instant.ofEpochSecond(1_600_000_000), first.publishedAt)
        assertEquals("The Pirate Bay", first.indexer)
        assertTrue(first.link.toString(), (first.link as TorrentLink.Magnet).uri.contains("btih:${"A".repeat(40)}"))
        assertEquals("/q.php?q=night%20of%20the%20living%20dead%201968", server.takeRequest().target)
    }

    @Test
    fun `an audiobook search asks for The Pirate Bay's audiobook category`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("[]").build())

        apibay().search("dune", TorrentCategory.AUDIOBOOKS)

        assertEquals("/q.php?q=dune&cat=102", server.takeRequest().target)
    }

    @Test
    fun `a result in The Pirate Bay's audiobook category says so`() = runTest {
        server.enqueue(
            MockResponse.Builder().code(200).body(
                """[{"id":"1","name":"Dune - Frank Herbert","info_hash":"${"A".repeat(40)}","leechers":"1",""" +
                    """"seeders":"9","size":"1","num_files":"40","username":"x","added":"1","status":"member",""" +
                    """"category":"102","imdb":""}]""",
            ).build(),
        )

        val found = apibay().search("dune") as TorrentSearchResult.Success

        assertEquals(listOf(true), found.results.map { it.audiobook })
    }

    @Test
    fun `a result keeps its IMDb id, so it can have a poster`() = runTest {
        server.enqueue(
            MockResponse.Builder().code(200).body(
                """[{"id":"1","name":"Dune 2021 1080p","info_hash":"${"A".repeat(40)}","leechers":"1",""" +
                    """"seeders":"9","size":"1","num_files":"1","username":"x","added":"1","status":"member",""" +
                    """"category":"207","imdb":"tt1160419"},""" +
                    """{"id":"2","name":"Dune 720p","info_hash":"${"B".repeat(40)}","leechers":"1",""" +
                    """"seeders":"2","size":"1","num_files":"1","username":"x","added":"1","status":"member",""" +
                    """"category":"207","imdb":""}]""",
            ).build(),
        )

        val found = apibay().search("dune") as TorrentSearchResult.Success

        assertEquals(listOf("tt1160419", null), found.results.map { it.imdbId })
    }

    @Test
    fun `its no-results row is not a result`() = runTest {
        server.enqueue(
            MockResponse.Builder().code(200).body(
                """[{"id":"0","name":"No results returned","info_hash":"${"0".repeat(40)}","leechers":"0",""" +
                    """"seeders":"0","num_files":"0","size":"0","username":"","added":"0","status":"member"}]""",
            ).build(),
        )

        assertEquals(TorrentSearchResult.Success(emptyList()), apibay().search("nothing at all"))
    }

    @Test
    fun `an error answer is a failure, not an empty list`() = runTest {
        server.enqueue(MockResponse.Builder().code(503).build())

        assertTrue(apibay().search("x") is TorrentSearchResult.Failure)
    }

    @Test
    fun `staff standings count as trusted`() {
        assertEquals(Uploader.TRUSTED, Apibay.standingOf("moderator"))
        assertEquals(Uploader.UNKNOWN, Apibay.standingOf(null))
    }
}
