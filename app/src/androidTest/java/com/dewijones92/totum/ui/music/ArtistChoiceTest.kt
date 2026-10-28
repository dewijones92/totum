package com.dewijones92.totum.ui.music

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.common.LocalOpenMusicPage
import com.dewijones92.totum.ui.common.MusicPage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ArtistChoiceTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun aSongByTwoArtistsListsBothAndOpensTheOneTapped() {
        val opened = mutableListOf<MusicPage>()
        val nina = MusicPage.Artist("UCqj1HtTq76Bo6rBXNwrF5Uw", "Nina Simone")
        val bill = MusicPage.Artist("UCDjpW6k2Sfg_FTr1toLvffQ", "Bill Withers")
        composeTestRule.setContent {
            TotumTheme {
                CompositionLocalProvider(LocalOpenMusicPage provides { page: MusicPage -> opened += page }) {
                    ArtistChoiceScreen(MusicPage.ArtistChoice(listOf(nina, bill), "I Put A Spell On You"), onBack = {})
                }
            }
        }

        composeTestRule.onNodeWithText("Which artist?").assertExists()
        composeTestRule.onNodeWithTag("artist-choice-Nina Simone").assertExists()
        composeTestRule.onNodeWithTag("artist-choice-Bill Withers").performClick()

        assertEquals(listOf<MusicPage>(bill), opened)
    }
}
