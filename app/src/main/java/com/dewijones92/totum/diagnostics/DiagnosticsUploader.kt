package com.dewijones92.totum.diagnostics

import android.os.Build
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HomeServerAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File

/**
 * Sends pending reports to the sink and deletes them once accepted.
 *
 * Runs at launch rather than at crash time: at crash time the process is dying and a
 * network call would likely be killed mid-flight, so the crash handler only writes to
 * disk and the next launch does the sending.
 *
 * **Nothing is uploaded from a test run.** Of 106 reports on the sink in September, **100 came
 * from an emulator and six from Dewi's phone** — the "26 unread reports" failure the triage
 * feature was built to prevent, recreated at a much higher rate, burying the reports that are
 * someone actually telling us something.
 *
 * The discriminator is **instrumentation, not the device**, and getting that wrong cost a whole
 * round: guarding only the automatic launch-time upload achieved close to nothing, because the
 * tests generating the noise are HAND-SENDS by construction — `DiagnosticsContentTest` and
 * `DiagnosticsNoteBoxTest` drive `sendDiagnostics()`, the same seam the Settings button drives,
 * and neither is excluded from the ordinary CI job. No `Build` heuristic can separate those from
 * a person, because on the device they are identical. Whether a test runner is in the process
 * separates them exactly.
 *
 * A person tapping "Send diagnostics" on an emulator still sends — that is where this app gets
 * debugged, and a button that silently did nothing while the UI said "Sent" would be worse than
 * the noise it avoided.
 *
 * [onAnEmulator] remains as a secondary guard on the AUTOMATIC path only, for an emulator someone
 * is driving by hand. Its false-positive cost is bounded by the same rule: a misdetected phone
 * still gets its report out the moment the button is tapped.
 */
public class DiagnosticsUploader(
    private val pending: () -> List<File>,
    client: OkHttpClient,
    private val scope: CoroutineScope,
    endpoint: () -> String? = HomeServerAuth::reportEndpoint,
    private val onAnEmulator: () -> Boolean = ::runningOnAnEmulator,
    private val underInstrumentation: () -> Boolean = ::runningUnderInstrumentation,
    private val signedIn: () -> Boolean = HomeServerAuth::signedIn,
    private val sendToHome: () -> Boolean = { true },
    private val dump: suspend () -> ByteArray? = { null },
) {
    private val sink = ReportSink(client, endpoint)

    /** The automatic upload at launch — suppressed on an emulator. See the class note. */
    public fun uploadPending(): Unit = launchUpload(becauseSomeoneAsked = false)

    /**
     * A person tapped "Send diagnostics". Goes from an emulator as well as a phone — but still not
     * from a test run, because the tests that flooded the sink drove this very method.
     */
    public fun sendDiagnosticsNow(): Unit = launchUpload(becauseSomeoneAsked = true)

    public fun sendStateNow(onDone: (StateSend) -> Unit = {}) {
        scope.launch(Dispatchers.IO) { onDone(sendState()) }
    }

    private fun launchUpload(becauseSomeoneAsked: Boolean) {
        scope.launch(Dispatchers.IO) { upload(becauseSomeoneAsked) }
    }

    internal suspend fun upload(becauseSomeoneAsked: Boolean) {
        val waiting = pending()
        if (waiting.isEmpty()) {
            Diag.log("diagnostics", "nothing pending to upload")
            return
        }
        // Said rather than silent in both cases: "my report never arrived" has to be
        // answerable, and the answer here is a deliberate one.
        if (underInstrumentation()) {
            Diag.log("diagnostics", "suppressed ${waiting.size} report(s) — this is a test run")
            return
        }
        if (!becauseSomeoneAsked && onAnEmulator()) {
            Diag.log("diagnostics", "suppressed ${waiting.size} report(s) — this is an emulator")
            return
        }
        if (!signedIn()) {
            Diag.log(
                "diagnostics",
                "kept ${waiting.size} report(s) on the phone: not signed in to a home server, so nothing " +
                    "is sent to one (asked by a person=$becauseSomeoneAsked); a signed-out phone reports " +
                    "through GitHub from Settings",
            )
            return
        }
        if (!becauseSomeoneAsked && !sendToHome()) {
            Diag.log(
                "diagnostics",
                "kept ${waiting.size} report(s) on the phone: sending reports to the home server is switched " +
                    "off, so nothing is posted automatically; Settings offers GitHub and the crash screen " +
                    "can still send by hand",
            )
            return
        }
        val waitingKb = waiting.sumOf { it.length() } / BYTES_PER_KB
        Diag.log("diagnostics", "uploading ${waiting.size} pending report(s), $waitingKb KB on the phone")
        var bringsState = false
        waiting.forEach { file ->
            val kind = reportKind(file)
            val sent = runCatching { sink.post(file.readText(), "report") }.getOrElse { error ->
                Diag.warn("diagnostics", "upload failed, keeping ${file.name}", error)
                false
            }
            // Kept on failure so it retries next launch; deleted only once accepted.
            if (sent) file.delete()
            if (sent && kind in STATE_KINDS) bringsState = true
            Diag.log("diagnostics", "report ${file.name} kind=$kind sent=$sent")
        }
        if (bringsState) {
            Diag.log("diagnostics", "state sent with the report(s): ${sendState()}")
        } else {
            Diag.log("diagnostics", "no state sent: no crash or diagnostics report went")
        }
    }

    internal suspend fun sendState(): StateSend {
        if (underInstrumentation()) return refused(StateSend.TestRun, "this is a test run")
        if (!signedIn()) return refused(StateSend.NotSignedIn, "not signed in to a home server")
        val bytes = runCatching { dump() }.getOrElse { error ->
            Diag.warn("diagnostics", "state not sent: the dump could not be gathered", error)
            null
        } ?: return refused(StateSend.NotGathered, "nothing was gathered")
        val sent = runCatching { sink.postState(bytes, "state") }.getOrElse { error ->
            Diag.warn("diagnostics", "state upload failed", error)
            false
        }
        Diag.log("diagnostics", "state ${bytes.size} bytes gzipped, sent=$sent")
        return if (sent) StateSend.Sent(bytes.size) else StateSend.Refused
    }

    private fun refused(result: StateSend, why: String): StateSend {
        Diag.log("diagnostics", "state not sent: $why")
        return result
    }

    private fun reportKind(file: File): String = runCatching {
        file.reader().use { reader ->
            val head = CharArray(KIND_SEARCH_CHARS)
            val read = reader.read(head).coerceAtLeast(0)
            KIND.find(String(head, 0, read))?.groupValues?.get(1)
        }
    }.getOrNull() ?: "unknown"

    private companion object {
        const val BYTES_PER_KB = 1024L
        const val KIND_SEARCH_CHARS = 512
        val KIND = Regex("\"kind\"\\s*:\\s*\"([a-z-]+)\"")
        val STATE_KINDS = setOf("crash", "diagnostics")
    }
}

