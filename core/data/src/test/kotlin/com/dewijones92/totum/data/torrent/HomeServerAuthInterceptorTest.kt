package com.dewijones92.totum.data.torrent

import com.dewijones92.totum.common.HomeServerAuth
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class HomeServerAuthInterceptorTest {

    private val home = MockWebServer()
    private val elsewhere = MockWebServer()

    private val client = OkHttpClient.Builder().sendingTheHomeToken().build()

    @Before
    fun start() {
        home.start()
        elsewhere.start()
        HomeServerAuth.use(host = { "localhost" }, token = { "device-token" })
    }

    @After
    fun stop() {
        HomeServerAuth.use(host = { null }, token = { "" })
        home.close()
        elsewhere.close()
    }

    private fun get(url: String) = client.newCall(Request.Builder().url(url).build()).execute().use { it.code }

    private fun elsewhereUrl(path: String) = elsewhere.url(path).newBuilder().host("127.0.0.1").build().toString()

    @Test
    fun `a request to the home host carries the token`() {
        home.enqueue(MockResponse.Builder().code(200).build())

        get(home.url("/stream").toString())

        assertEquals("device-token", home.takeRequest().headers[HomeServerAuth.HEADER])
    }

    @Test
    fun `a redirect from the home host to another host arrives without the token`() {
        elsewhere.enqueue(MockResponse.Builder().code(200).build())
        home.enqueue(MockResponse.Builder().code(302).addHeader("Location", elsewhereUrl("/cdn/file")).build())

        val code = get(home.url("/stream").toString())

        assertEquals(200, code)
        assertEquals("device-token", home.takeRequest().headers[HomeServerAuth.HEADER])
        assertNull(elsewhere.takeRequest().headers[HomeServerAuth.HEADER])
    }

    @Test
    fun `a redirect that stays on the home host keeps the token`() {
        home.enqueue(MockResponse.Builder().code(302).addHeader("Location", home.url("/next").toString()).build())
        home.enqueue(MockResponse.Builder().code(200).build())

        get(home.url("/stream").toString())

        assertEquals("device-token", home.takeRequest().headers[HomeServerAuth.HEADER])
        assertEquals("device-token", home.takeRequest().headers[HomeServerAuth.HEADER])
    }

    @Test
    fun `a request that already carries the token to another host has it taken off`() {
        elsewhere.enqueue(MockResponse.Builder().code(200).build())
        val request = Request.Builder().url(elsewhereUrl("/x")).header(HomeServerAuth.HEADER, "device-token").build()

        client.newCall(request).execute().close()

        assertNull(elsewhere.takeRequest().headers[HomeServerAuth.HEADER])
    }
}
