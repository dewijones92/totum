package com.dewijones92.totum.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.R
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.settings.SponsorRow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SponsorRowTest {

    @get:Rule
    val compose = createComposeRule()

    private fun text(id: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun show(configured: String) {
        compose.setContent { TotumTheme { SponsorRow(configured) } }
        compose.waitForIdle()
    }

    @Test
    fun noLinkInTheBuildMeansNoRow() {
        show("")

        assertEquals(0, compose.onAllNodesWithText(text(R.string.sponsor_title)).fetchSemanticsNodes().size)
    }

    @Test
    fun aLinkThatIsNotPlainHttpsMeansNoRow() {
        listOf("http://ko-fi.com/x", "javascript:alert(1)", "ko-fi.com/x", "https://").forEach { configured ->
            show(configured)

            assertEquals(
                configured,
                0,
                compose.onAllNodesWithText(text(R.string.sponsor_title)).fetchSemanticsNodes().size,
            )
        }
    }

    @Test
    fun anHttpsLinkShowsTheRowWithItsSummary() {
        show("https://ko-fi.com/totum")

        compose.onNodeWithText(text(R.string.sponsor_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.sponsor_summary)).assertIsDisplayed()
    }
}
