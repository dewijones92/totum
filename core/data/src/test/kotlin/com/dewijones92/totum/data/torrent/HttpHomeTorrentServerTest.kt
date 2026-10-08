package com.dewijones92.totum.data.torrent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Credentials are read at REQUEST time, never captured at construction.
 *
 * This is the bug from report 0.1.313 and it hid for a day. Signing in stored both the token and
 * Prowlarr's key, the app logged `token=true prowlarrKey=true`, and every search still failed
 * with 401 twelve seconds later — because the client had been built before sign-in and kept the
 * empty key it was born with. Prowlarr answers a bad key with 401, which reads exactly like the
 * gate refusing the token, so the message pointed at the wrong half of the system.
 */
class HttpHomeTorrentServerTest {

    private val server = MockWebServer()

    @Before fun setUp() = server.start()

    @After fun tearDown() = server.close()

    private fun serverWith(token: () -> String, key: () -> String, lateDeadlineMs: Long = 2_000) =
        HttpHomeTorrentServer(
            client = OkHttpClient(),
            base = server.url("").toString().trimEnd('/'),
            prowlarrApiKey = key,
            token = token,
            lateDeadlineMs = lateDeadlineMs,
        )

    private fun ok(body: String) = MockResponse.Builder().code(200).body(body).build()

