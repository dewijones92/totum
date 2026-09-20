package com.dewijones92.totum

import android.os.Bundle
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
 * **Here rather than in a shell script**, which is where it was first tried and where it cannot
 * work. `connectedAndroidTest` UNINSTALLS the app when it finishes, so a grant issued before the
 * gradle line runs is aimed at a package that will be replaced — and `pm grant` against a missing
 * package prints `Failure [package not found]` to stderr and exits 0, so behind the customary
 * `2>/dev/null || true` it announces nothing at all. The runner starts after the install, in the
 * process being tested, which is the only moment that is both late enough and guaranteed.
 *
 * **Here rather than a `GrantPermissionRule` in each test**, because thirteen tests in the
 * ordinary phase start playback and the fourteenth would be written without it.
 *
 * The outcome is logged either way: a grant that silently failed would leave the next
 * investigation chasing the video surface again.
 */
class TotumTestRunner : AndroidJUnitRunner() {

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        grantNotifications()
    }

    private fun grantNotifications() {
        val result = runCatching {
            uiAutomation
                .executeShellCommand("pm grant $TARGET_PACKAGE $POST_NOTIFICATIONS")
                .use { descriptor ->
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)
                        .bufferedReader()
                        .use { it.readText() }
                }
        }
        result.onSuccess { output ->
            val said = output.ifBlank { "no output, which is what success looks like" }
            Log.i(TAG, "dewidebug granted POST_NOTIFICATIONS to $TARGET_PACKAGE: $said")
        }.onFailure { error ->
            Log.w(
                TAG,
                "dewidebug could NOT grant POST_NOTIFICATIONS — a permission dialog may pause playback mid-test",
                error
            )
        }
    }

    private companion object {
        const val TAG = "TotumTestRunner"
        const val TARGET_PACKAGE = "com.dewijones92.totum"
        const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
    }
}
