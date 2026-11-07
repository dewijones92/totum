package com.dewijones92.totum.diagnostics

import com.dewijones92.totum.common.HomeServerAuth
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReportSinkTest {
    private val server = MockWebServer()

    @Before fun start() = server.start()

    @After fun stop() {
        server.close()
        HomeServerAuth.use({ null }, { "" })
    }

    private fun sink(endpoint: () -> String?) = ReportSink(OkHttpClient(), endpoint)

    @Test
    fun `a report carries the token to the sink of the configured home server`() {
        HomeServerAuth.use({ "totum.home.test" }, { "device-token" }, { server.hostName })
        server.enqueue(MockResponse.Builder().code(200).build())

        val sent = sink { server.url("/ingest").toString() }.post("{}", "diagnostics")

        assertTrue(sent)
        assertEquals("device-token", server.takeRequest().headers[HomeServerAuth.HEADER])
    }

    @Test
    fun `an endpoint that is not the configured sink gets nothing, not even the token`() {
        HomeServerAuth.use({ "totum.home.test" }, { "device-token" }, { "crashlog.home.test" })

        val sent = sink { server.url("/ingest").toString() }.post("{}", "diagnostics")

        assertFalse(sent)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `signed out sends nothing`() {
        HomeServerAuth.use({ "totum.home.test" }, { "" }, { server.hostName })

        val sent = sink { server.url("/ingest").toString() }.post("{}", "diagnostics")

        assertFalse(sent)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `the body goes as JSON by POST and a sink that refuses it is a failure, not a success`() {
        HomeServerAuth.use({ "totum.home.test" }, { "device-token" }, { server.hostName })
        server.enqueue(MockResponse.Builder().code(500).build())

        val sent = sink { server.url("/ingest").toString() }.post("""{"a":1}""", "diagnostics")

        assertFalse(sent)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("""{"a":1}""", request.body?.utf8())
        assertTrue(request.headers["Content-Type"].orEmpty().startsWith("application/json"))
    }

    @Test
    fun `a sink that cannot be reached is a failure and does not throw`() {
        HomeServerAuth.use({ "totum.home.test" }, { "device-token" }, { server.hostName })
        val address = server.url("/ingest").toString()
        server.close()

        assertFalse(sink { address }.post("{}", "diagnostics"))
    }

    @Test
    fun `no home server means no endpoint and nothing is sent`() {
        HomeServerAuth.use({ null }, { "device-token" })

        assertNull(HomeServerAuth.reportEndpoint())
        assertFalse(ReportSink(OkHttpClient()).post("{}", "diagnostics"))
        assertEquals(0, server.requestCount)
    }
}
