package com.dewijones92.totum.data.home

import com.dewijones92.totum.common.HomeServerAuth
import com.dewijones92.totum.data.jellyfin.JellyfinItem
import com.dewijones92.totum.data.jellyfin.JellyfinKind
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class JellyfinClientTest {

    private val server = MockWebServer()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun base() = server.url("/fetch").toString().trimEnd('/')

    private fun client(token: String = "device") = JellyfinClient(OkHttpClient(), base(), { token })

    private val listing = """
        {"parent":null,"title":"Jellyfin","items":[
          {"id":"0123456789abcdef0123456789abcdef","name":"Heat","kind":"MOVIE","year":1995,"container":"mkv"},
          {"id":"fedcba9876543210fedcba9876543210","name":"Bluey","kind":"SERIES"}
        ],"total":40,"next":2}
    """.trimIndent()

    @Test
    fun `the top level is asked for with no query and the device token`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body(listing).build())

        val answer = client().browse(null)

        val sent = server.takeRequest()
        assertEquals("/fetch/jellyfin/browse", sent.url.encodedPath)
        assertNull(sent.url.encodedQuery)
        assertEquals("device", sent.headers[HomeServerAuth.HEADER])
        val read = (answer as HomeAnswer.Ok).value
        assertEquals(listOf("Heat", "Bluey"), read.items.map { it.name })
        assertEquals(JellyfinKind.SERIES, read.items[1].kind)
        assertEquals(2, read.next)
        assertEquals(40, read.total)
    }

    @Test
    fun `a folder and a page are asked for by parent and start, and the parent is encoded`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body(listing).build())

        client().browse("a b&c", start = 50)

        val query = server.takeRequest().url
        assertEquals("a b&c", query.queryParameter("parent"))
        assertEquals("50", query.queryParameter("start"))
    }

    @Test
    fun `the first page sends no start at all`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body(listing).build())

        client().browse("0123456789abcdef0123456789abcdef", start = 0)

        assertNull(server.takeRequest().url.queryParameter("start"))
    }

    @Test
    fun `the Pi's own words come back when Jellyfin is not set up or unreachable`() = runTest {
        server.enqueue(
            MockResponse.Builder().code(
                503
            ).body("""{"error":"ℹ️ Jellyfin isn't set up on the home server"}""").build(),
        )
        server.enqueue(MockResponse.Builder().code(502).body("""{"error":"❌ Jellyfin did not answer"}""").build())

        assertEquals(
            HomeAnswer.No("ℹ️ Jellyfin isn't set up on the home server", 503),
            client().browse(null),
        )
        assertEquals(HomeAnswer.No("❌ Jellyfin did not answer", 502), client().browse(null))
    }

    @Test
    fun `an answer that is not a listing is an error the phone can show`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("""<html>nginx</html>""").build())

        val answer = client().browse(null)

        assertTrue(answer.toString(), answer is HomeAnswer.No)
        assertEquals("❌ The home server's answer made no sense", (answer as HomeAnswer.No).reason)
    }

    @Test
    fun `without a token nothing is sent`() = runTest {
        val answer = client(token = "").browse(null)

        assertEquals(HomeAnswer.No(HomePiCall.NOT_SIGNED_IN), answer)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a stream address is the Pi's, keyed by the item id and its container`() {
        val item = JellyfinItem("0123456789abcdef0123456789abcdef", "Heat", JellyfinKind.MOVIE, container = "mp4,m4v")

        val url = client().streamUrl(item).value

        assertEquals("${base()}/jellyfin/stream/0123456789abcdef0123456789abcdef.mp4", url)
        assertTrue(!url.contains("api_key"))
    }

    @Test
    fun `a poster address is the Pi's, keyed by the item id, with no key in it`() {
        val item = JellyfinItem("0123456789abcdef0123456789abcdef", "Heat", JellyfinKind.MOVIE)

        val url = client().imageUrl(item)?.value

        assertEquals("${base()}/jellyfin/image/0123456789abcdef0123456789abcdef", url)
    }
}
