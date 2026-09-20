package com.dewijones92.totum

import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.runner.AndroidJUnitRunner

/**
 * The instrumentation runner, which grants POST_NOTIFICATIONS before a single test runs.
 *
 * The app asks for it as it opens, and a permission dialog is another activity: it pauses ours
 * and releases the video surface. On a phone somebody taps the dialog; on a runner emulator
 * nobody does, so it sits there and every test that plays anything watches a stopped picture.
 * That is the whole of the five-day live-test failure of September 2026, which read as "the
 * stream stopped".
 *
 * **In `onStart`, not `onCreate`.** `AndroidJUnitRunner.onCreate` ends by calling `start()`, which
 * spawns the instrumentation thread — so granting after `super.onCreate(...)` races the suite it
 * is meant to protect. It would usually win, which is the worst version of the bug: an occasional
 * dialog over a playing video, indistinguishable from a flake. `onStart` runs ON that thread,
 * before any test.
 *
 * **Here rather than in a shell script**, which is where it was first tried and where it cannot
 * work. `connectedAndroidTest` UNINSTALLS the app when it finishes, so a grant issued before the
 * gradle line is aimed at a package that will be replaced — and `pm grant` against a missing
 * package prints `Failure [package not found]` to stderr and exits 0, so behind the customary
 * `2>/dev/null || true` it announces nothing at all.
 *
 * **Here rather than a `GrantPermissionRule` in each test**, because thirteen tests in the
 * ordinary phase start playback and the fourteenth would be written without it.
 */
class TotumTestRunner : AndroidJUnitRunner() {

    override fun onStart() {
        grantNotifications()
        super.onStart()
    }

    private fun grantNotifications() {
        val target = targetContext.packageName
        runCatching {
            // Drained to EOF, which is the ONLY way to wait for it. `executeShellCommand` returns
            // the moment the command is spawned, so closing the descriptor immediately and asking
            // the package manager gave a confident "NOT granted" on a device where the grant then
            // landed a fraction of a second later (measured 2026-09-20). Reading to the end is the
            // synchronisation; the bytes themselves are worthless, because pm grant says nothing
            // on stdout whether it worked or not.
            ParcelFileDescriptor.AutoCloseInputStream(
                uiAutomation.executeShellCommand("pm grant $target $POST_NOTIFICATIONS"),
            ).use { it.readBytes() }
        }.onFailure { error ->
            Log.w(TAG, "dewidebug could not run pm grant for $target", error)
        }
        // The OUTCOME, asked of the package manager rather than inferred from the command.
        // `executeShellCommand` hands back stdout only, and `pm grant` reports every failure on
        // stderr while exiting 0 — so reading its output cannot tell a refusal from a success,
        // which is precisely the blind spot that made the shell-script version useless. Reporting
        // "granted" off an empty stdout would have repeated it with more confidence.
        val granted = targetContext.checkSelfPermission(POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            Log.i(TAG, "dewidebug $POST_NOTIFICATIONS is granted to $target")
        } else {
            Log.w(
                TAG,
                "dewidebug $POST_NOTIFICATIONS is NOT granted to $target — a permission dialog " +
                    "may open over a playing video and pause it mid-test",
            )
        }
    }

    private companion object {
        const val TAG = "TotumTestRunner"
        const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
    }
}
