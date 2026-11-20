package com.dewijones92.totum.innertube.search

import com.dewijones92.totum.innertube.auth.AccessToken
import com.dewijones92.totum.innertube.browse.InnerTubeClient
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WholePageTest {

    private val server = MockWebServer()

    @Before fun setUp() = server.start()

    @After fun tearDown() = server.close()

    private fun search(token: AccessToken? = null) = HttpYouTubeSearch(
        InnerTubeClient(OkHttpClient(), searchUrl = server.url("/search").toString()),
        token = { token },
    )

    private fun res(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "fixture $name missing" }
            .bufferedReader().readText()

    private fun ok(body: String) = MockResponse.Builder().code(200).body(body).build()

    @Test
    fun `an anonymous page is returned whole, whatever limit the caller asked for`() = runBlocking {
        server.enqueue(ok(res("search_web_sample.json")))

        val page = (search().searchVideos("tennis", limit = 8) as SearchVideosResult.Success).page

        val whole = SearchResultsParser.videos(res("search_web_sample.json")).size
        assertTrue("the fixture must hold more than the limit: $whole", whole > 8)
        assertEquals("every parsed video must reach the caller", whole, page.items.size)
    }

    @Test
    fun `a signed-in page is returned whole, whatever limit the caller asked for`() = runBlocking {
        server.enqueue(ok(res("channel_streams_web_sample.json")))

        val page = (search(AccessToken("secret")).searchVideos("tennis", limit = 1) as SearchVideosResult.Success).page

        assertEquals("a limit of 1 cut the page to ${page.items.size}", 6, page.items.size)
    }
}
