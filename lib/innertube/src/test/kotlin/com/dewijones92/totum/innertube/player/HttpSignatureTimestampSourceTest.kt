package com.dewijones92.totum.innertube.player

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class HttpSignatureTimestampSourceTest {

    private val server = MockWebServer()

    @Before fun setUp() = server.start()

    @After fun tearDown() = server.close()

    private fun source() = HttpSignatureTimestampSource(
        client = OkHttpClient(),
        iframeApiUrl = server.url("/iframe_api").toString(),
        playerScriptUrl = { build -> server.url("/s/player/$build/tv-player-ias.js").toString() },
    )

    private fun enqueue(body: String, code: Int = 200) =
        server.enqueue(MockResponse.Builder().code(code).body(body).build())

    @Test
    fun `finds the player build in iframe_api, then the timestamp in its script`() = runBlocking {
        // The build id appears escaped in the iframe API's JS string, exactly like this.
        enqueue("""var x="\/s\/player\/bed7a914\/tv-player-ias.vflset\/tv-player-ias.js";""")
        enqueue("""a.signatureTimestamp=20662;var b=1""")

        assertEquals(SignatureTimestamp(20662), source().current())
        assertEquals("/iframe_api", server.takeRequest().target)
        assertEquals("/s/player/bed7a914/tv-player-ias.js", server.takeRequest().target)
    }

    @Test
    fun `the timestamp is fetched once and reused — YouTube ships a new player weekly, not per video`() =
        runBlocking {
            enqueue("""var x="\/s\/player\/bed7a914\/tv-player-ias.vflset\/tv-player-ias.js";""")
            enqueue("""signatureTimestamp:20662,""")
            val source = source()

            assertEquals(SignatureTimestamp(20662), source.current())
            assertEquals(SignatureTimestamp(20662), source.current())
            assertEquals(2, server.requestCount)
        }

    @Test
    fun `an unusable iframe_api is a null, not a crash — the caller then skips the sync`() = runBlocking {
        enqueue("no player reference here at all")
        assertNull(source().current())
    }

    @Test
    fun `a script with no timestamp is a null`() = runBlocking {
        enqueue("""var x="\/s\/player\/bed7a914\/tv-player-ias.vflset\/tv-player-ias.js";""")
        enqueue("just some javascript")
        assertNull(source().current())
    }

    @Test
    fun `an HTTP failure is a null`() = runBlocking {
        enqueue("nope", code = 500)
        assertNull(source().current())
    }
}
