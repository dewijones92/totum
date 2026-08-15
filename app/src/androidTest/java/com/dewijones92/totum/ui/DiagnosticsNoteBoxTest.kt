package com.dewijones92.totum.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.diagnostics.DiagnosticsStore
import com.dewijones92.totum.ui.settings.SettingsScreen
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Typing what went wrong, and it reaching the report — driven through the real screen.
 *
 * Dewi asked for this on 2026-08-15: *"a free-form text box where I can just tell you a bit more
 * context about the diagnostics, like the problem I faced in the UX"*. `DiagnosticsNoteTest` covers
 * the rule and `DiagnosticsContentTest` covers the report field; neither would notice the dialog
 * never opening, the Send button not being wired, or the text not reaching the call — which is the
 * failure mode that matters, because a box that types into nothing looks exactly like one that
 * works until the report that needed it.
 */
class DiagnosticsNoteBoxTest {

    // Not createAndroidComposeRule<MainActivity>: MainActivity sets its own content, so the rule
    // refuses to set any. This one hosts the composable under test on an empty activity.
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * The real Settings screen with the real container, rendered directly rather than navigated to.
     * The dialog's wiring is what is under test; walking Library → Account → Settings would make
     * this a test of the shell's navigation, which has its own.
     */
    private fun settings() {
        val container = (context.applicationContext as TotumApplication).container
        compose.setContent { SettingsScreen(container = container, onBack = {}) }
    }

    @Test
    fun typingWhatWentWrongReachesTheReport() {
        val before = DiagnosticsStore.pending(context).toSet()

        settings()
        compose.onNodeWithText(SEND).performScrollTo().performClick()
        compose.onNodeWithTag("diagnostics-note").assertIsDisplayed().performTextInput(TYPED)
        // Two nodes read "Send diagnostics" now — the row behind and the dialog's button. The
        // dialog's is the last one added, and clicking the row would prove nothing.
        compose.onAllNodesWithText(SEND).onLast().performClick()
        compose.waitForIdle()

        val written = DiagnosticsStore.pending(context).firstOrNull { it !in before }
        assertTrue("tapping Send wrote no report", written != null)
        assertEquals(TYPED, JSONObject(written!!.readText()).optString("note"))
    }

    /** Cancelling must not send: a half-written thought is not a report. */
    @Test
    fun cancellingSendsNothing() {
        val before = DiagnosticsStore.pending(context).toSet()

        settings()
        compose.onNodeWithText(SEND).performScrollTo().performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()

        assertEquals(before, DiagnosticsStore.pending(context).toSet())
    }

    private companion object {
        const val SEND = "Send diagnostics"
        const val TYPED = "tapped the WarFronts video and it jumped to another one"
    }
}
