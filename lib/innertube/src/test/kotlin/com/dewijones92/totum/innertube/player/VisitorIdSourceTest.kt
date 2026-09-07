package com.dewijones92.totum.innertube.player

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

class VisitorIdSourceTest {

    private val server = MockWebServer()

    @Before fun setUp() = server.start()

    @After fun tearDown() = server.close()

    private val answer = """{"responseContext":{"visitorData":"CgtxSlZuQXR3U2ZpbyiVhvbUBjIKCgJHQhIEGgAgQQ%3D%3D",
        "serviceTrackingParams":[]}}"""

    @Test
    fun `the visitor id is read out of the answer`() {
        assertEquals("CgtxSlZuQXR3U2ZpbyiVhvbUBjIKCgJHQhIEGgAgQQ%3D%3D", VisitorId.parse(answer))
        assertNull(VisitorId.parse("""{"responseContext":{}}"""))
        assertNull(VisitorId.parse("not json"))
    }

    @Test
    fun `it is asked as the WEB client, once per process`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body(answer).build())
        val source = HttpVisitorIdSource(OkHttpClient(), server.url("/visitor_id").toString())

        assertEquals("CgtxSlZuQXR3U2ZpbyiVhvbUBjIKCgJHQhIEGgAgQQ%3D%3D", source.current())
        assertEquals("CgtxSlZuQXR3U2ZpbyiVhvbUBjIKCgJHQhIEGgAgQQ%3D%3D", source.current())

        val request = server.takeRequest()
        assertEquals("1", request.headers["X-Youtube-Client-Name"])
        assertTrue(request.body!!.utf8().contains(""""clientName":"WEB""""))
        assertEquals(1, server.requestCount)
    }
}
