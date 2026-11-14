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

    @Test
    fun `a report that is accepted hands back the sink's reply, id included`() {
        HomeServerAuth.use({ "totum.home.test" }, { "device-token" }, { server.hostName })
        server.enqueue(MockResponse.Builder().code(202).body("""{"id":"20261114T120000-abcd1234"}""").build())

        val reply = sink { server.url("/ingest").toString() }.postReport("{}", "diagnostics")

        assertTrue(reply.accepted)
        assertEquals(202, reply.code)
        assertEquals("20261114T120000-abcd1234", reply.reportId())
    }

    @Test
    fun `a reply that is not a report id reads as none`() {
        assertNull(SinkReply(202, "").reportId())
        assertNull(SinkReply(202, null).reportId())
        assertNull(SinkReply(202, """{"id":"../../etc"}""").reportId())
        assertNull(SinkReply(202, "not json").reportId())
    }

    @Test
    fun `a refusal says whether a retry could help`() {
        assertTrue(SinkReply(null, null).retryable)
        assertTrue(SinkReply(503, null).retryable)
        assertTrue(SinkReply(429, null).retryable)
        assertTrue(SinkReply(408, null).retryable)
        assertFalse(SinkReply(413, null).retryable)
        assertFalse(SinkReply(400, null).retryable)
        assertFalse(SinkReply(404, null).retryable)
        assertFalse(SinkReply(202, null).retryable)
    }

    @Test
    fun `a state names its report in a header when it has one, and sends no header when it has not`() {
        HomeServerAuth.use({ "totum.home.test" }, { "device-token" }, { server.hostName })
        server.enqueue(MockResponse.Builder().code(202).build())
        server.enqueue(MockResponse.Builder().code(202).build())
        val state = sink { server.url("/ingest").toString() }

        state.postState(byteArrayOf(0x1f, 0x8b.toByte()), "state", "20261114T120000-abcd1234")
        state.postState(byteArrayOf(0x1f, 0x8b.toByte()), "state", null)

        assertEquals("20261114T120000-abcd1234", server.takeRequest().headers[ReportSink.REPORT_ID_HEADER])
        assertNull(server.takeRequest().headers[ReportSink.REPORT_ID_HEADER])
    }

    @Test
    fun `a state that is refused hands back its code`() {
        HomeServerAuth.use({ "totum.home.test" }, { "device-token" }, { server.hostName })
        server.enqueue(MockResponse.Builder().code(413).build())

        val reply = sink { server.url("/ingest").toString() }.postState(byteArrayOf(1), "state", null)

        assertFalse(reply.accepted)
        assertEquals(413, reply.code)
    }

    @Test
    fun `a state that cannot be posted has no code at all`() {
        HomeServerAuth.use({ null }, { "device-token" })

        assertNull(ReportSink(OkHttpClient()).postState(byteArrayOf(1), "state", null).code)
    }
}
