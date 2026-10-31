package com.dewijones92.totum.data.torrent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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

class ProwlarrSearchTest {

    private val server = MockWebServer()

    @Before fun setUp() = server.start()

    @After fun tearDown() = server.close()

    private fun ok(body: String) = MockResponse.Builder().code(200).body(body).build()

    private fun prowlarr(listingDelaySeconds: Long = 0) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.target.startsWith("/api/v1/indexer") ->
                    ok("[]").newBuilder().headersDelay(listingDelaySeconds, TimeUnit.SECONDS).build()
                else -> ok("[]")
            }
        }
    }

    private fun search(vararg also: NamedSearch, deadlineMs: Long = 10_000) = ProwlarrSearch(
        OkHttpClient(),
        server.url("").toString().trimEnd('/'),
        prowlarrApiKey = { "k" },
        token = { "t" },
        lateDeadlineMs = deadlineMs,
        alsoAsk = also.toList(),
    )

    private fun found(name: String) = NamedSearch(name) {
        TorrentSearchResult.Success(
            listOf(TorrentResult(name, TorrentLink.Magnet("magnet:?xt=urn:btih:$name"), 1, 1, name))
        )
    }

    @Test
    fun `the last answer always carries every source, however close together they finish`() = runTest {
        prowlarr()
        repeat(RACES) { round ->
            val answers = withContext(Dispatchers.Default) { search(found("a"), found("b")).updates("q").toList() }

            val last = answers.last() as TorrentSearchResult.Success
            assertEquals("round $round still waiting", emptyList<String>(), last.stillWaitingFor)
            assertEquals("round $round", setOf("a", "b"), last.results.map { it.title }.toSet())
        }
    }

    @Test
    fun `a source beside Prowlarr answers while the home server is still listing its indexers`() = runTest {
        prowlarr(listingDelaySeconds = 3)
        val started = System.nanoTime()

        val first = withContext(Dispatchers.Default) {
            search(
                found("torrentio")
            ).updates("q").first { (it as? TorrentSearchResult.Success)?.results?.isNotEmpty() == true }
        } as TorrentSearchResult.Success

        val tookMs = (System.nanoTime() - started) / NANOS_PER_MS
        assertTrue("took ${tookMs}ms", tookMs < 1_500)
        assertEquals(listOf("torrentio"), first.results.map { it.title })
        assertTrue("waiting for ${first.stillWaitingFor}", first.stillWaitingFor.isNotEmpty())
    }

    @Test
    fun `a source that throws is a failed source, and the others still answer`() = runTest {
        prowlarr()
        val broken = NamedSearch("broken") { throw IllegalStateException("unreadable") }

        val last = withContext(Dispatchers.Default) { search(broken, found("ok")).updates("q").toList() }.last()

        assertEquals(listOf("ok"), (last as TorrentSearchResult.Success).results.map { it.title })
    }

    @Test
    fun `an audiobook search asks only the indexers that carry audiobooks, for that category`() = runTest {
        val asked = java.util.Collections.synchronizedList(mutableListOf<String>())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.target.startsWith("/api/v1/indexer") -> ok(INDEXERS)
                else -> ok("[]").also { asked += request.target }
            }
        }

        withContext(Dispatchers.Default) {
            search().updates("dune", TorrentCategory.AUDIOBOOKS, also = emptyList()).toList()
        }

        assertEquals(
            listOf("/api/v1/search?query=dune&type=search&categories=3030&indexerIds=11"),
            asked.toList(),
        )
    }

    private companion object {
        const val RACES = 300
        const val NANOS_PER_MS = 1_000_000L
        const val INDEXERS = """[
            {"id": 2, "name": "YTS", "enable": true, "tags": [],
             "capabilities": {"categories": [{"id": 2000, "subCategories": [{"id": 2040}]}]}},
            {"id": 11, "name": "AudioBook Bay", "enable": true, "tags": [],
             "capabilities": {"categories": [{"id": 3000, "subCategories": [{"id": 3030}]}]}}
        ]"""
    }
}
