package com.dewijones92.totum.diagnostics

import com.dewijones92.totum.common.HomeServerAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DiagnosticsUploaderTest {
    private val server = MockWebServer()

    @get:Rule val folder = TemporaryFolder()

    private val dumpBytes = byteArrayOf(0x1f, 0x8b.toByte(), 1, 2, 3)
    private var dumpAsked = 0

    @Before fun start() {
        server.start()
        HomeServerAuth.use({ "totum.home.test" }, { "device-token" }, { server.hostName })
    }

    @After fun stop() {
        server.close()
        HomeServerAuth.use({ null }, { "" })
    }

    private fun report(kind: String): File = folder.newFile("${System.nanoTime()}.json").also {
        it.writeText("""{"kind":"$kind","note":"probe"}""")
    }

    private fun uploader(
        files: List<File>,
        signedIn: Boolean = true,
        sendToHome: Boolean = true,
        underInstrumentation: Boolean = false,
        dump: suspend () -> ByteArray? = { dumpAsked++; dumpBytes },
    ) = DiagnosticsUploader(
        pending = { files.filter(File::exists) },
        client = OkHttpClient(),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        endpoint = { server.url("/ingest").toString() },
        onAnEmulator = { false },
        underInstrumentation = { underInstrumentation },
        signedIn = { signedIn },
        sendToHome = { sendToHome },
        dump = dump,
    )

    @Test
    fun `a signed-in phone with the setting on sends the report, then its state, and keeps nothing`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(202).build())
        server.enqueue(MockResponse.Builder().code(202).build())
        val file = report("diagnostics")

        uploader(listOf(file)).upload(becauseSomeoneAsked = false)

        assertFalse(file.exists())
        val first = server.takeRequest()
        val second = server.takeRequest()
        assertEquals("/ingest", first.url.encodedPath)
        assertEquals("/ingest/state", second.url.encodedPath)
        assertEquals("device-token", second.headers[HomeServerAuth.HEADER])
        assertEquals(dumpBytes.toList(), second.body?.toByteArray()?.toList())
        assertEquals("application/gzip", second.headers["Content-Type"])
    }

    @Test
    fun `with the setting off nothing is posted automatically, and the report waits on the phone`() = runBlocking {
        val file = report("crash")

        uploader(listOf(file), sendToHome = false).upload(becauseSomeoneAsked = false)

        assertEquals(0, server.requestCount)
        assertTrue(file.exists())
        assertEquals(0, dumpAsked)
    }

    @Test
    fun `with the setting off a person who taps send to my Pi still sends`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(202).build())
        server.enqueue(MockResponse.Builder().code(202).build())
        val file = report("crash")

        uploader(listOf(file), sendToHome = false).upload(becauseSomeoneAsked = true)

        assertFalse(file.exists())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a phone that is not signed in sends nothing, state included`() = runBlocking {
        val file = report("diagnostics")

        uploader(listOf(file), signedIn = false).upload(becauseSomeoneAsked = true)

        assertEquals(0, server.requestCount)
        assertTrue(file.exists())
        assertEquals(0, dumpAsked)
    }

    @Test
    fun `a test run sends nothing`() = runBlocking {
        val file = report("diagnostics")

        uploader(listOf(file), underInstrumentation = true).upload(becauseSomeoneAsked = true)

        assertEquals(0, server.requestCount)
        assertTrue(file.exists())
    }

    @Test
    fun `a handled non-fatal report goes without a state dump`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(202).build())

        uploader(listOf(report("non-fatal"))).upload(becauseSomeoneAsked = false)

        assertEquals(1, server.requestCount)
        assertEquals(0, dumpAsked)
    }

    @Test
    fun `a dump that cannot be gathered does not stop the report being sent and deleted`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(202).build())
        val file = report("diagnostics")

        uploader(listOf(file), dump = { error("the database is closed") }).upload(becauseSomeoneAsked = false)

        assertFalse(file.exists())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a sink that refuses the state leaves the report sent and the state unsent`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(202).build())
        server.enqueue(MockResponse.Builder().code(500).build())
        val file = report("diagnostics")

        uploader(listOf(file)).upload(becauseSomeoneAsked = false)

        assertFalse(file.exists())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `send my state on its own posts only the dump`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(202).build())

        val result = uploader(emptyList()).sendState()

        assertEquals(StateSend.Sent(dumpBytes.size), result)
        assertEquals(1, server.requestCount)
        assertEquals("/ingest/state", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `send my state is refused when not signed in, in a test run, and says why`() = runBlocking {
        assertEquals(StateSend.NotSignedIn, uploader(emptyList(), signedIn = false).sendState())
        assertEquals(StateSend.TestRun, uploader(emptyList(), underInstrumentation = true).sendState())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `send my state says when the dump could not be gathered or the sink said no`() = runBlocking {
        assertEquals(StateSend.NotGathered, uploader(emptyList(), dump = { null }).sendState())
        assertEquals(StateSend.NotGathered, uploader(emptyList(), dump = { error("boom") }).sendState())
        server.enqueue(MockResponse.Builder().code(503).build())
        assertEquals(StateSend.Refused, uploader(emptyList()).sendState())
    }
}
