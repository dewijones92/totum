package com.dewijones92.totum.data.ask

import com.dewijones92.totum.common.HomeServerAuth
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HttpAskServiceTest {

    private val server = MockWebServer()
    private val request = AskRequest(listOf(AskTurn("a Gruffalo film")))

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun service(token: String = "device-token") =
        HttpAskService(OkHttpClient(), server.url("/fetch").toString().trimEnd('/'), { token }, pollMs = 1)

    private fun job(state: String, extra: String = "") =
        MockResponse.Builder().code(200).body("""{"id":"j1","state":"$state","elapsedMs":900$extra}""").build()

    private val answer = ""","ask":{"suggestions":[""" +
        """{"kind":"film","title":"Zog","year":2018,"why":"dragons","searchWords":"Zog 2018"}]}"""

    @Test
    fun `an ask is started, polled and answered, carrying the device token`() = runTest {
        server.enqueue(job("running"))
        server.enqueue(job("running"))
        server.enqueue(job("done", answer))
        val progress = mutableListOf<Long>()
        val outcome = service().ask(request) { progress += it }
        assertEquals("Zog", (outcome as AskOutcome.Answered).value.suggestions.single().title)
        val post = server.takeRequest()
        assertEquals("POST", post.method)
        assertEquals("/fetch/ask", post.url.encodedPath)
        assertEquals("device-token", post.headers[HomeServerAuth.HEADER])
        assertEquals("/fetch/job/j1", server.takeRequest().url.encodedPath)
        assertEquals(listOf(900L, 900L), progress)
    }

    @Test
    fun `a poll lost to a bad signal is just tried again`() = runTest {
        server.enqueue(job("running"))
        server.enqueue(MockResponse.Builder().code(502).body("bad gateway").build())
        server.enqueue(job("done", answer))
        assertTrue(service().ask(request) is AskOutcome.Answered)
    }

    @Test
    fun `a failed job says why`() = runTest {
        server.enqueue(job("running"))
        server.enqueue(job("failed", ""","error":"claude said no""""))
        assertEquals(AskOutcome.Failed("❌ claude said no"), service().ask(request))
    }

    @Test
    fun `no token means no request at all`() = runTest {
        assertTrue(service(token = "").ask(request) is AskOutcome.Failed)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a job that never finishes is given up on`() = runTest {
        server.enqueue(job("running"))
        repeat(20) { server.enqueue(job("running")) }
        var ticks = 0L
        val slow =
            HttpAskService(
                OkHttpClient(),
                server.url("/fetch").toString().trimEnd('/'),
                { "t" },
                pollMs = 1,
                giveUpMs = 5,
                clock = { ticks++ },
            )
        assertEquals(AskOutcome.Failed("❌ The home server took too long"), slow.ask(request))
    }
}
