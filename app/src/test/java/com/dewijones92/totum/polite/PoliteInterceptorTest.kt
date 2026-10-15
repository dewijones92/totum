package com.dewijones92.totum.polite

import com.dewijones92.totum.common.PoliteGate
import com.dewijones92.totum.common.RequestMeter
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class PoliteInterceptorTest {

    private val server = MockWebServer()
    private var clock = 1_000_000L
    private val slept = mutableListOf<Long>()
    private val gate = PoliteGate(now = { clock }, sleep = {
        slept += it
        clock += it
    }, jitter = { 0.5 })
    private val meter = RequestMeter { clock }

    @Before fun setUp() = server.start()

    @After fun tearDown() = server.close()

    private fun client(gatedHosts: Boolean = true) = OkHttpClient.Builder()
        .addInterceptor(PoliteInterceptor(gate, meter, gated = { gatedHosts }, now = { clock }))
        .build()

    private fun get(client: OkHttpClient) =
        client.newCall(Request.Builder().url(server.url("/youtubei/v1/player")).build()).execute().use { it.code }

    private fun reply(code: Int, retryAfter: String? = null) = server.enqueue(
        MockResponse.Builder().code(code).apply { retryAfter?.let { addHeader("Retry-After", it) } }.build(),
    )

    @Test
    fun `a 429 pauses, then the call is retried once and the pause clears on success`() {
        reply(429)
        reply(200)
        assertEquals(200, get(client()))
        assertEquals(30_000L, slept.single())
        assertEquals(2, server.requestCount)
        assertNull(gate.slowDown.value)
    }

    @Test
    fun `a pause longer than a caller should wait is not waited for`() {
        reply(429, retryAfter = "300")
        assertEquals(429, get(client()))
        assertEquals(1, server.requestCount)
        assertEquals(clock + 300_000, gate.slowDown.value?.untilMs)
    }

    @Test
    fun `every call is metered, gated or not`() {
        reply(200)
        reply(403)
        get(client(gatedHosts = false))
        get(client(gatedHosts = false))
        assertEquals(mapOf(server.hostName to "2 call(s), 0 429, 1 other 4xx"), meter.lastHour())
    }

    @Test
    fun `video bytes and images are not YouTube API calls`() {
        assertEquals(
            listOf(true, true, true, false, false, false),
            listOf(
                "www.youtube.com",
                "music.youtube.com",
                "youtubei.googleapis.com",
                "rr2---sn-8vq54vox03-cgnl.googlevideo.com",
                "i.ytimg.com",
                "yt3.ggpht.com",
            ).map(PoliteInterceptor::isYouTubeApi),
        )
    }
}
