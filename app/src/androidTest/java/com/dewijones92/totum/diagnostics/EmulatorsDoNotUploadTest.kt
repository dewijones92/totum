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
 * A test run must not post to the diagnostics sink — by ANY route, including a hand-send.
 *
 * In September **100 of 106 reports on the sink came from an emulator and six from Dewi's phone**:
 * the "26 unread reports" failure the triage feature exists to prevent, recreated at a much higher
 * rate, burying the ones that are somebody actually telling us something.
 *
 * The first version of this guard covered only the automatic launch-time upload and achieved close
 * to nothing, because the tests doing the uploading are HAND-SENDS — `DiagnosticsContentTest` and
 * `DiagnosticsNoteBoxTest` call `sendDiagnostics()`, the same seam the Settings button calls. So
 * the discriminator here is **instrumentation**, which is the one thing that differs, and the case
 * that matters most is `thisTestRunIsRecognisedAsOne`: it uses the REAL detector, so if it ever
 * stops working, uploading silently resumes and nothing else would notice.
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

    /**
     * Defaults are the PRODUCTION detectors, named explicitly rather than left implicit — a case
     * that means to exercise the real one must not be able to lose it to a later refactor.
     */
    private fun uploader(
        onAnEmulator: () -> Boolean = ::runningOnAnEmulator,
        underInstrumentation: () -> Boolean = ::runningUnderInstrumentation,
    ) = DiagnosticsUploader(context, OkHttpClient(), scope, REFUSED, onAnEmulator, underInstrumentation)

    /**
     * THE case. A hand-send from a test must not reach the Pi, and this uses the real detector —
     * `DiagnosticsContentTest` drives exactly this path and produced most of September's noise.
     */
    @Test
    fun thisTestRunIsRecognisedAsOne() = runBlocking {
        aPendingReport()
        val before = DiagnosticsStore.pending(context).toSet()

        uploader().sendDiagnosticsNow()

        assertTrue(
            "a hand-send from a TEST was not suppressed, so every test run still posts to the Pi",
            awaitTrail("this is a test run"),
        )
        assertEquals("a suppressed upload must not delete anything", before, DiagnosticsStore.pending(context).toSet())
    }

    /** And the automatic path is suppressed on an emulator even when no test is driving. */
    @Test
    fun anEmulatorDoesNotUploadAutomatically() = runBlocking {
        aPendingReport()

        uploader(underInstrumentation = { false }).uploadPending()

        assertTrue(
            "the emulator running this test was not recognised as an emulator",
            awaitTrail("this is an emulator"),
        )
    }

    /**
     * And a real device still would. Asserted on a fragment that the SUPPRESSED line cannot also
     * contain: "not uploading"/"suppressed" both contain "uploading", so matching that alone made
     * this case pass whether or not the guard fired — it could not fail, which is worse than absent.
     */
    @Test
    fun aRealDeviceStillUploads() = runBlocking {
        aPendingReport()

        uploader(onAnEmulator = { false }, underInstrumentation = { false }).uploadPending()

        assertTrue(
            "nothing tried to upload, so the guard has disabled the pipeline rather than narrowed it",
            awaitTrail("pending report"),
        )
    }

    /** A person asked, on an emulator, with no test driving. It goes — that is where Dewi debugs. */
    @Test
    fun aHandSentReportGoesEvenFromAnEmulator() = runBlocking {
        aPendingReport()

        uploader(underInstrumentation = { false }).sendDiagnosticsNow()

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
