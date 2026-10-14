package com.dewijones92.totum.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.R
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.update.AppUpdateCheck
import com.dewijones92.totum.data.update.AppVersion
import com.dewijones92.totum.data.update.Release
import com.dewijones92.totum.data.update.ReleaseLookup
import com.dewijones92.totum.di.fake.FakeAppContainer
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.common.OUT_OF_DATE_BANNER_TAG
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class OutOfDateBannerTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun text(id: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun shellWith(installed: String) {
        val latest = Release(
            AppVersion.parse("0.1.603")!!,
            "v0.1.603",
            HttpUrl.of("https://github.com/dewijones92/totum/releases/tag/v0.1.603"),
            null,
        )
        val check = AppUpdateCheck(installed, { ReleaseLookup.Found(latest) })
        runBlocking { check.checkNow("test") }
        composeTestRule.setContent { TotumTheme { AppShell(FakeAppContainer(appUpdate = check)) } }
    }

    @Test
    fun anOlderBuildIsToldSoOnEveryTab() {
        shellWith(installed = "0.1.602")

        composeTestRule.onNodeWithText(text(R.string.out_of_date_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText("🆕 You're on v0.1.602. v0.1.603 is out.").assertIsDisplayed()
        for (tab in listOf(R.string.destination_podcasts, R.string.destination_queue, R.string.destination_library)) {
            composeTestRule.onNodeWithText(text(tab)).performClick()
            composeTestRule.onNodeWithTag(OUT_OF_DATE_BANNER_TAG).assertIsDisplayed()
        }
    }

    @Test
    fun theLatestBuildShowsNoBanner() {
        shellWith(installed = "0.1.603")

        composeTestRule.onNodeWithTag(OUT_OF_DATE_BANNER_TAG).assertDoesNotExist()
    }
}
