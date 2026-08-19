package com.dewijones92.totum.account

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.innertube.auth.DeviceLoginEvent
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Signs this device in to YouTube, printing the code for a human to approve.
 *
 * NOT part of any suite: it needs a person at google.com/device, so it is registered as a live test and
 * excluded from CI. It exists because the sign-in screen could not be reached by hand on the emulator on
 * 2026-08-19 (the Library's nav rows did not render) and because the automation browser profile is now
 * signed out, so a code cannot be approved without Dewi.
 *
 * Drives the real `YouTubeAccount.signIn()` rather than the screen, which matters: that flow saves the
 * tokens itself on `Succeeded`, so a device signed in this way is signed in for every other purpose --
 * no separate storage step to get wrong.
 *
 * Run it, watch logcat for `dewidebug signin code`, hand the code over, and it completes on approval.
 */
class SignInOnThisDeviceTest {

    private val app = InstrumentationRegistry.getInstrumentation()
        .targetContext.applicationContext as TotumApplication

    @Test
    fun signInWithACodeAHumanApproves() = runBlocking {
        val account = app.container.youTubeAccount
        if (account.isSignedIn()) {
            Log.i("dewidebug", "signin already signed in — nothing to do")
            return@runBlocking
        }
        val outcome = withTimeoutOrNull(APPROVAL_WINDOW_MS) {
            account.signIn()
                .takeWhile { it !is DeviceLoginEvent.Succeeded && it !is DeviceLoginEvent.Failed }
                .collect { event ->
                    if (event is DeviceLoginEvent.AwaitingUser) {
                        Log.i("dewidebug", "signin code ${event.userCode} at ${event.verificationUrl.value}")
                    } else {
                        Log.i("dewidebug", "signin event $event")
                    }
                }
            true
        }
        // Asked of the STORE, not reconstructed from the events: takeWhile drops the terminal one, and
        // the store is what every other caller reads.
        val signedIn = account.isSignedIn()
        Log.i("dewidebug", "signin finished — signedIn=$signedIn collected=$outcome")
        assertTrue(
            "nobody approved the code within ${APPROVAL_WINDOW_MS / MS_PER_MINUTE} minutes, or the flow " +
                "failed — see the dewidebug signin lines in logcat",
            signedIn,
        )
    }

    private companion object {
        const val MS_PER_MINUTE = 60_000L
        const val APPROVAL_WINDOW_MS = 9 * MS_PER_MINUTE
    }
}
