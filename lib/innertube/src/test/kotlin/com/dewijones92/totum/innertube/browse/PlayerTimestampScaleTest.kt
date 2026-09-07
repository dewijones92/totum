package com.dewijones92.totum.innertube.browse

import com.dewijones92.totum.innertube.auth.AccessToken
import com.dewijones92.totum.innertube.player.SignatureTimestamp
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

/**
 * Each player call declares the timestamp on the scale its client is checked against.
 *
 * The web-scale number in a TV request is the exact mistake that killed progress sync and the
 * age-restricted fallback for three weeks (2026-08-18 to 2026-09-06), so it is pinned per call.
 */
class PlayerTimestampScaleTest {

    private val server = MockWebServer()

    @Before fun setUp() = server.start()

    @After fun tearDown() = server.close()

    private val client by lazy { InnerTubeClient(OkHttpClient(), playerUrl = server.url("/player").toString()) }
    private val stamp = SignatureTimestamp(20697)
    private val token = AccessToken("at")

    private fun ok() = MockResponse.Builder().code(200).body("""{"playabilityStatus":{"status":"OK"}}""").build()

    private fun bodySent(): String = server.takeRequest().body!!.utf8()

    @Test
    fun `the signed-in TV player declares the TV scale`() = runBlocking {
        server.enqueue(ok())
        client.playerTracking("vid", stamp, token)
        val body = bodySent()
        assertTrue(body, body.contains(""""signatureTimestamp":20697001"""))
        assertTrue(body, body.contains("TVHTML5"))
    }

    @Test
    fun `the downgraded TV player declares the TV scale too`() = runBlocking {
        server.enqueue(ok())
        client.playerDowngradedTv("vid", stamp, token)
        val body = bodySent()
        assertTrue(body, body.contains(""""signatureTimestamp":20697001"""))
    }

    @Test
    fun `the WEB player keeps the number every player script carries`() = runBlocking {
        server.enqueue(ok())
        client.playerAsWeb("vid", "visitor", stamp, "po")
        val body = bodySent()
        assertTrue(body, body.contains(""""signatureTimestamp":20697}"""))
        assertTrue(body, body.contains(""""clientName":"WEB""""))
    }

    @Test
    fun `the embedded player carries its two load-bearing fields and the web-scale timestamp`() = runBlocking {
        server.enqueue(ok())
        client.playerAsEmbedded("vid", "visitor-1", stamp, "HOSTFLAGS")
        val request = server.takeRequest()
        val body = request.body!!.utf8()
        assertTrue(body, body.contains(""""clientName":"WEB_EMBEDDED_PLAYER""""))
        assertTrue(body, body.contains(""""encryptedHostFlags":"HOSTFLAGS""""))
        assertTrue(body, body.contains(""""thirdParty":{"embedUrl":"https://www.reddit.com/"}"""))
        assertTrue(body, body.contains(""""visitorData":"visitor-1""""))
        assertTrue(body, body.contains(""""signatureTimestamp":20697,"""))
        assertTrue(body, !body.contains("poToken"))
        assertEquals("56", request.headers["X-Youtube-Client-Name"])
        assertNull(request.headers["Authorization"])
    }
}