public sealed interface StateSend {
    public data class Sent(val bytes: Int) : StateSend
    public data object NotSignedIn : StateSend
    public data object TestRun : StateSend
    public data object NotGathered : StateSend
    public data object Refused : StateSend
}

/**
 * Whether this is an emulator rather than somebody's phone.
 *
 * **`HARDWARE` first, because it is the only stable one.** The running AVD reports
 * `FINGERPRINT=google/sdk_gphone64_x86_64/emu64xa:15/…`, `PRODUCT=sdk_gphone64_x86_64`,
 * `MODEL=sdk_gphone64_x86_64`, `HARDWARE=ranchu`, `DEVICE=emu64xa` — measured with `adb getprop`,
 * not assumed. So a `FINGERPRINT.contains("emulator")` check does NOT fire (it says `emu64xa`),
 * and the naming Google has already changed twice (`sdk_phone_armv7` → `sdk_google_phone_x86` →
 * `sdk_gphone64_x86_64`) is the only thing the obvious clauses match on.
 *
 * `ranchu` (and the older `goldfish`) is the QEMU machine type every AOSP, Google-APIs, ATD and
 * Play-Store image reports, across API levels, and it is what CI's image reports too. `vbox`
 * covers Genymotion. The product/model clauses stay as a belt-and-braces for an image that
 * overrides the hardware string.
 */
public fun runningOnAnEmulator(): Boolean =
    Build.HARDWARE in EMULATOR_HARDWARE ||
        Build.DEVICE.startsWith("emu") ||
        Build.FINGERPRINT.startsWith("generic") ||
        Build.FINGERPRINT.contains("vbox") ||
        Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
        Build.FINGERPRINT.contains("sdk_gphone") ||
        Build.PRODUCT.startsWith("sdk") ||
        Build.PRODUCT.contains("emulator") ||
        Build.MODEL.contains("Emulator") ||
        Build.MODEL.contains("Android SDK built for")

/** QEMU machine types: `goldfish` is the classic emulator, `ranchu` everything since API 25-ish. */
private val EMULATOR_HARDWARE: Set<String> = setOf("ranchu", "goldfish", "vbox86", "android_x86")

/**
 * Whether a test runner is in this process.
 *
 * Instrumentation loads the test APK's classes into the app's own process, so a class that only
 * the test configurations depend on is present exactly when a test is driving. `androidx.test` is
 * an `androidTestImplementation` dependency and never ships in the app, so this is false in every
 * build Dewi installs.
 *
 * The device cannot answer this question. `DiagnosticsContentTest` calls the same
 * `sendDiagnostics()` the Settings button calls, on the same emulator Dewi debugs on — identical
 * in every `Build` property. What differs is that one of them has a test runner in the process.
 */
public fun runningUnderInstrumentation(): Boolean =
    runCatching { Class.forName("androidx.test.platform.app.InstrumentationRegistry") }.isSuccess
