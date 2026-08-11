package com.dewijones92.totum.innertube.browse

import com.dewijones92.totum.innertube.auth.AccessToken
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The one rule about authentication, pinned at the only place that can apply it.
 *
 * Dewi, 2026-08-11: *"make sure we have as much as possible auth requests to YouTube. Maybe some
 * global middleware"*. This is the middleware's contract: **the token goes on every request whose
 * declared client will accept one, and on no others.**
 *
 * Both halves matter and both were learned by being burnt. Forgetting the token is a request that
 * credits nobody — which is how watch history and search were anonymous for months. Attaching it
 * where it does not belong is worse: InnerTube cross-checks the declared client against the headers
 * and answers `HTTP 400`, so an over-eager middleware would break playback outright rather than
 * degrade politely.
 */
class AuthAttachedByIdentityTest {

    private val server = MockWebServer()

    @Before fun setUp() = server.start()

    @After fun tearDown() = server.close()

    private fun client(token: AccessToken? = TOKEN) = InnerTubeClient(
        OkHttpClient(),
        browseUrl = server.url("/browse").toString(),
        nextUrl = server.url("/next").toString(),
        searchUrl = server.url("/search").toString(),
        playerUrl = server.url("/player").toString(),
        musicSearchUrl = server.url("/music/search").toString(),
        accountToken = { token },
    )

    /** A TV call is authenticated without the call site doing anything about it. */
    @Test
    fun `a TV client request carries the account token automatically`() = runBlocking {
        server.enqueue(ok())

        // browseWeb is WEB; `action` is the TV write path and passes no token of its own here.
        client().action(server.url("/browse").toString(), """"target":{"videoId":"abc"}""", TOKEN)

        assertEquals("Bearer secret", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `the anonymous search stays anonymous, because a bearer would be refused`() = runBlocking {
        server.enqueue(ok())

        client().search(SearchTarget.Query("nina"))

        assertNull(
            "a bearer with a WEB context is answered HTTP 400 — attaching it here breaks search",
            server.takeRequest().headers["Authorization"],
        )
    }

    @Test
    fun `the streams client is never authenticated`() = runBlocking {
        server.enqueue(ok())

        client().player("abc12345678")

        assertNull(
            "the ANDROID client refuses a bearer, and this is the call that fetches playable streams",
            server.takeRequest().headers["Authorization"],
        )
    }

    @Test
    fun `YouTube Music is never authenticated either`() = runBlocking {
        server.enqueue(ok())

        client().searchMusic(SearchTarget.Query("nina"))

        assertNull(server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `comments stay anonymous`() = runBlocking {
        server.enqueue(ok())

        client().next("abc12345678")

        assertNull(server.takeRequest().headers["Authorization"])
    }

    /** The signed-in search DOES get it, which is the whole point of that path existing. */
    @Test
    fun `the signed-in search carries it`() = runBlocking {
        server.enqueue(ok())

        client().searchAsAccount(SearchTarget.Query("nina"), TOKEN)

        val request = server.takeRequest()
        assertEquals("Bearer secret", request.headers["Authorization"])
        assertEquals("7", request.headers["X-Youtube-Client-Name"])
    }

    /** Signed out, a TV call is simply anonymous rather than failing or waiting. */
    @Test
    fun `signed out, nothing is attached and nothing breaks`() = runBlocking {
        server.enqueue(ok())

        client(token = null).searchMusic(SearchTarget.Query("nina"))

        assertNull(server.takeRequest().headers["Authorization"])
    }

    private fun ok() = MockResponse.Builder().code(200).body("""{"contents":{}}""").build()

    private companion object {
        val TOKEN = AccessToken("secret")
    }
}
