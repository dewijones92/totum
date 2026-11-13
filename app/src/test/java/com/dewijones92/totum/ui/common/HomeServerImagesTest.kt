package com.dewijones92.totum.ui.common

import com.dewijones92.totum.common.HomeServerAuth
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class HomeServerImagesTest {

    private val server = MockWebServer()
    private val client = homeServerImageClient()

    @Before
    fun start() {
        server.start()
    }

    @After
    fun forget() {
        HomeServerAuth.use(host = { null }, token = { "" })
        server.close()
    }

    private fun fetch(host: String) {
        server.enqueue(MockResponse.Builder().code(200).build())
        val url = server.url("/jellyfin/image/abc").newBuilder().host(host).build()
        client.newCall(Request.Builder().url(url).build()).execute().close()
    }

    @Test
    fun `a picture from the home server carries this device's token, a picture from anywhere else does not`() {
        HomeServerAuth.use(host = { "localhost" }, token = { "device-token" })

        fetch("localhost")
        fetch("127.0.0.1")

        assertEquals("device-token", server.takeRequest().headers[HomeServerAuth.HEADER])
        assertNull(server.takeRequest().headers[HomeServerAuth.HEADER])
    }

    @Test
    fun `signed out, nothing is sent even to the home server`() {
        HomeServerAuth.use(host = { "localhost" }, token = { "" })

        fetch("localhost")

        assertNull(server.takeRequest().headers[HomeServerAuth.HEADER])
    }
}
