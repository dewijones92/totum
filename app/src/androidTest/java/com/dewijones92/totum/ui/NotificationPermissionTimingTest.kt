package com.dewijones92.totum.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import com.dewijones92.totum.di.fake.FakeAppContainer
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.common.RequestNotificationPermissionOnce
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The notification permission is asked for BEFORE anything plays, and asked for once.
 *
 * It used to be asked for at first play, and the dialog is another activity: it pauses ours and
 * releases the video surface, so pressing play on a fresh install stopped the picture. The live
 * CI tests were red on exactly that for five days and read as "the stream stopped" — the logcat
 * shows `START … REQUEST_PERMISSIONS`, `video size=0x0 hasVideo=false` and `MainActivity in:
 * PAUSED` four milliseconds apart.
 *
 * So this asserts the TIMING, which is the thing that was wrong. A test that only asserted "it
 * asks" passed against the broken version too.
 */
class NotificationPermissionTimingTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun itAsksWithoutWaitingForSomethingToPlay() {
        val asked = AtomicInteger()

        composeTestRule.setContent {
            RequestNotificationPermissionOnce(granted = { false }, request = { asked.incrementAndGet() })
        }
        composeTestRule.waitForIdle()

        assertEquals(
            "nothing has played yet, so a request now is the only one that cannot pause the video",
            1,
            asked.get(),
        )
    }

    @Test
    fun itDoesNotAskAgainOnEveryRecomposition() {
        val asked = AtomicInteger()
        var nudge by mutableStateOf(0)

        composeTestRule.setContent {
            @Suppress("UNUSED_EXPRESSION")
            nudge
            RequestNotificationPermissionOnce(granted = { false }, request = { asked.incrementAndGet() })
        }
        composeTestRule.waitForIdle()
        repeat(RECOMPOSITIONS) {
            composeTestRule.runOnIdle { nudge++ }
            composeTestRule.waitForIdle()
        }

        assertEquals("a dialog queued behind every frame is worse than no dialog", 1, asked.get())
    }

    @Test
    fun itAsksNobodyWhenItAlreadyHasThePermission() {
        val asked = AtomicInteger()

        composeTestRule.setContent {
            RequestNotificationPermissionOnce(granted = { true }, request = { asked.incrementAndGet() })
        }
        composeTestRule.waitForIdle()

        assertEquals(0, asked.get())
    }

    /**
     * THE MUTATION THAT MATTERED. The shell asks with nothing playing.
     *
     * The three cases above drive the composable directly, and an adversarial review of
     * 2026-09-20 showed that all three stay green if `AppShell` puts the gate back —
     * `if (playbackState != null) RequestNotificationPermissionOnce()` — which is the original
     * defect exactly. They also stay green if the call is deleted from the shell entirely. The
     * bug was never inside the composable; it was the call site, so the call site is what has to
     * be asserted.
     */
    @Test
    fun theShellAsksWithoutWaitingForAPlaybackState() {
        val asked = AtomicInteger()

        composeTestRule.setContent {
            TotumTheme {
                AppShell(FakeAppContainer(), askForNotifications = { asked.incrementAndGet() })
            }
        }
        composeTestRule.waitForIdle()

        // At least one, not exactly one: the lambda runs per composition of AppShell's body, and a
        // flow settling would recompose it. Both mutations this exists to kill — the gate put back,
        // and the call deleted — produce ZERO, so the weaker assertion catches them both while a
        // stray recomposition can no longer report "the five-day CI failure back again" wrongly.
        assertTrue(
            "FakeAppContainer plays nothing, so an ask that arrives here cannot be waiting on " +
                "playback — and one that never arrives is the five-day CI failure back again",
            asked.get() >= 1,
        )
    }

    private companion object {
        const val RECOMPOSITIONS = 5
    }
}
