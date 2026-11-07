package com.dewijones92.totum.data.ai

import com.dewijones92.totum.common.HomeServerAuth
import com.dewijones92.totum.data.ask.AskOutcome
import com.dewijones92.totum.data.ask.JOB_LOST_MESSAGE
import com.dewijones92.totum.data.spoiler.SpoilerAnswer
import com.dewijones92.totum.data.spoiler.SpoilerQuery
import com.dewijones92.totum.data.spoiler.SpoilerRequest
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HttpAiJobsTest {

    private val server = MockWebServer()
    private val request = SpoilerRequest(listOf(SpoilerQuery("a", "Alcaraz beats Sinner", "Tennis TV")))

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun jobs(token: String = "device-token") =
        HttpAiJobs(OkHttpClient(), server.url("/fetch").toString().trimEnd('/'), { token }, pollMs = 1)

    private fun status(state: String, extra: String = "") =
        MockResponse.Builder().code(200).body("""{"id":"j1","state":"$state","elapsedMs":700$extra}""").build()

    private suspend fun run(client: HttpAiJobs = jobs()): AskOutcome<SpoilerAnswer> = client.run(
        AiTasks.SPOILER,
        request,
        SpoilerRequest.serializer(),
        SpoilerAnswer.serializer(),
    )

    @Test
    fun `a task is posted, polled and answered, carrying the device token`() = runTest {
        server.enqueue(status("running"))
        server.enqueue(status("running"))
        val answer = ""","result":{"verdicts":[{"id":"a","spoils":true,"safeTitle":"Alcaraz v Sinner"}]}"""
        server.enqueue(status("done", answer))
        val outcome = run()
        assertEquals("Alcaraz v Sinner", (outcome as AskOutcome.Answered).value.verdicts.single().safeTitle)
        val post = server.takeRequest()
        assertEquals("POST", post.method)
        assertEquals("/fetch/ai/spoiler", post.url.encodedPath)
        assertEquals("device-token", post.headers[HomeServerAuth.HEADER])
        assertTrue(post.body!!.utf8().contains("Alcaraz beats Sinner"))
        assertEquals("/fetch/ai/job/j1", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `a failed job says why`() = runTest {
        server.enqueue(status("running"))
        server.enqueue(status("failed", ""","error":"claude said no""""))
        assertEquals(AskOutcome.Failed("❌ claude said no"), run())
    }

    @Test
    fun `an answer that does not fit the shape is a failure, not a crash`() = runTest {
        server.enqueue(status("done", ""","result":{"nonsense":1}"""))
        assertTrue(run() is AskOutcome.Failed)
    }

    @Test
    fun `no token means no request at all`() = runTest {
        assertTrue(run(jobs(token = "")) is AskOutcome.Failed)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a poll lost to a bad signal is just tried again`() = runTest {
        server.enqueue(status("running"))
        server.enqueue(MockResponse.Builder().code(502).body("bad gateway").build())
        server.enqueue(status("done", ""","result":{"verdicts":[]}"""))
        assertTrue(run() is AskOutcome.Answered)
    }

    @Test
    fun `a job the Pi no longer knows ends at once with a plain message`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.method == "POST") status("running") else MockResponse.Builder().code(404).body("{}").build()
        }
        val outcome = HttpAiJobs(
            OkHttpClient(),
            server.url("/fetch").toString().trimEnd('/'),
            { "device-token" },
            pollMs = 1,
            giveUpMs = 1_500,
        ).run(AiTasks.SPOILER, request, SpoilerRequest.serializer(), SpoilerAnswer.serializer())

        assertEquals(AskOutcome.Failed(JOB_LOST_MESSAGE), outcome)
        assertTrue("polled ${server.requestCount} times", server.requestCount <= 3)
    }
}
