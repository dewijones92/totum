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
            innerTubeClient = InnerTubeClient(http, playerUrl = server.url("/player").toString()),
            nSolver = NSolver { challenges, _ -> challenges.associateWith { "solved" } },
        )
    }

    private fun enqueue(body: String) = server.enqueue(MockResponse.Builder().code(200).body(body).build())

    private fun timestampSource() {
        enqueue("""var x = "player\/abcdef12\/";""")
        enqueue("""signatureTimestamp:20738""")
    }

    @Test
    fun `nothing fetchable from the downgraded TV client is no answer, not the tracking client's dead links`() =
        runBlocking {
            timestampSource()
            enqueue(onlyCiphered())
            enqueue(onePlainFormat())
            val result = player().playerFor("vid")
            assertNull(result)
            assertEquals(3, server.requestCount)
        }

    @Test
    fun `a downgraded answer with plain links is taken`() = runBlocking {
        timestampSource()
        enqueue(onePlainFormat())
        val result = player().playerFor("vid")
        assertTrue(result.toString(), result is PlayerResult.Success)
        assertEquals(3, server.requestCount)
    }

    private fun onlyCiphered() =
        """
        {"playabilityStatus":{"status":"OK"},
         "streamingData":{"adaptiveFormats":[
           {"itag":251,"mimeType":"audio/webm; codecs=\"opus\"","bitrate":1000,
            "signatureCipher":"s=ABC&sp=sig&url=https%3A%2F%2Fx.test%2Fvideoplayback%3Fitag%3D251%26n%3DRAW"}]}}
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
