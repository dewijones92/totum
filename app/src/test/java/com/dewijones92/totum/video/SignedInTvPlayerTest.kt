package com.dewijones92.totum.video

import com.dewijones92.totum.innertube.auth.AccessToken
import com.dewijones92.totum.innertube.auth.OAuthTokens
import com.dewijones92.totum.innertube.auth.RefreshToken
import com.dewijones92.totum.innertube.auth.YouTubeAccount
import com.dewijones92.totum.innertube.auth.fake.FakeYouTubeAuth
import com.dewijones92.totum.innertube.auth.fake.InMemoryTokenStore
import com.dewijones92.totum.innertube.browse.InnerTubeClient
import com.dewijones92.totum.innertube.player.HttpSignatureTimestampSource
import com.dewijones92.totum.innertube.player.NSolver
import com.dewijones92.totum.innertube.player.PlayerResult
import com.dewijones92.totum.innertube.player.SignatureSolver
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SignedInTvPlayerTest {

    private val server = MockWebServer()

    @Before fun setUp() = server.start()

    @After fun tearDown() = server.close()

    private fun player(): SignedInTvPlayer {
        val http = OkHttpClient()
        return SignedInTvPlayer(
            youTubeAccount = YouTubeAccount(FakeYouTubeAuth(), InMemoryTokenStore(TOKENS), nowEpochSeconds = { 0 }),
            signatureTimestamps = HttpSignatureTimestampSource(
                http,
                iframeApiUrl = server.url("/iframe_api").toString(),
                playerScriptUrl = { server.url("/tv.js").toString() },
            ),
            innerTubeClient = InnerTubeClient(
                http,
                playerUrl = server.url("/player").toString(),
                nextUrl = server.url("/next").toString(),
            ),
            nSolver = NSolver { challenges, _ -> challenges.associateWith { "solved" } },
            signatureSolver = SignatureSolver { challenges, _ -> challenges.associateWith { "SIGNED+$it" } },
        )
    }

    private fun enqueue(body: String) = server.enqueue(MockResponse.Builder().code(200).body(body).build())

    private fun timestampSource() {
        enqueue("""var x = "player\/abcdef12\/";""")
        enqueue("""signatureTimestamp:20738""")
    }

    @Test
    fun `a TV answer with no title is described by the account's watch page`() = runBlocking {
        timestampSource()
        enqueue(untitledPlainFormat())
        enqueue(watchPage())
        val result = player().playerFor("vid", describe = true) as? PlayerResult.Success
        assertEquals("Where Does The Illuminati Conspiracy Come From?", result?.details?.title)
        assertEquals("Dan Snow's History Hit", result?.details?.author)
        assertEquals("UC4wKw34XH0pzeeBZ8xT29Eg", result?.details?.channelId)
        assertEquals(2712L, result?.details?.lengthSeconds)
    }

    @Test
    fun `a row that already has its title spares the watch page`() = runBlocking {
        timestampSource()
        enqueue(untitledPlainFormat())
        val result = player().playerFor("vid", describe = false) as? PlayerResult.Success
        assertEquals(listOf("/iframe_api", "/tv.js", "/player"), List(3) { server.takeRequest().url.encodedPath })
        assertEquals(3, server.requestCount)
        assertEquals("the player response's own length survives", 2712L, result?.details?.lengthSeconds)
        assertEquals("UC4wKw34XH0pzeeBZ8xT29Eg", result?.details?.channelId)
        assertEquals(
            "with no title, the id stands in, which every rule reads as no title",
            "vid",
            result?.details?.title
        )
    }

    @Test
    fun `a ciphered format is signed and solved into a playable link`() = runBlocking {
        timestampSource()
        enqueue(onlyCiphered())
        val result = player().playerFor("vid", describe = true) as? PlayerResult.Success
        val url = result?.streaming?.directlyPlayable?.singleOrNull()?.url?.value
        assertEquals("https://x.test/videoplayback?itag=251&n=solved&sig=SIGNED%2BABC", url)
    }

    @Test
    fun `nothing fetchable from the downgraded TV client is no answer, not the tracking client's dead links`() =
        runBlocking {
            timestampSource()
            enqueue(onlyUnsolvableCiphered())
            enqueue(onePlainFormat())
            val result = player().playerFor("vid", describe = true)
            assertNull(result)
            assertEquals(3, server.requestCount)
        }

    @Test
    fun `a downgraded answer with plain links is taken`() = runBlocking {
        timestampSource()
        enqueue(onePlainFormat())
        val result = player().playerFor("vid", describe = true)
        assertTrue(result.toString(), result is PlayerResult.Success)
        assertEquals(
            listOf("/iframe_api", "/tv.js", "/player", "/next"),
            List(4) { server.takeRequest().url.encodedPath }
        )
    }

    private fun onlyCiphered() =
        """
        {"playabilityStatus":{"status":"OK"},
         "streamingData":{"adaptiveFormats":[
           {"itag":251,"mimeType":"audio/webm; codecs=\"opus\"","bitrate":1000,
            "signatureCipher":"s=ABC&sp=sig&url=https%3A%2F%2Fx.test%2Fvideoplayback%3Fitag%3D251%26n%3DRAW"}]}}
        """.trimIndent()

    private fun onlyUnsolvableCiphered() =
        """
        {"playabilityStatus":{"status":"OK"},
         "streamingData":{"adaptiveFormats":[
           {"itag":251,"mimeType":"audio/webm; codecs=\"opus\"","bitrate":1000,
            "signatureCipher":"sp=sig&url=https%3A%2F%2Fx.test%2Fvideoplayback%3Fitag%3D251%26n%3DRAW"}]}}
        """.trimIndent()

    private fun untitledPlainFormat() =
        """
        {"playabilityStatus":{"status":"OK"},
         "videoDetails":{"videoId":"vid","lengthSeconds":"2712","channelId":"UC4wKw34XH0pzeeBZ8xT29Eg"},
         "streamingData":{"formats":[
           {"itag":18,"mimeType":"video/mp4; codecs=\"avc1, mp4a\"","height":360,"bitrate":1000,
            "url":"https://x.test/videoplayback?itag=18&n=RAW&sig=keep"}]}}
        """.trimIndent()

    private fun watchPage() =
        """
        {"contents":{"singleColumnWatchNextResults":{"results":{"results":{"contents":[{"itemSectionRenderer":
          {"contents":[{"videoMetadataRenderer":{"videoId":"vid",
            "title":{"runs":[{"text":"Where Does The Illuminati Conspiracy Come From?"}]},
            "owner":{"videoOwnerRenderer":{"title":{"simpleText":"Dan Snow's History Hit"},
              "navigationEndpoint":{"browseEndpoint":{"browseId":"UC4wKw34XH0pzeeBZ8xT29Eg"}}}},
            "publishedTimeText":{"simpleText":"Published on Oct 14, 2026"}}}]}}]}}}}}
        """.trimIndent()

    private fun onePlainFormat() =
        """
        {"playabilityStatus":{"status":"OK"},
         "streamingData":{"formats":[
           {"itag":18,"mimeType":"video/mp4; codecs=\"avc1, mp4a\"","height":360,"bitrate":1000,
            "url":"https://x.test/videoplayback?itag=18&n=RAW&sig=keep"}]}}
        """.trimIndent()

    private companion object {
        val TOKENS = OAuthTokens(AccessToken("at"), RefreshToken("rt"), expiresAtEpochSeconds = 3_600)
    }
}
