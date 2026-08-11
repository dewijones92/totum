package com.dewijones92.totum.innertube.search

import com.dewijones92.totum.innertube.auth.AccessToken
import com.dewijones92.totum.innertube.browse.InnerTubeClient
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Search attributed to the account, and the rule that makes it safe to try.
 *
 * Dewi, 2026-08-11: *"authed requests every if sensibly possible"*. His searches feed the
 * recommendations, and an anonymous search credits nobody. But nobody here can know whether
 * YouTube's TV client — the only identity that accepts a bearer token — answers `/search` with
 * renderers this parser understands, and **"no results" is not an error**. So the contract under
 * test is that the signed-in attempt can only ever ADD: anything less than a usable page falls
 * back to the anonymous search that worked before.
 */
class AuthenticatedSearchTest {

    private val server = MockWebServer()

    @Before fun setUp() = server.start()

    @After fun tearDown() = server.close()

    private fun search(token: AccessToken? = TOKEN) = HttpYouTubeSearch(
        InnerTubeClient(OkHttpClient(), searchUrl = server.url("/search").toString()),
        token = { token },
    )

    @Test
    fun `signed in, the account's search is used`() = runBlocking {
        server.enqueue(ok(oneResult()))

        val result = search().searchVideos("nina simone", limit = 5)

        assertEquals(listOf("abc12345678"), videos(result))
        val request = server.takeRequest()
        assertEquals("Bearer secret", request.headers["Authorization"])
        assertTrue(
            "must declare the TV client, or YouTube answers 400",
            request.headers["X-Youtube-Client-Name"] == "7"
        )
        assertTrue(request.body?.utf8().orEmpty().contains("TVHTML5"))
    }

    @Test
    fun `signed out, nothing is wasted asking`() = runBlocking {
        server.enqueue(ok(oneResult()))

        val result = search(token = null).searchVideos("nina simone", limit = 5)

        assertEquals(listOf("abc12345678"), videos(result))
        assertEquals("exactly one request, the anonymous one", 1, server.requestCount)
        assertFalse(server.takeRequest().body?.utf8().orEmpty().contains("TVHTML5"))
    }

    /** THE SAFETY RULE. A refused signed-in attempt must not cost the user their search. */
    @Test
    fun `a refused signed-in search falls back to the anonymous one`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(400).body("nope").build())
        server.enqueue(ok(oneResult()))

        val result = search().searchVideos("nina simone", limit = 5)

        assertEquals(listOf("abc12345678"), videos(result))
        assertEquals(2, server.requestCount)
    }

    /**
     * And the subtler half: a signed-in response this parser cannot read comes back as an EMPTY
     * page, not an error. Without this rule that would silently empty the search screen — the
     * worst possible outcome, because it looks like YouTube has no results for you.
     */
    @Test
    fun `a signed-in search that parses to nothing falls back too`() = runBlocking {
        server.enqueue(ok("""{"contents":{"someUnknownRenderer":{}}}"""))
        server.enqueue(ok(oneResult()))

        val result = search().searchVideos("nina simone", limit = 5)

        assertEquals(listOf("abc12345678"), videos(result))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `both failing is still a failure, not a silent empty list`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(400).body("nope").build())
        server.enqueue(MockResponse.Builder().code(500).body("nope").build())

        val result = search().searchVideos("nina simone", limit = 5)

        assertTrue(result.toString(), result is SearchVideosResult.Failure)
    }

    @Test
    fun `the query itself reaches the signed-in request`() = runBlocking {
        server.enqueue(ok(oneResult()))

        search().searchVideos("nina simone feeling good", limit = 5)

        assertTrue(server.takeRequest().body?.utf8().orEmpty().contains("nina simone feeling good"))
    }

    private fun videos(result: SearchVideosResult): List<String> =
        (result as SearchVideosResult.Success).page.items.map { it.videoId }

    private fun ok(body: String) = MockResponse.Builder().code(200).body(body).build()

    /** The classic `videoRenderer` shape the WEB search returns, trimmed to what the parser reads. */
    private fun oneResult(): String = """
        {"contents":{"twoColumnSearchResultsRenderer":{"primaryContents":{"sectionListRenderer":
        {"contents":[{"itemSectionRenderer":{"contents":[{"videoRenderer":{
          "videoId":"abc12345678",
          "title":{"runs":[{"text":"Feeling Good"}]},
          "ownerText":{"runs":[{"text":"Nina Simone"}]},
          "lengthText":{"simpleText":"2:54"}
        }}]}}]}}}}}
    """.trimIndent()

    private companion object {
        val TOKEN = AccessToken("secret")
    }
}
