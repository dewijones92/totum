package com.dewijones92.totum.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Environment
import android.os.StatFs
import androidx.core.content.getSystemService
import com.dewijones92.totum.BuildConfig
import com.dewijones92.totum.common.Breadcrumbs
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.Vitals
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant

/**
 * Catches crashes, writes a verbose report to disk, and lets [DiagnosticsUploader] send
 * it. Disk first, always: a crash is often the last thing the process does, so a report
 * that only existed in memory (or was mid-upload) would be lost. Anything left on disk
 * is sent on the next launch.
 *
 * **Collection policy:** verbose by explicit instruction (Dewi, 2026-07-25 — "forget
 * about PII or data sensitivity … prioritise collecting data"). Titles, URLs, ids,
 * queue contents and settings all go in. The single exception is credentials: the
 * YouTube OAuth tokens are never read here, because a token in a transmitted log is an
 * account-takeover risk rather than a disclosure of viewing habits.
 */
public class CrashReporter(
    private val context: Context,
    private val stateProviders: () -> Map<String, String> = { emptyMap() },
) {
    /**
     * Held so an OutOfMemoryError can be reported at all.
     *
     * Writing a report allocates — a JSONObject, the stack trace as a string, the event trail —
     * and on the way out of an OOM there is nothing left to allocate from, so the attempt threw a
     * second OutOfMemoryError, `runCatching` swallowed it, and the crash left NO report. That is
     * exactly what happened on 2026-08-06: the app died twice of OOM at 07:43 and the sink knew
     * nothing about it; the cause had to be reconstructed from a diagnostics report sent by hand
     * fifty minutes later, from a different process.
     *
     * Dropping this reserve first hands the handler a few MB of headroom to work in. Deliberately
     * a plain allocation rather than anything clever: it has to exist before the crash, and the
     * only thing it does is stop being referenced.
     */
    @Volatile
    private var oomReserve: ByteArray? = ByteArray(OOM_RESERVE_BYTES)

    /** Installs the handler, chaining to whatever was there so the app still dies properly. */
    public fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // FIRST, before anything that allocates, and for any error: a heap this close to
            // full is why the report is about to fail, whatever the exception type says.
            oomReserve = null
            runCatching { writeReport(kind = "crash", error = error, thread = thread.name) }
                .onFailure {
                    // A failure here is the report itself failing, which is invisible by
                    // definition. Logcat is all that is left, and it survives the process.
                    Diag.warn("diagnostics", "could not write the crash report for $error", it)
                }
            previous?.uncaughtException(thread, error)
        }
        Diag.log("diagnostics", "crash reporter installed")
    }

    /** Writes a report for something that was handled but shouldn't have happened. */
    public fun reportNonFatal(error: Throwable, note: String? = null) {
        runCatching { writeReport(kind = "non-fatal", error = error, note = note) }
    }

    /** Writes a report with no error at all — "something felt wrong", from Settings. */
    public fun reportDiagnostics(note: String? = null): File? =
        runCatching { writeReport(kind = "diagnostics", error = null, note = note) }.getOrNull()

    private fun writeReport(
        kind: String,
        error: Throwable?,
        thread: String? = null,
        note: String? = null,
    ): File {
        val report = JSONObject().apply {
            put("kind", kind)
            put("reportedAt", Instant.now().toString())
            put("appVersion", BuildConfig.VERSION_NAME)
            put("versionCode", BuildConfig.VERSION_CODE)
            put("gitCommit", BuildConfig.GIT_SHA)
            put("buildType", BuildConfig.BUILD_TYPE)
            put("installId", InstallId.get(context))
            note?.let { put("note", it) }
            thread?.let { put("thread", it) }

            error?.let {
                put("exception", it.javaClass.name)
                put("message", it.message ?: "")
                put("stackTrace", it.stackTraceText())
                it.cause?.let { cause ->
                    put("causeException", cause.javaClass.name)
                    put("causeMessage", cause.message ?: "")
                }
            }

            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            put("memory", memoryInfo())
            // Broken out as numbers as well as prose, because "was it near the ceiling?" is the
            // first question of any OOM and the prose form cannot be compared across reports.
            Runtime.getRuntime().let { runtime ->
                put("heapUsedMb", (runtime.totalMemory() - runtime.freeMemory()) / MB)
                put("heapMaxMb", runtime.maxMemory() / MB)
                put("nativeHeapMb", Debug.getNativeHeapAllocatedSize() / MB)
            }
            put("storageFreeMb", freeStorageMb())

            // Whatever the app can tell us about itself right now — playback, queue,
            // settings. Supplied by the caller so this class needs no app dependencies.
            put("state", JSONObject(stateProviders().toMap()))

            // Running totals for the whole session — stalls, buffering time, resolve
            // failures. A breadcrumb trail only reaches back so far; these do not
            // scroll off, which is what makes an intermittent problem visible.
            put("vitals", JSONObject(Vitals.snapshot().toMap()))

            put("events", breadcrumbsJson())
            put("logcat", logcatTail())
        }
        return DiagnosticsStore.write(context, report)
    }

    private fun Throwable.stackTraceText(): String {
        val writer = StringWriter()
        printStackTrace(PrintWriter(writer))
        return writer.toString()
    }

    private fun breadcrumbsJson(): JSONArray {
        val array = JSONArray()
        Breadcrumbs.snapshot().forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("at", Breadcrumbs.formatTime(entry.atEpochMs))
                    put("tag", entry.tag)
                    put("message", entry.message)
                },
            )
        }
        return array
    }

    /**
     * Our own logcat, which on modern Android is all an app can read — and all we want.
     * This is where the Media3 / MediaCodec / ExoPlayer lines live, and those were what
     * actually diagnosed this project's playback bugs.
     *
     * **Bounded, because this runs on the crash path.** It is called from the uncaught-exception
     * handler, one line BEFORE the report is written and before the previous handler is chained
     * to — so a `logcat` that never returns does not merely cost a log, it means the report is
     * never written at all and the process hangs instead of dying and restarting. A wedged `logd`
     * is exactly the device duress that correlates with the crashes worth having.
     *
     * **The bound is on the DRAIN, not on the process exiting**, and that distinction is the whole
     * of it. An app may read only its OWN logcat, so an idle run is about 20KB and fits in a pipe
     * — but a session of the generous logging this project mandates does not, and that is exactly
     * the session worth having a crash report from. Once the tail exceeds the 64KiB pipe the child
     * blocks on `write()` and can never exit, so a `waitFor` placed before the read never
     * completes; `destroyForcibly` then closes the stream and the read throws, leaving the whole
     * block as `logcat unavailable: IOException: Stream closed` — 46 characters instead of the
     * tail, which is a silent and total loss of the most useful part of a report.
     *
     * **That is a warning, not this repo's history.** The code before this was an unbounded
     * `bufferedReader().readText()`, which is the correct way to drain a subprocess and cannot
     * deadlock; the `waitFor` version existed only as a wrong first attempt while this bound was
     * being written, and the 46 characters were measured on it. Said plainly because the obvious
     * way to add a timeout here is exactly that wrong one, and because a comment that invents a
     * past defect is worse than no comment — it is what the next person will trust.
     *
     * So what is NEW is the bound itself: the old drain was correct but unbounded, and a wedged
     * `logd` would have hung a dying process for ever.
     *
     * The thread is a daemon: this runs while the process is dying, and a non-daemon thread stuck
     * on an unreadable pipe would be one more thing keeping it alive.
     */
    private fun logcatTail(): String = outputOf(
        start = {
            ProcessBuilder("logcat", "-d", "-v", "time", "-t", LOGCAT_LINES.toString())
                .redirectErrorStream(true)
                .start()
        },
        seconds = LOGCAT_TIMEOUT_SECONDS,
        // In the RETURNED VALUE as well as the trail when it gives up: `events` is serialised one
        // line before this runs, so a breadcrumb written here would reach the NEXT report rather
        // than the one that is missing its logcat. A report has to explain its own gap.
        what = "logcat",
        maxChars = MAX_LOGCAT_CHARS,
    )

    private fun memoryInfo(): String {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService<ActivityManager>()?.getMemoryInfo(info)
        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / MB
        return "heapUsed=${usedMb}MB heapMax=${runtime.maxMemory() / MB}MB " +
            "systemAvail=${info.availMem / MB}MB lowMemory=${info.lowMemory}"
    }

    private fun freeStorageMb(): Long = runCatching {
        StatFs(Environment.getDataDirectory().path).availableBytes / MB
    }.getOrDefault(-1)

    private companion object {
        const val MB = 1024L * 1024L

        /**
         * Enough headroom for one report: the JSON, a stack trace, the event trail and a trimmed
         * logcat come to a few hundred KB, and 4MB leaves room for the copies made on the way.
         */
        const val OOM_RESERVE_BYTES = 4 * 1024 * 1024

        /**
         * Long enough for a healthy `logcat -d` on a loaded device, short enough that a wedged
         * one costs a couple of seconds of a dying process rather than the whole report.
         */
        const val LOGCAT_TIMEOUT_SECONDS = 3L

        /** The wording lives in [unavailable]; `OutputOfTest` asserts it against this same call. */
        val LOGCAT_UNAVAILABLE: String get() = unavailable("logcat", LOGCAT_TIMEOUT_SECONDS)

        const val LOGCAT_LINES = 1500
        const val MAX_LOGCAT_CHARS = 400_000
    }
}
