package com.dewijones92.totum.innertube.feeds

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

class HttpYouTubeFeedsTest {

    private val server = MockWebServer()

    @Before fun setUp() = server.start()

    @After fun tearDown() = server.close()

    private fun feeds(signedIn: Boolean = true): HttpYouTubeFeeds {
        val initial = if (signedIn) TOKENS else null
        val account = YouTubeAccount(FakeYouTubeAuth(), InMemoryTokenStore(initial), nowEpochSeconds = { 0 })
        return HttpYouTubeFeeds(account, InnerTubeClient(OkHttpClient(), browseUrl = server.url("/b").toString()))
    }

    @Test
    fun `history browses FEhistory and parses videos`() = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(200).body(
                """{"contents":{"c":[{"tileRenderer":{"contentType":"TILE_CONTENT_TYPE_VIDEO",
                   "onSelectCommand":{"watchEndpoint":{"videoId":"vvvvvvvvvvv"}},
                   "metadata":{"tileMetadataRenderer":{"title":{"simpleText":"Watched"}}}}}]}}""",
            ).build(),
        )

        val result = feeds().history()

        assertEquals("vvvvvvvvvvv", (result as FeedResult.Success).page.items.single().videoId)
        assertTrue(server.takeRequest().body?.utf8().orEmpty().contains("FEhistory"))
    }

    @Test
    fun `a channel is browsed as the account and its progress bars are seen`() = runBlocking {
        SeenProgress.reset()
        server.enqueue(
            MockResponse.Builder().code(200).body(
                """{"contents":{"c":[{"tileRenderer":{"contentType":"TILE_CONTENT_TYPE_VIDEO",
                   "onSelectCommand":{"watchEndpoint":{"videoId":"vvvvvvvvvvv"}},
                   "metadata":{"tileMetadataRenderer":{"title":{"simpleText":"Half watched"},
                     "lines":[{"lineRenderer":{"items":[{"lineItemRenderer":{"text":{"simpleText":"10:00"}}}]}}]}},
                   "header":{"tileHeaderRenderer":{"thumbnailOverlays":[
                     {"thumbnailOverlayResumePlaybackRenderer":{"percentDurationWatched":46}},
                     {"thumbnailOverlayTimeStatusRenderer":{"text":{"simpleText":"10:00"}}}]}}}}]}}""",
            ).build(),
        )

        val result = feeds().channel("UCben0000000000000000000")

        assertEquals("vvvvvvvvvvv", (result as FeedResult.Success).page.items.single().videoId)
        assertTrue(server.takeRequest().body?.utf8().orEmpty().contains("UCben0000000000000000000"))
        assertEquals(276_000L, SeenProgress.positions.value.getValue("vvvvvvvvvvv").positionMs)
        SeenProgress.reset()
    }

    @Test
    fun `signed out without a token`() = runBlocking {
        assertEquals(FeedResult.SignedOut, feeds(signedIn = false).recommended())
    }

    @Test
    fun `403 is treated as signed out`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(403).body("no").build())
        assertEquals(FeedResult.SignedOut, feeds().watchLater())
    }

    private companion object {
        val TOKENS = OAuthTokens(AccessToken("at"), RefreshToken("rt"), expiresAtEpochSeconds = 3_600)
    }
}
