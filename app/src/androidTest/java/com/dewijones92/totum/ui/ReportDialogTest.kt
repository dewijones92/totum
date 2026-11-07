package com.dewijones92.totum.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.R
import com.dewijones92.totum.di.fake.FakeAppContainer
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.settings.ReportDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ReportDialogTest {

    @get:Rule
    val compose = createComposeRule()

    private fun text(id: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private val sentToPi = mutableListOf<String>()

    private fun show(signedIn: Boolean) {
        val container = FakeAppContainer()
        if (signedIn) {
            container.appPreferences.setHomeServer("home.example", "")
            container.appPreferences.setHomeServerToken("device-token")
        }
        compose.setContent {
            TotumTheme { ReportDialog(container, onDismiss = {}, onSendToPi = { note, _ -> sentToPi += note }) }
        }
        compose.waitForIdle()
    }

    @Test
    fun aSignedInPhoneSendsItsNoteToThePiAndOffersNoGitHubStep() {
        show(signedIn = true)

        compose.onNodeWithText(text(R.string.settings_diagnostics_send)).assertIsDisplayed()
        assertEquals(
            0,
            compose.onAllNodesWithText(text(R.string.report_github_preview_button)).fetchSemanticsNodes().size,
        )
        compose.onNodeWithText(text(R.string.settings_diagnostics_send)).performClick()
        compose.waitForIdle()

        assertEquals(1, sentToPi.size)
    }

    @Test
    fun aSignedOutPhoneTypesANoteThenReadsExactlyWhatGitHubWillBeGivenBeforeAnythingLeaves() {
        show(signedIn = false)
        compose.onNodeWithText(text(R.string.report_github_note_supporting)).assertIsDisplayed()

        compose.onNodeWithText(text(R.string.settings_diagnostics_note_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.report_github_preview_button)).performClick()
        compose.waitForIdle()

        compose.onNodeWithText(text(R.string.report_github_preview_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.report_github_open)).assertIsDisplayed()
        assertTrue("nothing went to the Pi: $sentToPi", sentToPi.isEmpty())
    }

    @Test
    fun theBackButtonReturnsToTheNoteWithWhatWasTyped() {
        show(signedIn = false)
        compose.onNodeWithText(text(R.string.report_github_preview_button)).performClick()
        compose.waitForIdle()

        compose.onNodeWithText(text(R.string.report_github_back)).performClick()
        compose.waitForIdle()

        compose.onNodeWithText(text(R.string.report_github_preview_button)).assertIsDisplayed()
        assertTrue(sentToPi.isEmpty())
    }
}
