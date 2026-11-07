package com.dewijones92.totum.data.home

import com.dewijones92.totum.common.HomeServerAuth
import com.dewijones92.totum.data.backup.BackupItem
import com.dewijones92.totum.data.targets.QueueMode
import com.dewijones92.totum.data.targets.Requester
import com.dewijones92.totum.data.targets.TargetAction
import com.dewijones92.totum.data.targets.TargetControl
import com.dewijones92.totum.data.targets.TargetKind
import com.dewijones92.totum.data.targets.TargetQueueRequest
import com.dewijones92.totum.data.targets.TargetsJson
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TargetsClientTest {

    private val server = MockWebServer()
    private val item = BackupItem("abc", "src", "Bluey", contentKind = "STANDARD", playbackType = "AUDIO")

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun client(token: String = "device") =
        TargetsClient(OkHttpClient(), server.url("/fetch").toString().trimEnd('/'), { token })

    private val state = """{"target":{"id":"lg-tv","label":"LG TV","kind":"TV"},"playing":true,"volume":40}"""

    @Test
    fun `the list is a GET with the token and each target is read with its kind`() = runTest {
        server.enqueue(
            MockResponse.Builder().code(200).body(
                """{"targets":[$state,{"target":{"id":"bar","label":"Soundbar"}}]}""",
            ).build(),
        )

        val answer = client().targets() as HomeAnswer.Ok

        val sent = server.takeRequest()
        assertEquals("GET", sent.method)
        assertEquals("/fetch/targets", sent.url.encodedPath)
        assertEquals("device", sent.headers[HomeServerAuth.HEADER])
        assertEquals(listOf("lg-tv", "bar"), answer.value.targets.map { it.target.id })
        assertEquals(listOf(TargetKind.TV, TargetKind.SPEAKER), answer.value.targets.map { it.target.kind })
        assertTrue(answer.value.targets[0].playing)
    }

    @Test
    fun `a queue goes to the target's own path with its mode, items and who sent it`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body(state).build())

        val answer = client().queue(
            "lg tv",
            TargetQueueRequest(
                listOf(item),
                QueueMode.PLAY,
                from = Requester("phone-1", "Dewi's Pixel"),
                startAtMs = 4_000,
                startItemId = "abc",
            ),
        )

        val sent = server.takeRequest()
        assertEquals("POST", sent.method)
        assertEquals("/fetch/targets/queue", sent.url.encodedPath)
        assertEquals("lg tv", sent.url.queryParameter("id"))
        assertEquals("application/json; charset=utf-8", sent.headers["Content-Type"])
        val body = TargetsJson.decodeFromString(TargetQueueRequest.serializer(), sent.body!!.utf8())
        assertEquals(QueueMode.PLAY, body.mode)
        assertEquals("phone-1", body.from?.id)
        assertEquals(4_000L, body.startAtMs)
        assertEquals("abc", body.startItemId)
        assertTrue((answer as HomeAnswer.Ok).value.playing)
    }

    @Test
    fun `a control goes to the control path and the new state comes back`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body(state).build())

        val answer = client().control("lg-tv", TargetControl(TargetAction.VOLUME, volume = 40)) as HomeAnswer.Ok

        val sent = server.takeRequest()
        assertEquals("/fetch/targets/control", sent.url.encodedPath)
        assertEquals("lg-tv", sent.url.queryParameter("id"))
        val body = TargetsJson.decodeFromString(TargetControl.serializer(), sent.body!!.utf8())
        assertEquals(TargetAction.VOLUME, body.action)
        assertEquals(40, body.volume)
        assertEquals(40, answer.value.volume)
    }

    @Test
    fun `a refusal from the Pi keeps its words and its status`() = runTest {
        server.enqueue(
            MockResponse.Builder().code(422).body("""{"error":"❌ Nothing in that was playable on LG TV"}""").build(),
        )
        server.enqueue(MockResponse.Builder().code(404).body("""{"error":"❌ No target sofa"}""").build())

        assertEquals(
            HomeAnswer.No("❌ Nothing in that was playable on LG TV", 422),
            client().queue("lg-tv", TargetQueueRequest(emptyList(), QueueMode.REPLACE)),
        )
        assertEquals(
            HomeAnswer.No("❌ No target sofa", 404),
            client().control("sofa", TargetControl(TargetAction.PAUSE)),
        )
    }

    @Test
    fun `with no token nothing is sent to any of the three`() = runTest {
        val signedOut = client(token = " ")

        assertEquals(HomeAnswer.No(HomePiCall.NOT_SIGNED_IN), signedOut.targets())
        assertEquals(
            HomeAnswer.No(HomePiCall.NOT_SIGNED_IN),
            signedOut.queue("x", TargetQueueRequest(emptyList(), QueueMode.END)),
        )
        assertEquals(HomeAnswer.No(HomePiCall.NOT_SIGNED_IN), signedOut.control("x", TargetControl(TargetAction.STOP)))
        assertEquals(0, server.requestCount)
    }
}
