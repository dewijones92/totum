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
    private val stateLimitBytes: Long = DEFAULT_STATE_LIMIT_BYTES,
) {
    private val sink = ReportSink(client, endpoint)

    /** The automatic upload at launch — suppressed on an emulator. See the class note. */
    public fun uploadPending(): Unit = launchUpload(becauseSomeoneAsked = false) {}

    /**
     * A person tapped "Send diagnostics". Goes from an emulator as well as a phone — but still not
     * from a test run, because the tests that flooded the sink drove this very method.
     */
    public fun sendDiagnosticsNow(onDone: (UploadOutcome) -> Unit = {}): Unit =
        launchUpload(becauseSomeoneAsked = true, onDone)

    public fun sendStateNow(onDone: (StateSend) -> Unit = {}) {
        scope.launch(Dispatchers.IO) { onDone(sendState()) }
    }

    private fun launchUpload(becauseSomeoneAsked: Boolean, onDone: (UploadOutcome) -> Unit) {
        scope.launch(Dispatchers.IO) { onDone(upload(becauseSomeoneAsked)) }
    }

    internal suspend fun upload(becauseSomeoneAsked: Boolean): UploadOutcome {
        val waiting = pending()
        val kept = UploadOutcome(sent = 0, kept = waiting.size)
        if (waiting.isEmpty()) {
            Diag.log("diagnostics", "nothing pending to upload")
            return kept
        }
        // Said rather than silent in both cases: "my report never arrived" has to be
        // answerable, and the answer here is a deliberate one.
        if (underInstrumentation()) {
            Diag.log("diagnostics", "suppressed ${waiting.size} report(s) — this is a test run")
            return kept
        }
        if (!becauseSomeoneAsked && onAnEmulator()) {
            Diag.log("diagnostics", "suppressed ${waiting.size} report(s) — this is an emulator")
            return kept
        }
        if (!signedIn()) {
            Diag.log(
                "diagnostics",
                "kept ${waiting.size} report(s) on the phone: not signed in to a home server, so nothing " +
                    "is sent to one (asked by a person=$becauseSomeoneAsked); a signed-out phone reports " +
                    "through GitHub from Settings",
            )
            return kept
        }
        if (!becauseSomeoneAsked && !sendToHome()) {
            Diag.log(
                "diagnostics",
                "kept ${waiting.size} report(s) on the phone: sending reports to the home server is switched " +
                    "off, so nothing is posted automatically; Settings offers GitHub and the crash screen " +
                    "can still send by hand",
            )
            return kept
        }
        val waitingKb = waiting.sumOf { it.length() } / BYTES_PER_KB
        Diag.log("diagnostics", "uploading ${waiting.size} pending report(s), $waitingKb KB on the phone")
        val run = RunDump()
        var settled = 0
        waiting.forEach { file -> if (uploadOne(file, run)) settled++ }
        return UploadOutcome(sent = settled, kept = waiting.size - settled)
    }

    private suspend fun uploadOne(file: File, run: RunDump): Boolean {
        val receipt = ReportReceipt(file)
        val earlier = receipt.read()
        if (earlier != null) {
            Diag.log(
                "diagnostics",
                "report ${file.name} was accepted earlier as ${earlier.reportId ?: "no id"}; sending only its state " +
                    "(attempt ${earlier.stateAttempts + 1} of $MAX_STATE_ATTEMPTS)",
            )
            return settleState(file, receipt, earlier, run)
        }
        val kind = reportKind(file)
        val reply = runCatching { sink.postReport(file.readText(), "report") }.getOrElse { error ->
            Diag.warn("diagnostics", "upload failed, keeping ${file.name}", error)
            SinkReply.NOT_POSTED
        }
        Diag.log("diagnostics", "report ${file.name} kind=$kind sent=${reply.accepted} http=${reply.code}")
        if (!reply.accepted) return false
        if (kind !in STATE_KINDS) {
            file.delete()
            Diag.log("diagnostics", "no state sent for ${file.name}: a $kind report brings none")
            return true
        }
        val accepted = Receipt(reply.reportId(), stateAttempts = 0)
        Diag.log(
            "diagnostics",
            if (accepted.reportId == null) {
                "report ${file.name} accepted with no id in the reply, so its state will go unlinked"
            } else {
                "report ${file.name} accepted as ${accepted.reportId}; its state follows"
            },
        )
        val written = runCatching { receipt.write(accepted) }
        if (written.isFailure) {
            file.delete()
            Diag.warn(
                "diagnostics",
                "no receipt could be kept for ${file.name}; dropping it rather than send it twice",
                written.exceptionOrNull(),
            )
            return true
        }
        return settleState(file, receipt, accepted, run)
    }

    private suspend fun settleState(file: File, receipt: ReportReceipt, held: Receipt, run: RunDump): Boolean {
        val label = held.reportId ?: "no id"
        val attempt = held.stateAttempts + 1
        when (val verdict = tryState(held.reportId, run)) {
            is StateVerdict.Sent -> Diag.log("diagnostics", "state ${verdict.bytes} bytes sent for report $label")
            is StateVerdict.GiveUp -> Diag.warn(
                "diagnostics",
                "state for report $label: ${verdict.why}; the report stays without its state",
            )
            is StateVerdict.Retry -> {
                if (attempt < MAX_STATE_ATTEMPTS) {
                    runCatching { receipt.write(held.copy(stateAttempts = attempt)) }.onFailure { error ->
                        Diag.warn("diagnostics", "the attempt count for report $label could not be kept", error)
                    }
                    Diag.log(
                        "diagnostics",
                        "state for report $label failed (${verdict.why}); kept pending with its report, " +
                            "attempt $attempt of $MAX_STATE_ATTEMPTS",
                    )
                    return false
                }
                Diag.warn(
                    "diagnostics",
                    "giving up on the state for report $label after $attempt attempts (last: ${verdict.why}); " +
                        "the report stays without its state",
                )
            }
        }
        file.delete()
        receipt.delete()
        return true
    }

    private suspend fun tryState(reportId: String?, run: RunDump): StateVerdict {
        val bytes = run.bytes() ?: return StateVerdict.Retry("the dump could not be gathered")
        if (bytes.size > stateLimitBytes) {
            return StateVerdict.GiveUp(
                "state too large, ${bytes.size} bytes against the limit of $stateLimitBytes bytes, not sent",
            )
        }
        val reply = runCatching { sink.postState(bytes, "state", reportId) }.getOrElse { error ->
            Diag.warn("diagnostics", "state upload failed", error)
            SinkReply.NOT_POSTED
        }
        return when {
            reply.accepted -> StateVerdict.Sent(bytes.size)
            reply.retryable -> StateVerdict.Retry(reply.code?.let { "HTTP $it" } ?: "no reply")
            else -> StateVerdict.GiveUp(
                "refused with HTTP ${reply.code}${if (reply.code == HTTP_TOO_LARGE) " (state too large)" else ""}, " +
                    "not retried",
            )
        }
    }

    internal suspend fun sendState(): StateSend {
        if (underInstrumentation()) return refused(StateSend.TestRun, "this is a test run")
        if (!signedIn()) return refused(StateSend.NotSignedIn, "not signed in to a home server")
        val bytes = gather() ?: return refused(StateSend.NotGathered, "nothing was gathered")
        if (bytes.size > stateLimitBytes) {
            return refused(
                StateSend.Refused,
                "state too large, ${bytes.size} bytes against the limit of $stateLimitBytes bytes, not sent",
            )
        }
        val reply = runCatching { sink.postState(bytes, "state", null) }.getOrElse { error ->
            Diag.warn("diagnostics", "state upload failed", error)
            SinkReply.NOT_POSTED
        }
        Diag.log("diagnostics", "state ${bytes.size} bytes gzipped, sent=${reply.accepted} http=${reply.code}")
        return if (reply.accepted) StateSend.Sent(bytes.size) else StateSend.Refused
    }

    private suspend fun gather(): ByteArray? = runCatching { dump() }.getOrElse { error ->
        Diag.warn("diagnostics", "state not sent: the dump could not be gathered", error)
        null
    }

    private inner class RunDump {
        private var asked = false
        private var gathered: ByteArray? = null

        suspend fun bytes(): ByteArray? {
            if (!asked) {
                asked = true
                gathered = gather()
            }
            return gathered
        }
    }

    private sealed interface StateVerdict {
        data class Sent(val bytes: Int) : StateVerdict
        data class Retry(val why: String) : StateVerdict
        data class GiveUp(val why: String) : StateVerdict
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

    internal companion object {
        const val MAX_STATE_ATTEMPTS = 5
        const val DEFAULT_STATE_LIMIT_BYTES = 31L * 1024 * 1024
        const val HTTP_TOO_LARGE = 413
        const val BYTES_PER_KB = 1024L
        const val KIND_SEARCH_CHARS = 512
        val KIND = Regex("\"kind\"\\s*:\\s*\"([a-z-]+)\"")
        val STATE_KINDS = setOf("crash", "diagnostics")
    }
}

public data class UploadOutcome(val sent: Int, val kept: Int)

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
