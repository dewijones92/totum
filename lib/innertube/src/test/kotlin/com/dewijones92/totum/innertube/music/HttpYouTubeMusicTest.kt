package com.dewijones92.totum.innertube.music

import com.dewijones92.totum.innertube.auth.AccessToken
import com.dewijones92.totum.innertube.auth.OAuthTokens
import com.dewijones92.totum.innertube.auth.RefreshToken
import com.dewijones92.totum.innertube.auth.YouTubeAccount
import com.dewijones92.totum.innertube.auth.fake.FakeYouTubeAuth
import com.dewijones92.totum.innertube.auth.fake.InMemoryTokenStore
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

class HttpYouTubeMusicTest {

    private val server = MockWebServer()

    @Before fun setUp() = server.start()

    @After fun tearDown() = server.close()

    private fun fixture(name: String): String = requireNotNull(
        javaClass.classLoader?.getResourceAsStream("music/$name"),
    ).bufferedReader().readText()

    private fun client() = InnerTubeClient(
        OkHttpClient(),
        browseUrl = server.url("/tv/browse").toString(),
        musicSearchUrl = server.url("/music/search").toString(),
        musicBrowseUrl = server.url("/music/browse").toString(),
        musicNextUrl = server.url("/music/next").toString(),
    )

    private fun ok(body: String) = server.enqueue(MockResponse.Builder().code(200).body(body).build())

    @Test
    fun `album search asks YouTube Music with the albums filter, anonymously`() = runBlocking {
        ok(fixture("albums-search.json"))

        val albums = HttpYouTubeMusicCatalogue(client()).albums("abbey road", limit = 2)

        assertEquals(2, (albums as MusicResult.Success).value.items.size)
        val request = server.takeRequest()
        assertEquals("/music/search", request.url.encodedPath)
        assertTrue(request.body!!.utf8().contains("EgWKAQIYAWoKEAoQCRADEAQQBQ"))
        assertEquals(null, request.headers["Authorization"])
    }

    @Test
    fun `an album playlist id is turned into its album page in two calls`() = runBlocking {
        ok(fixture("album-as-playlist.json"))
        ok(fixture("album.json"))

        val album = HttpYouTubeMusicCatalogue(client()).albumForPlaylist("OLAK5uy_nMJmELuIq7hkr1fvMVh2JK50kfaoKr5yw")

        assertEquals("MPREb_ky8xEro8eK9", (album as MusicResult.Success).value.browseId)
        assertTrue(server.takeRequest().body!!.utf8().contains("VLOLAK5uy_nMJmELuIq7hkr1fvMVh2JK50kfaoKr5yw"))
        assertTrue(server.takeRequest().body!!.utf8().contains("MPREb_ky8xEro8eK9"))
    }

    @Test
    fun `a radio continuation carries the token and the playlist`() = runBlocking {
        ok(fixture("radio-continuation.json"))

        HttpYouTubeMusicCatalogue(client()).radio(RadioSeed.forSong("BNMKGYiJpvg"), continuation = "TOKEN123")

        val body = server.takeRequest().body!!.utf8()
        assertTrue(body.contains("\"continuation\":\"TOKEN123\""))
        assertTrue(body.contains("RDAMVMBNMKGYiJpvg"))
    }

    @Test
    fun `a page with no recognisable album is a failure, not an empty album`() = runBlocking {
        ok("{}")

        assertTrue(HttpYouTubeMusicCatalogue(client()).album("MPREb_x") is MusicResult.Failure)
    }

    @Test
    fun `a server error is a failure`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(500).body("").build())

        assertTrue(HttpYouTubeMusicCatalogue(client()).artist("UCx") is MusicResult.Failure)
    }

    @Test
    fun `the library is read as the TV client with the account's token`() = runBlocking {
        ok(fixture("library-albums-tv.json"))
        val account = YouTubeAccount(FakeYouTubeAuth(), InMemoryTokenStore(TOKENS), nowEpochSeconds = { 0 })

        val albums = HttpYouTubeMusicLibrary(account, client()).albums()

        assertEquals(2, (albums as LibraryResult.Success).value.size)
        val request = server.takeRequest()
        assertEquals("/tv/browse", request.url.encodedPath)
        assertEquals("Bearer at", request.headers["Authorization"])
        assertTrue(request.body!!.utf8().contains("FEmusic_liked_albums"))
    }

    @Test
    fun `the library says signed out when there is no account`() = runBlocking {
        val account = YouTubeAccount(FakeYouTubeAuth(), InMemoryTokenStore(null), nowEpochSeconds = { 0 })

        assertEquals(LibraryResult.SignedOut, HttpYouTubeMusicLibrary(account, client()).artists())
    }

    private companion object {
        val TOKENS = OAuthTokens(AccessToken("at"), RefreshToken("rt"), expiresAtEpochSeconds = 3600)
    }
}
