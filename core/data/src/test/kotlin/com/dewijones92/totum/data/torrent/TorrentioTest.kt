package com.dewijones92.totum.data.torrent

import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class TorrentioTest {

    private val server = MockWebServer()

    @Before fun setUp() = server.start()

    @After fun tearDown() = server.close()

    private fun fixture(name: String) = javaClass.getResource("/torrentio/$name")!!.readText()

    private fun ok(body: String) = MockResponse.Builder().code(200).body(body).build()

    private fun torrentio(): Torrentio {
        val base = server.url("").toString().trimEnd('/')
        return Torrentio(OkHttpClient(), cinemetaBase = base, torrentioBase = base)
    }

    @Test
    fun `a film is found through its catalogue entry and every stream with a hash becomes a magnet`() = runTest {
        val asked = mutableListOf<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.target
                synchronized(asked) { asked += path }
                return when {
                    path.startsWith("/catalog/movie/top/search=") -> ok(fixture("cinemeta-movie.json"))
                    path.startsWith("/catalog/series/top/search=") -> ok(fixture("cinemeta-series.json"))
                    path == "/stream/movie/tt0063350.json" -> ok(fixture("streams-movie.json"))
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }

        val found = torrentio().search("night of the living dead") as TorrentSearchResult.Success

        assertEquals(
            listOf(
                Triple("Night.of.the.Living.Dead.1968.2160p.BluRay.x265", 58, 4_350_000_000L),
                Triple("Night of the Living Dead (1968) 1080p", 7, 820_500_000L),
                Triple("Night of the Living Dead 1968 no counts", 0, 0L),
            ),
            found.results.map { Triple(it.title, it.seeders, it.sizeBytes) },
        )
        assertEquals(listOf("Torrentio · YTS", "Torrentio · 1337x", "Torrentio"), found.results.map { it.indexer })
        assertEquals(
            TorrentLink.Magnet(
                "magnet:?xt=urn:btih:${"a".repeat(40)}&dn=Night.of.the.Living.Dead.1968.2160p.BluRay.x265"
            ),
            found.results.first().link,
        )
        assertEquals(true, asked.any { it.startsWith("/catalog/movie/top/search=night%20of%20the%20living%20dead") })
    }

    @Test
    fun `an episode in the search is the episode asked for, and a loose catalogue match is not used`() = runTest {
        val asked = mutableListOf<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(asked) { asked += request.target }
                return when {
                    request.target.lowercase() == "/catalog/series/top/search=flash%20gordon.json" -> ok(
                        """{"metas":[{"id":"tt0000001","imdb_id":"tt0000001","name":"Flash Gordon"}]}""",
                    )
                    request.target.startsWith("/catalog/movie/") -> ok(
                        """{"metas":[{"id":"tt0000002","imdb_id":"tt0000002","name":"Flash"}]}""",
                    )
                    request.target == "/stream/series/tt0000001:2:3.json" -> ok(
                        """{"streams":[{"title":"Flash Gordon S02E03\n👤 2 💾 300 MB ⚙️ EZTV",""" +
                            """"infoHash":"dddddddddddddddddddddddddddddddddddddddd"}]}""",
                    )
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }

        val found = torrentio().search("Flash Gordon S02E03") as TorrentSearchResult.Success

        assertEquals(listOf("Flash Gordon S02E03"), found.results.map { it.title })
        assertEquals(false, asked.any { it.startsWith("/stream/movie/") })
    }

    @Test
    fun `a year in the search picks the film from that year and is not part of the title`() = runTest {
        val asked = mutableListOf<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(asked) { asked += request.target }
                return when {
                    request.target == "/catalog/movie/top/search=night%20of%20the%20living%20dead.json" ->
                        ok(fixture("cinemeta-movie.json"))
                    request.target.startsWith("/catalog/series/") -> ok(fixture("cinemeta-series.json"))
                    request.target.startsWith("/stream/movie/") -> ok(fixture("streams-movie.json"))
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }

        val found = torrentio().search("night of the living dead 1990") as TorrentSearchResult.Success

        assertEquals(3, found.results.size)
        assertEquals(listOf("/stream/movie/tt0100258.json"), asked.filter { it.startsWith("/stream/") })
    }

    @Test
    fun `one catalogue failing or answering nonsense does not hide the other`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.target.startsWith("/catalog/movie/") -> ok(fixture("cinemeta-movie.json"))
                request.target.startsWith("/catalog/series/") ->
                    ok("""{"metas":[{"imdb_id":{"not":"a string"},"name":["nor","this"]}]}""")
                request.target == "/stream/movie/tt0063350.json" -> ok(fixture("streams-movie.json"))
                else -> MockResponse.Builder().code(500).build()
            }
        }

        val found = torrentio().search("night of the living dead")

        assertEquals(3, (found as TorrentSearchResult.Success).results.size)
    }

    @Test
    fun `a number in a title that is not a year still finds that title`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.target.startsWith("/catalog/movie/") -> ok(
                    """{"metas":[{"imdb_id":"tt0000010","name":"The Space Film","releaseInfo":"1959"},""" +
                        """{"imdb_id":"tt0000011","name":"The Space Film 2061","releaseInfo":"2016"}]}""",
                )
                request.target.startsWith("/catalog/series/") -> ok(fixture("cinemeta-series.json"))
                request.target == "/stream/movie/tt0000011.json" -> ok(fixture("streams-movie.json"))
                else -> MockResponse.Builder().code(404).build()
            }
        }

        val found = torrentio().search("the space film 2061")

        assertEquals(3, (found as TorrentSearchResult.Success).results.size)
    }

    @Test
    fun `a search that is only an episode asks no catalogue`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = ok(fixture("cinemeta-movie.json"))
        }

        val found = torrentio().search("S01E02")

        assertEquals(TorrentSearchResult.Success(emptyList()), found)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `nothing in the catalogue is an empty answer, not a failure`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = ok(fixture("cinemeta-series.json"))
        }

        val found = torrentio().search("zzzq")

        assertEquals(TorrentSearchResult.Success(emptyList()), found)
    }
}
