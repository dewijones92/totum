package com.dewijones92.totum.data.home

import com.dewijones92.totum.common.HomeServerAuth
import com.dewijones92.totum.data.backup.BackupItem
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HomeJobsClientTest {

    private val server = MockWebServer()
    private val item = BackupItem("abc", "src", "A film", contentKind = "STANDARD", playbackType = "VIDEO")

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun client(token: String = "device") =
        HomeJobsClient(OkHttpClient(), server.url("/fetch").toString().trimEnd('/'), { token })

    @Test
    fun `a job is started with the token and answers with the id it is filed under`() = runTest {
        server.enqueue(MockResponse.Builder().code(202).body("""{"ok":true,"id":"canonical"}""").build())
        assertEquals(
            HomeAnswer.Ok("canonical"),
            client().start(HomeJobRequest(item, audioOnly = false, maxHeight = 720))
        )
        val sent = server.takeRequest()
        assertEquals("/fetch/jobs", sent.url.encodedPath)
        assertEquals("device", sent.headers[HomeServerAuth.HEADER])
        assertTrue(sent.body?.utf8().orEmpty().contains("\"maxHeight\":720"))
    }

    @Test
    fun `a refusal carries the home server's own words`() = runTest {
        server.enqueue(
            MockResponse.Builder().code(507).body("""{"error":"❌ Not enough space on the Pi (12 GB free)"}""").build()
        )
        assertEquals(
            HomeAnswer.No("❌ Not enough space on the Pi (12 GB free)", 507),
            client().start(HomeJobRequest(item, true))
        )
    }

    @Test
    fun `a plain error from the home server is wrapped in an emoji-led line on the phone`() = runTest {
        server.enqueue(MockResponse.Builder().code(400).body("""{"error":"unreadable request"}""").build())
        assertEquals(
            HomeAnswer.No("❌ unreadable request", 400),
            client().start(HomeJobRequest(item, true))
        )
        assertEquals("⚠️ keeps its own", com.dewijones92.totum.data.home.HomePiCall.emojiLed("⚠️ keeps its own"))
    }

    @Test
    fun `ids with odd characters travel in the query, untouched by nginx`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("{}").build())
        client().cancel("a b/c?d")
        val sent = server.takeRequest().url
        assertEquals("/fetch/jobs", sent.encodedPath)
        assertEquals("a b/c?d", sent.queryParameter("id"))
        assertEquals("x/y", client().fileUrl("x/y").value.toHttpUrl().queryParameter("id"))
    }

    @Test
    fun `no token, no request`() = runTest {
        assertTrue(client(token = "").jobs() is HomeAnswer.No)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `the job list is read`() = runTest {
        server.enqueue(
            MockResponse.Builder().code(200).body("""{"jobs":[],"freeBytes":5,"youTubeSignedIn":true}""").build()
        )
        assertEquals(HomeAnswer.Ok(HomeJobs(emptyList(), 5, true)), client().jobs())
    }
}
