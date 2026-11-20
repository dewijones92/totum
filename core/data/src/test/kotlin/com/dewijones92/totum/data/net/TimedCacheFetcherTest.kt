package com.dewijones92.totum.data.net

import com.dewijones92.totum.common.HttpUrl
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimedCacheFetcherTest {

    private val cached = HttpUrl.of("https://ibl.api.bbci.co.uk/ibl/v1/programmes/b006mkw3/episodes")
    private val other = HttpUrl.of("https://example.com/feed.xml")

    private var clock = 1_000L
    private val asked = mutableListOf<String>()
    private var answer: FetchResult = FetchResult.Success("body")

    private fun fetcher(ttlMs: Long = 60_000L, maxEntries: Int = 8) = TimedCacheFetcher(
        delegate = { url ->
            asked += url.value
            answer
        },
        label = "test",
        ttlMs = ttlMs,
        maxEntries = maxEntries,
        cacheable = { it.host == "ibl.api.bbci.co.uk" },
        nowMs = { clock },
    )

    @Test
    fun `a second ask inside the time to live is answered from memory`() = runTest {
        val fetcher = fetcher()

        fetcher.fetch(cached)
        clock += 59_000L
        val again = fetcher.fetch(cached)

        assertEquals(FetchResult.Success("body"), again)
        assertEquals(listOf(cached.value), asked)
    }

    @Test
    fun `after the time to live it asks again`() = runTest {
        val fetcher = fetcher()

        fetcher.fetch(cached)
        clock += 60_000L
        fetcher.fetch(cached)

        assertEquals(2, asked.size)
    }

    @Test
    fun `a failure is never kept, so the next ask goes out`() = runTest {
        val fetcher = fetcher()
        answer = FetchResult.Failure("HTTP 503", httpStatus = 503)

        fetcher.fetch(cached)
        answer = FetchResult.Success("recovered")
        val after = fetcher.fetch(cached)

        assertEquals(FetchResult.Success("recovered"), after)
        assertEquals(2, asked.size)
    }

    @Test
    fun `an address that is not cacheable always goes to the network`() = runTest {
        val fetcher = fetcher()

        fetcher.fetch(other)
        fetcher.fetch(other)

        assertEquals(2, asked.size)
    }

    @Test
    fun `the oldest entry is forgotten once the cache is full`() = runTest {
        val fetcher = fetcher(maxEntries = 2)
        val urls = (1..3).map { HttpUrl.of("https://ibl.api.bbci.co.uk/ibl/v1/programmes/p$it") }

        urls.forEach { fetcher.fetch(it) }
        asked.clear()
        fetcher.fetch(urls[2])
        fetcher.fetch(urls[0])

        assertEquals(listOf(urls[0].value), asked)
        assertTrue(asked.none { it == urls[2].value })
    }
}
