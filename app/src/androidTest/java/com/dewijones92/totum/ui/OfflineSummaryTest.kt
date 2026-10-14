package com.dewijones92.totum.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.dewijones92.totum.domain.OfflineReadiness
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.queue.OfflineSummary
import org.junit.Rule
import org.junit.Test

/**
 * The queue says, in words, whether it can be listened to with no signal.
 *
 * Dewi, 2026-08-02: *"I expect the gui / labels etc to be very very clear"*. Rendered rather
 * than unit-tested because the thing being checked IS the wording on screen — `OfflineReadiness`
 * already has the arithmetic covered on the JVM, and a test asserting the numbers again would
 * prove nothing about what a person actually reads.
 *
 * Runs on CI's emulator with the rest of `connectedDebugAndroidTest`.
 */
class OfflineSummaryTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun show(
        readiness: OfflineReadiness,
        autoDownloadOff: Boolean = false,
        waitingForWifi: Boolean = false,
    ) = composeTestRule.setContent {
        TotumTheme {
            OfflineSummary(
                readiness = readiness,
                autoDownloadOff = autoDownloadOff,
                waitingForWifi = waitingForWifi,
            )
        }
    }

    @Test
    fun aFullyDownloadedQueueSaysSoPlainly() {
        show(OfflineReadiness(ready = 12, downloading = 0, waiting = 0, unavailableOffline = 0))

        composeTestRule.onNodeWithText("✅ All 12 ready to play offline").assertIsDisplayed()
    }

    /**
     * Dewi's actual queue: most items downloaded, a few members-only ones that never can be.
     * Saying "73 ready" alone would leave him wondering where the other 4 went.
     */
    @Test
    fun itemsThatCanNeverDownloadAreCountedSeparately() {
        show(OfflineReadiness(ready = 73, downloading = 0, waiting = 0, unavailableOffline = 4))

        composeTestRule.onNodeWithText("⚠️ 73 ready offline · 4 can't be downloaded").assertIsDisplayed()
    }

    @Test
    fun workInProgressSaysHowMuchIsLeft() {
        show(OfflineReadiness(ready = 60, downloading = 1, waiting = 16, unavailableOffline = 0))

        composeTestRule.onNodeWithText("⬇️ 60 of 77 ready offline · 17 still to fetch").assertIsDisplayed()
    }

    /**
     * The state that was completely invisible before, and the reason the default changed.
     * Waiting for Wi-Fi used to look identical to nothing happening at all.
     */
    @Test
    fun waitingForWifiIsNamedRatherThanLookingIdle() {
        show(
            OfflineReadiness(ready = 2, downloading = 0, waiting = 9, unavailableOffline = 0),
            waitingForWifi = true,
        )

        composeTestRule.onNodeWithText("📶 Waiting for Wi-Fi to download 9 items").assertIsDisplayed()
    }

    /** Singular reads as a sentence, not as "1 item(s)" — the point of the plurals. */
    @Test
    fun oneOutstandingItemReadsProperly() {
        show(
            OfflineReadiness(ready = 3, downloading = 0, waiting = 1, unavailableOffline = 0),
            waitingForWifi = true,
        )

        composeTestRule.onNodeWithText("📶 Waiting for Wi-Fi to download 1 item").assertIsDisplayed()
    }

    /**
     * A queue mid-fetch must read as progress, not as a problem. This is the state Dewi was
     * looking at when he said it was not clear what was downloading.
     */
    @Test
    fun oneItemDownloadingReadsAsProgress() {
        show(OfflineReadiness(ready = 40, downloading = 1, waiting = 36, unavailableOffline = 4))

        composeTestRule.onNodeWithText("⬇️ 40 of 81 ready offline · 37 still to fetch").assertIsDisplayed()
    }

    /** Automatic downloads switched off must never be reported as "still to fetch". */
    @Test
    fun automaticDownloadsBeingOffIsSaidOutLoud() {
        show(
            OfflineReadiness(ready = 0, downloading = 0, waiting = 5, unavailableOffline = 0),
            autoDownloadOff = true,
        )

        composeTestRule
            .onNodeWithText("⚪ Automatic downloads are off · 5 items not saved offline")
            .assertIsDisplayed()
    }
}
