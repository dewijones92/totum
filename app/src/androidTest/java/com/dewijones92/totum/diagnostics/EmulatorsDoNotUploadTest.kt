package com.dewijones92.totum.diagnostics

import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.common.Breadcrumbs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * A test run must not post to the diagnostics sink — but a person tapping "Send" always may.
 *
 * Every instrumented run launches the app, which uploads whatever is pending, so the sink filled
 * with test output: in September **100 of 106 reports came from an emulator and six from the
 * phone**. That is the "26 unread reports" failure the triage feature exists to prevent, recreated
 * at a much higher rate, and it buries the ones that are somebody actually telling us something.
 *
 * Three things are guarded, and the middle one is the one a careless version of this test misses:
 * the automatic upload is suppressed here, a hand-sent one is NOT (an emulator is where this app
 * gets debugged), and **this very emulator is recognised as one** by the real detector rather than
 * a double — if a future system image stops matching it, uploading silently resumes.
 */
class EmulatorsDoNotUploadTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val written = mutableListOf<File>()

    @Before
    fun clearTheTrail(): Unit = Breadcrumbs.clear()

    /** Its own litter, removed. The store is capped at 50 and `adb`-read by hand when debugging. */
    @After
    fun tearDown() {
        scope.cancel()
        written.forEach { it.delete() }
    }

    private fun aPendingReport() {
        written += DiagnosticsStore.write(
            context,
            JSONObject().put("kind", "diagnostics").put("note", "EmulatorsDoNotUploadTest probe"),
        )
    }

    private suspend fun awaitTrail(fragment: String): Boolean =
        withTimeoutOrNull(TIMEOUT_MS) {
            while (Breadcrumbs.snapshot().none { fragment in it.message }) delay(POLL_MS)
            true
        } == true

    private fun uploader(onAnEmulator: (() -> Boolean)? = null) =
        onAnEmulator
            ?.let { DiagnosticsUploader(context, OkHttpClient(), scope, REFUSED, it) }
            ?: DiagnosticsUploader(context, OkHttpClient(), scope, REFUSED)

    /** The detector, against the thing it exists to detect — with no double in the way. */
    @Test
    fun thisEmulatorIsRecognisedAsOne() = runBlocking {
        aPendingReport()
        val before = DiagnosticsStore.pending(context).toSet()

        uploader().uploadPending()

        assertTrue(
            "the emulator running this test was not recognised as an emulator, so it would upload",
            awaitTrail("suppressed"),
        )
        assertEquals("a suppressed upload must not delete anything", before, DiagnosticsStore.pending(context).toSet())
    }

    /**
     * And a real device still would. Asserted on a fragment that the SUPPRESSED line cannot also
     * contain: "not uploading"/"suppressed" both contain "uploading", so matching that alone made
     * this case pass whether or not the guard fired — it could not fail, which is worse than absent.
     */
    @Test
    fun aRealDeviceStillUploads() = runBlocking {
        aPendingReport()

        uploader(onAnEmulator = { false }).uploadPending()

        assertTrue(
            "nothing tried to upload, so the guard has disabled the pipeline rather than narrowed it",
            awaitTrail("pending report"),
        )
    }

    /** A person asked. It goes, on an emulator, using the REAL detector. */
    @Test
    fun aHandSentReportGoesEvenFromAnEmulator() = runBlocking {
        aPendingReport()

        uploader().sendDiagnosticsNow()

        assertTrue(
            "the Settings button did nothing on an emulator, while the UI says Sent",
            awaitTrail("pending report"),
        )
    }

    private companion object {
        /**
         * Connection REFUSED, instantly — not a black hole. A blackholed address (192.0.2.1) costs
         * OkHttp's ten-second connect timeout per pending file, on an uncancellable blocking socket,
         * spilling into whatever test runs next.
         */
        const val REFUSED = "http://127.0.0.1:1/report"
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 50L
    }
}
