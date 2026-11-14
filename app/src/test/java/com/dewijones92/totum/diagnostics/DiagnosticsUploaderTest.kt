package com.dewijones92.totum.diagnostics

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HomeServerAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class DiagnosticsUploaderTest {
    private val server = MockWebServer()

    @get:Rule val folder = TemporaryFolder()

    private val dumpBytes = byteArrayOf(0x1f, 0x8b.toByte(), 1, 2, 3)
    private var dumpAsked = 0
    private val logged = mutableListOf<String>()
    private var sinkBefore: Diag.Sink? = null

    @Before fun start() {
        server.start()
        HomeServerAuth.use({ "totum.home.test" }, { "device-token" }, { server.hostName })
        sinkBefore = Diag.sink
        Diag.sink = Diag.Sink { _, tag, message, _ -> if (tag == "diagnostics") logged += message }
    }

    @After fun stop() {
        server.close()
        HomeServerAuth.use({ null }, { "" })
        sinkBefore?.let { Diag.sink = it }
    }

    private fun report(kind: String): File = folder.newFile("${System.nanoTime()}.json").also {
        it.writeText("""{"kind":"$kind","note":"probe"}""")
    }

    private fun acceptReport(id: String? = REPORT_ID) = server.enqueue(
        MockResponse.Builder().code(202).body(if (id == null) "" else """{"id":"$id"}""").build(),
    )

    private fun answer(code: Int) = server.enqueue(MockResponse.Builder().code(code).build())

    private fun receipt(file: File) = ReportReceipt(file).read()

    private fun next(): RecordedRequest = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS)) {
        "the phone sent fewer requests than the test expected"
    }

    private fun requests(count: Int): List<RecordedRequest> = List(count) { next() }

    private fun uploader(
        files: List<File>,
        signedIn: Boolean = true,
        sendToHome: Boolean = true,
        underInstrumentation: Boolean = false,
        stateLimitBytes: Long = 31L * 1024 * 1024,
        dump: suspend () -> ByteArray? = {
            dumpAsked++
            dumpBytes
        },
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
        stateLimitBytes = stateLimitBytes,
    )

    @Test
    fun `a signed-in phone sends the report, then its state linked to that report, and keeps nothing`() =
        runBlocking {
            acceptReport()
            answer(202)
            val file = report("diagnostics")

            val outcome = uploader(listOf(file)).upload(becauseSomeoneAsked = false)

            assertFalse(file.exists())
            assertNull(receipt(file))
            assertEquals(UploadOutcome(sent = 1, kept = 0), outcome)
            val (first, second) = requests(2)
            assertEquals("/ingest", first.url.encodedPath)
            assertEquals("/ingest/state", second.url.encodedPath)
            assertEquals(REPORT_ID, second.headers[ReportSink.REPORT_ID_HEADER])
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
        acceptReport()
        answer(202)
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
        acceptReport()
        val file = report("non-fatal")

        val outcome = uploader(listOf(file)).upload(becauseSomeoneAsked = false)

        assertEquals(1, server.requestCount)
        assertEquals(0, dumpAsked)
        assertFalse(file.exists())
        assertNull(receipt(file))
        assertEquals(UploadOutcome(sent = 1, kept = 0), outcome)
    }

    @Test
    fun `a state that fails keeps the report and its receipt pending together`() = runBlocking {
        acceptReport()
        answer(503)
        val file = report("diagnostics")

        val outcome = uploader(listOf(file)).upload(becauseSomeoneAsked = false)

        assertTrue(file.exists())
        assertEquals(Receipt(REPORT_ID, stateAttempts = 1), receipt(file))
        assertEquals(UploadOutcome(sent = 0, kept = 1), outcome)
        assertTrue(logged.any { "HTTP 503" in it && REPORT_ID in it && "kept pending" in it })
    }

    @Test
    fun `the next run sends only the state, linked to the same report, and the report is not posted again`() =
        runBlocking {
            acceptReport()
            answer(503)
            val file = report("diagnostics")
            uploader(listOf(file)).upload(becauseSomeoneAsked = false)
            requests(2)
            answer(202)

            val outcome = uploader(listOf(file)).upload(becauseSomeoneAsked = false)

            assertEquals(1, server.requestCount - 2)
            val retried = next()
            assertEquals("/ingest/state", retried.url.encodedPath)
            assertEquals(REPORT_ID, retried.headers[ReportSink.REPORT_ID_HEADER])
            assertFalse(file.exists())
            assertNull(receipt(file))
            assertEquals(UploadOutcome(sent = 1, kept = 0), outcome)
        }

    @Test
    fun `nothing is sent twice once the state has landed`() = runBlocking {
        acceptReport()
        answer(202)
        val file = report("crash")
        val phone = uploader(listOf(file))
        phone.upload(becauseSomeoneAsked = false)

        phone.upload(becauseSomeoneAsked = false)
        phone.upload(becauseSomeoneAsked = true)

        assertEquals(2, server.requestCount)
        assertEquals(1, dumpAsked)
    }

    @Test
    fun `a dump that cannot be gathered keeps the report pending, then the state goes alone`() = runBlocking {
        acceptReport()
        val file = report("diagnostics")

        uploader(listOf(file), dump = { error("the database is closed") }).upload(becauseSomeoneAsked = false)

        assertTrue(file.exists())
        assertEquals(1, server.requestCount)
        assertEquals(Receipt(REPORT_ID, stateAttempts = 1), receipt(file))
        answer(202)

        uploader(listOf(file)).upload(becauseSomeoneAsked = false)

        assertEquals(2, server.requestCount)
        assertFalse(file.exists())
    }

    @Test
    fun `two reports in one run each bring a state linked to their own id, from one dump`() = runBlocking {
        acceptReport("20261114T120000-aaaaaaaa")
        answer(202)
        acceptReport("20261114T120001-bbbbbbbb")
        answer(202)
        val files = listOf(report("crash"), report("diagnostics"))

        uploader(files).upload(becauseSomeoneAsked = false)

        val posted = requests(4)
        assertEquals(
            listOf("20261114T120000-aaaaaaaa", "20261114T120001-bbbbbbbb"),
            posted.filter { it.url.encodedPath == "/ingest/state" }.map { it.headers[ReportSink.REPORT_ID_HEADER] },
        )
        assertEquals(1, dumpAsked)
        assertTrue(files.none(File::exists))
    }

    @Test
    fun `a state the sink refuses outright is not retried, and the log says the HTTP code`() = runBlocking {
        acceptReport()
        answer(413)
        val file = report("diagnostics")

        val outcome = uploader(listOf(file)).upload(becauseSomeoneAsked = false)

        assertFalse(file.exists())
        assertNull(receipt(file))
        assertEquals(UploadOutcome(sent = 1, kept = 0), outcome)
        assertTrue(logged.any { "HTTP 413" in it && "too large" in it && REPORT_ID in it && "not retried" in it })
        uploader(listOf(file)).upload(becauseSomeoneAsked = false)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a refusal that is not about size is logged with its code and is not retried either`() = runBlocking {
        acceptReport()
        answer(400)
        val file = report("crash")

        uploader(listOf(file)).upload(becauseSomeoneAsked = false)

        assertFalse(file.exists())
        assertTrue(logged.any { "refused" in it && "HTTP 400" in it && "not retried" in it })
    }

    @Test
    fun `a state that keeps failing is given up on after its attempts, loudly, and the report goes`() = runBlocking {
        acceptReport()
        val file = report("diagnostics")
        repeat(DiagnosticsUploader.MAX_STATE_ATTEMPTS) { attempt ->
            if (attempt > 0) assertTrue("still pending before attempt ${attempt + 1}", file.exists())
            if (attempt == 0) answer(503) else answer(502)
            uploader(listOf(file)).upload(becauseSomeoneAsked = false)
        }

        assertFalse(file.exists())
        assertNull(receipt(file))
        assertTrue(
            logged.any {
                "giving up" in it && REPORT_ID in it && "${DiagnosticsUploader.MAX_STATE_ATTEMPTS} attempts" in it
            },
        )
        assertEquals(1 + DiagnosticsUploader.MAX_STATE_ATTEMPTS, server.requestCount)
    }

    @Test
    fun `a state the phone itself finds too big is not posted and says so`() = runBlocking {
        acceptReport()
        val file = report("diagnostics")

        val outcome = uploader(listOf(file), stateLimitBytes = 4).upload(becauseSomeoneAsked = false)

        assertEquals(1, server.requestCount)
        assertFalse(file.exists())
        assertEquals(UploadOutcome(sent = 1, kept = 0), outcome)
        assertTrue(logged.any { "state too large" in it && "${dumpBytes.size} bytes" in it && "not sent" in it })
    }

    @Test
    fun `a reply with no report id sends the state with no id, as an older server wants it`() = runBlocking {
        acceptReport(id = null)
        answer(202)
        val file = report("diagnostics")

        uploader(listOf(file)).upload(becauseSomeoneAsked = false)

        val (_, state) = requests(2)
        assertEquals("/ingest/state", state.url.encodedPath)
        assertNull(state.headers[ReportSink.REPORT_ID_HEADER])
        assertFalse(file.exists())
        assertTrue(logged.any { "no id" in it })
    }

    @Test
    fun `a receipt the phone cannot read still never posts the report again`() = runBlocking {
        val file = report("diagnostics")
        File(file.parentFile, file.name + ".receipt").writeText("\u0000\u0001 not a receipt")
        answer(202)

        uploader(listOf(file)).upload(becauseSomeoneAsked = false)

        assertEquals(1, server.requestCount)
        assertEquals("/ingest/state", next().url.encodedPath)
        assertFalse(file.exists())
    }

    @Test
    fun `a pending state is not posted from a phone that is signed out, and the receipt waits`() = runBlocking {
        acceptReport()
        answer(503)
        val file = report("crash")
        uploader(listOf(file)).upload(becauseSomeoneAsked = false)
        requests(2)

        uploader(listOf(file), signedIn = false).upload(becauseSomeoneAsked = true)

        assertEquals(2, server.requestCount)
        assertEquals(Receipt(REPORT_ID, stateAttempts = 1), receipt(file))
    }

    @Test
    fun `send my state on its own posts only the dump, linked to nothing`() = runBlocking {
        answer(202)

        val result = uploader(emptyList()).sendState()

        assertEquals(StateSend.Sent(dumpBytes.size), result)
        assertEquals(1, server.requestCount)
        val posted = next()
        assertEquals("/ingest/state", posted.url.encodedPath)
        assertNull(posted.headers[ReportSink.REPORT_ID_HEADER])
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
        answer(503)
        assertEquals(StateSend.Refused, uploader(emptyList()).sendState())
    }

    private companion object {
        const val REPORT_ID = "20261114T120000-abcd1234"
    }
}