    private fun answering(indexers: MockResponse = ok("[]"), search: (indexerId: String?) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.target.startsWith("/prowlarr/api/v1/indexer") -> indexers
                request.target.startsWith("/prowlarr/api/v1/search") -> search(request.url.queryParameter("indexerIds"))
                else -> MockResponse.Builder().code(404).build()
            }
        }
    }

    private fun enqueueEmptyResults() = answering { ok("[]") }

    private fun searchRequest(): RecordedRequest {
        while (true) {
            val request = server.takeRequest()
            if (request.target.startsWith("/prowlarr/api/v1/search")) return request
        }
    }

    private fun result(title: String, seeders: Int, indexer: String) =
        """{"title":"$title","guid":"magnet:?xt=urn:btih:${title.hashCode()}",""" +
            """"seeders":$seeders,"size":1,"indexer":"$indexer"}"""

    private val threeIndexers = ok(
        """[{"id":1,"name":"The Pirate Bay","enable":true},{"id":2,"name":"YTS","enable":true},""" +
            """{"id":3,"name":"1337x","enable":true},{"id":4,"name":"Off","enable":false}]""",
    )

    @Test
    fun `each indexer is searched on its own and the answers are merged`() = runTest {
        val asked = mutableListOf<String?>()
        answering(threeIndexers) { id ->
            synchronized(asked) { asked += id }
            when (id) {
                "1" -> ok("[${result("Big Buck Bunny 1080p", 3, "The Pirate Bay")}]")
                "2" -> ok("[${result("Big Buck Bunny 720p", 9, "YTS")}]")
                else -> ok("[]")
            }
        }

        val found = serverWith(token = { "t" }, key = { "k" }).search("big buck bunny")

        assertEquals(setOf("1", "2", "3"), asked.toSet())
        assertEquals(
            setOf("Big Buck Bunny 1080p", "Big Buck Bunny 720p"),
            (found as TorrentSearchResult.Success).results.map { it.title }.toSet(),
        )
    }

    @Test
    fun `the fast indexers' results come first and a late indexer's are added when they arrive`() = runTest {
        answering(threeIndexers) { id ->
            when (id) {
                "1" -> ok("[${result("Sintel 1080p", 22, "The Pirate Bay")}]")
                "3" -> ok(
                    "[${result("Sintel 720p", 4, "1337x")}]"
                ).newBuilder().headersDelay(1, TimeUnit.SECONDS).build()
                else -> ok("[]")
            }
        }

        val answers = serverWith(
            token = { "t" },
            key = { "k" },
            lateDeadlineMs = 10_000
        ).searchUpdates("sintel").toList()

        val first = answers.first() as TorrentSearchResult.Success
        assertEquals(listOf("Sintel 1080p"), first.results.map { it.title })
        assertTrue("still waiting for ${first.stillWaitingFor}", "1337x" in first.stillWaitingFor)
        val last = answers.last() as TorrentSearchResult.Success
        assertEquals(setOf("Sintel 1080p", "Sintel 720p"), last.results.map { it.title }.toSet())
        assertEquals(emptyList<String>(), last.stillWaitingFor)
    }

    @Test
    fun `an indexer that never answers is given up on at the late deadline`() = runTest {
        answering(threeIndexers) { id ->
            when (id) {
                "1" -> ok("[${result("Sintel", 22, "The Pirate Bay")}]")
                "3" -> ok("[]").newBuilder().headersDelay(30, TimeUnit.SECONDS).build()
                else -> ok("[]")
            }
        }
        val started = System.nanoTime()

        val last = serverWith(
            token = { "t" },
            key = { "k" },
            lateDeadlineMs = 500
        ).searchUpdates("sintel").toList().last()

        assertTrue(
            "finished after ${(System.nanoTime() - started) / 1_000_000}ms",
            (System.nanoTime() - started) < 5_000_000_000
        )
        assertEquals(emptyList<String>(), (last as TorrentSearchResult.Success).stillWaitingFor)
        assertEquals(listOf("Sintel"), last.results.map { it.title })
    }

    @Test
    fun `when every indexer is slow it says so rather than that the server is unreachable`() = runTest {
        answering(threeIndexers) { ok("[]").newBuilder().headersDelay(30, TimeUnit.SECONDS).build() }

        val found = serverWith(token = { "t" }, key = { "k" }, lateDeadlineMs = 300).search("big buck bunny")

        val detail = (found as TorrentSearchResult.Failure).detail
        assertTrue("should say the indexers are slow, was: $detail", detail.contains("slow"))
    }

    @Test
    fun `warming a film asks for its first and last megabyte`() = runTest {
        val ranges = mutableListOf<String?>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(ranges) { ranges += request.headers["Range"] }
                return MockResponse.Builder().code(206).body("x").build()
            }
        }
        val film = PreparedTorrent("abc", "Sintel", listOf(TorrentFile(index = 6, path = "sintel.mkv", sizeBytes = 1)))

        serverWith(token = { "t" }, key = { "k" }).warmVideo(film, film.files.single())

        assertEquals(setOf("bytes=0-1048575", "bytes=-1048576"), ranges.toSet())
        assertTrue(server.takeRequest().target.startsWith("/ts/stream/"))
    }

    @Test
    fun `without an indexer list it searches them all at once`() = runTest {
        answering(indexers = MockResponse.Builder().code(500).build()) { id ->
            if (id == null) ok("[${result("Big Buck Bunny", 3, "The Pirate Bay")}]") else ok("[]")
        }

        val found = serverWith(token = { "t" }, key = { "k" }).search("big buck bunny")

        assertEquals(1, (found as TorrentSearchResult.Success).results.size)
    }

    @Test
    fun `a cancelled search stops waiting at once`() = runTest {
        answering(threeIndexers) { ok("[]").newBuilder().headersDelay(30, TimeUnit.SECONDS).build() }
        val torrents = serverWith(token = { "t" }, key = { "k" }, lateDeadlineMs = 60_000)
        val started = System.nanoTime()

        val outcome = withContext(Dispatchers.Default) { withTimeoutOrNull(300) { torrents.search("big buck bunny") } }

        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(null, outcome)
        assertTrue("gave up after ${tookMs}ms", tookMs < 5_000)
    }

    @Test
    fun `a key stored after construction is the one actually sent`() = runTest {
        var key = ""
        val torrents = serverWith(token = { "tok" }, key = { key })

        // Sign-in happens AFTER the client exists — the ordering on a fresh install.
        key = "the-real-key"
        enqueueEmptyResults()
        torrents.search("peep show")

        assertEquals("the-real-key", searchRequest().headers["X-Api-Key"])
    }

    @Test
    fun `a token stored after construction is the one actually sent`() = runTest {
        var token = ""
        val torrents = serverWith(token = { token }, key = { "k" })

        token = "the-real-token"
        enqueueEmptyResults()
        torrents.search("peep show")

        assertEquals("the-real-token", searchRequest().headers["X-Totum-Token"])
    }

    /** Not signed in is answered without a request at all — there is nothing to ask. */
    @Test
    fun `a blank token does not even reach the network`() = runTest {
        val result = serverWith(token = { "" }, key = { "k" }).search("peep show")

        assertEquals(0, server.requestCount)
        assertTrue(result is TorrentSearchResult.Failure)
    }

    /** 401 says which half is wrong in words, because the number cannot. */
    @Test
    fun `a rejection is reported as something a person can act on`() = runTest {
        answering(indexers = MockResponse.Builder().code(401).build()) { MockResponse.Builder().code(401).build() }

        val result = serverWith(token = { "t" }, key = { "k" }).search("peep show")

        val detail = (result as TorrentSearchResult.Failure).detail
        assertTrue("should tell them to sign in again, was: $detail", detail.contains("sign in again"))
    }

    /** A slow fan-out that times out at the gateway is not the same as a broken server. */
    @Test
    fun `a gateway timeout suggests narrowing the search`() = runTest {
        answering(threeIndexers) { MockResponse.Builder().code(504).build() }

        val result = serverWith(token = { "t" }, key = { "k" }).search("peep show")

        val detail = (result as TorrentSearchResult.Failure).detail
        assertTrue("should mention it took too long, was: $detail", detail.contains("too long"))
    }

    /**
     * A magnet's file list arrives from the SWARM, not from the add, so the first ask is often
     * empty. Report 0.1.317: "prepared … with 0 file(s)", then the same torrent with 89 fourteen
     * seconds later — which to anyone tapping a search result is a season that had nothing in it.
     */
    @Test
    fun `prepare waits for the file list to arrive`() = runTest {
        // add → hash, then an empty file list, then a populated one.
        server.enqueue(MockResponse.Builder().code(200).body("""{"hash":"abc","name":"A season"}""").build())
        server.enqueue(MockResponse.Builder().code(200).body("""{"file_stats":[]}""").build())
        server.enqueue(
            MockResponse.Builder().code(200).body(
                """{"file_stats":[{"id":1,"path":"A season/S01E01.mkv","length":100}]}""",
            ).build(),
        )

        val prepared = serverWith(token = { "t" }, key = { "k" }).prepare("magnet:?xt=urn:btih:abc")

        assertEquals(1, prepared?.files?.size)
    }

    /** A magnet nobody is seeding must fail in seconds, not hang on the tap forever. */
    @Test
    fun `prepare gives up when the metadata never arrives`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("""{"hash":"abc","name":"Nothing"}""").build())
        repeat(HttpHomeTorrentServer.METADATA_ATTEMPTS) {
            server.enqueue(MockResponse.Builder().code(200).body("""{"file_stats":[]}""").build())
        }

        val prepared = serverWith(token = { "t" }, key = { "k" }).prepare("magnet:?xt=urn:btih:abc")

        assertEquals(emptyList<TorrentFile>(), prepared?.files)
    }
}
