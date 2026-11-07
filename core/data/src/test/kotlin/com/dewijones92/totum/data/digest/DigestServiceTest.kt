package com.dewijones92.totum.data.digest

import com.dewijones92.totum.data.ai.HttpAiJobs
import com.dewijones92.totum.data.ask.AskOutcome
import com.dewijones92.totum.data.transcript.Cue
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DigestServiceTest {

    private val server = MockWebServer()
    private val store = InMemoryDigestStore()
    private val base get() = server.url("/fetch").toString().trimEnd('/')
    private val subject = DigestSubject("vid1", "A talk", "Someone", 1200)
    private val cues = listOf(
        Cue(0, 4000, "welcome to the talk about sleep"),
        Cue(30_000, 34_000, "the bedroom should be cold at night"),
    )

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun service(online: Boolean = true) = DigestService(
        jobs = { if (online) HttpAiJobs(OkHttpClient(), base, { "t" }, pollMs = 1) else null },
        store = store,
        clock = { 5L },
    )

    private fun done(result: String) =
        MockResponse.Builder().code(200).body("""{"id":"j","state":"done","elapsedMs":9,"result":$result}""").build()

    @Test
    fun `a summary is fetched once, kept on disk and used again without a call`() = runTest {
        server.enqueue(done("""{"tldr":["Sleep matters."],"chapters":[{"startSec":0,"title":"Start"}]}"""))
        val first = service().summarise(subject, cues)
        assertEquals("Sleep matters.", (first as AskOutcome.Answered).value.tldr.single())
        assertEquals(1, server.requestCount)
        val sent = server.takeRequest().body!!.utf8()
        assertTrue(sent, "[0:30] the bedroom should be cold at night" in sent)
        val again = service().summarise(subject, cues)
        assertTrue(again is AskOutcome.Answered)
        assertEquals(1, server.requestCount)
        assertEquals(setOf("vid1"), store.load().keys)
    }

    @Test
    fun `asking again by hand makes a new call`() = runTest {
        server.enqueue(done("""{"tldr":["One."],"chapters":[]}"""))
        server.enqueue(done("""{"tldr":["Two."],"chapters":[]}"""))
        service().summarise(subject, cues)
        val forced = service().summarise(subject, cues, force = true)
        assertEquals("Two.", (forced as AskOutcome.Answered).value.tldr.single())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `captions that changed since the summary are not matched to it`() = runTest {
        server.enqueue(done("""{"tldr":["One."],"chapters":[]}"""))
        service().summarise(subject, cues)
        assertEquals(null, service().saved(subject, cues + Cue(60_000, 64_000, "and one more line")))
    }

    @Test
    fun `no home server is a failure that says so and makes no call`() = runTest {
        val outcome = service(online = false).summarise(subject, cues)
        assertTrue(outcome is AskOutcome.Failed)
        assertTrue(service(online = false).ask(subject, cues, "how cold?") is AskOutcome.Failed)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a question is sent with the parts of a long transcript that match it`() = runTest {
        val filler = { n: Int -> "filler words about nothing in particular number $n " + "x".repeat(300) }
        val long = (0 until 400).map { Cue(it * 25_000L, it * 25_000L + 4000, filler(it)) } +
            Cue(10_000_000, 10_004_000, "and the lobotomy was introduced by Freeman in the thirties")
        server.enqueue(done("""{"found":true,"answer":"Freeman did.","moments":[]}"""))
        service().ask(subject, long, "Who introduced the lobotomy?")
        val sent = server.takeRequest().body!!.utf8()
        assertTrue(sent, "introduced by Freeman" in sent)
        assertTrue(sent.length < 140_000)
    }
}
