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

class EmbedHostFlagsTest {

    private val server = MockWebServer()

    @Before fun setUp() = server.start()

    @After fun tearDown() = server.close()

    private val page = """<html><script>ytcfg.set({"WEB_PLAYER_CONTEXT_CONFIGS":
        {"WEB_PLAYER_CONTEXT_CONFIG_ID_EMBEDDED_PLAYER":{"rootElementId":"movie_player",
        "encryptedHostFlags":"AJi0VCsAxpraJ6rKuB77X7xcicy_0SPCWnxnxi","serializedExperimentIds":"1"}}});
        </script></html>"""

    @Test
    fun `the flags are read out of the embed page's player config`() {
        assertEquals("AJi0VCsAxpraJ6rKuB77X7xcicy_0SPCWnxnxi", EmbedHostFlags.parse(page))
        assertNull(EmbedHostFlags.parse("<html>nothing here</html>"))
    }

    @Test
    fun `the page is fetched as an embed, with the referer the player will claim, once per video`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body(page).build())
        val source = HttpEmbedHostFlagsSource(OkHttpClient()) { id -> server.url("/embed/$id?html5=1").toString() }

        assertEquals("AJi0VCsAxpraJ6rKuB77X7xcicy_0SPCWnxnxi", source.forVideo("aqz-KE-bpKQ"))
        assertEquals("AJi0VCsAxpraJ6rKuB77X7xcicy_0SPCWnxnxi", source.forVideo("aqz-KE-bpKQ"))

        val request = server.takeRequest()
        assertEquals("/embed/aqz-KE-bpKQ?html5=1", request.target)
        assertEquals("https://www.reddit.com/", request.headers["Referer"])
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `an HTTP failure is a null, not a crash — the caller then asks another client`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(500).build())
        val source = HttpEmbedHostFlagsSource(OkHttpClient()) { id -> server.url("/embed/$id").toString() }
        assertNull(source.forVideo("x"))
    }
}
