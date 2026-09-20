package com.dewijones92.totum.diagnostics

import android.content.Context
import android.os.Build
import com.dewijones92.totum.common.Diag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Sends pending reports to the sink and deletes them once accepted.
 *
 * Runs at launch rather than at crash time: at crash time the process is dying and a
 * network call would likely be killed mid-flight, so the crash handler only writes to
 * disk and the next launch does the sending.
 *
 * **The AUTOMATIC upload is suppressed on an emulator; a hand-sent one never is.** Every
 * instrumented run — each CI job, and each local one — launches the app and posts whatever it
 * wrote, so the sink filled with test output: of 106 reports in September, **100 came from an
 * emulator and six from the phone**. That is the same "26 unread reports" failure the triage
 * feature was built to prevent, recreated at a much higher rate, and it buries the reports that
 * are someone actually telling us something.
 *
 * Only [uploadPending] is guarded, and [sendDiagnosticsNow] is deliberately not: a person tapping
 * "Send diagnostics" has asked, and an emulator is where Dewi debugs. That split also settles the
 * cost of getting the detector wrong. A false NEGATIVE costs one filterable row; a false POSITIVE
 * would otherwise cost exactly the reports that matter, silently — the phone would simply go
 * quiet, and nothing on the server can tell "nothing broke" from "the detector misfired". With
 * the button always sending, a misdetected phone still gets its report out the moment it is
 * asked for, so the detector can be judged on accuracy rather than on that asymmetry.
 */
public class DiagnosticsUploader(
    private val context: Context,
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
    private val endpoint: String = ENDPOINT,
    private val onAnEmulator: () -> Boolean = ::runningOnAnEmulator,
) {
    /** The automatic upload at launch — suppressed on an emulator. See the class note. */
    public fun uploadPending(): Unit = upload(becauseSomeoneAsked = false)

    /** A person tapped "Send diagnostics". Always goes, emulator or not. */
    public fun sendDiagnosticsNow(): Unit = upload(becauseSomeoneAsked = true)

    private fun upload(becauseSomeoneAsked: Boolean) {
        scope.launch(Dispatchers.IO) {
            val pending = DiagnosticsStore.pending(context)
            if (pending.isEmpty()) {
                Diag.log("diagnostics", "nothing pending to upload")
                return@launch
            }
            if (!becauseSomeoneAsked && onAnEmulator()) {
                // Said rather than silent: "my report never arrived" has to be answerable, and the
                // answer here is a deliberate one.
                Diag.log("diagnostics", "suppressed ${pending.size} report(s) — this is an emulator")
                return@launch
            }
            Diag.log("diagnostics", "uploading ${pending.size} pending report(s)")
            pending.forEach { file ->
                val sent = runCatching { post(file.readText()) }.getOrElse { error ->
                    Diag.warn("diagnostics", "upload failed, keeping ${file.name}", error)
                    false
                }
                // Kept on failure so it retries next launch; deleted only once accepted.
                if (sent) file.delete()
            }
        }
    }

    private fun post(body: String): Boolean {
        val request = Request.Builder()
            .url(endpoint)
            .post(body.toRequestBody(JSON))
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                Diag.log("diagnostics", "report upload -> HTTP ${response.code}")
                response.isSuccessful
            }
        } catch (e: IOException) {
            Diag.warn("diagnostics", "report upload failed", e)
            false
        }
    }

    private companion object {
        /** The sink on Dewi's Pi; /ingest is the only unauthenticated path there. */
        const val ENDPOINT = "https://crashlog.333133333.xyz/ingest"
        val JSON = "application/json".toMediaType()
    }
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
private fun runningOnAnEmulator(): Boolean =
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
private val EMULATOR_HARDWARE = setOf("ranchu", "goldfish", "vbox86", "android_x86")
