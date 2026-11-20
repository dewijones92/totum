package com.dewijones92.totum.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.common.Page
import com.dewijones92.totum.data.search.SearchHit
import com.dewijones92.totum.data.search.SearchSection
import com.dewijones92.totum.di.fake.FakeAppContainer
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.common.rememberMediaItemActions
import com.dewijones92.totum.ui.search.SearchContent
import com.dewijones92.totum.ui.search.SearchMore
import com.dewijones92.totum.ui.search.SearchViewModel.Results
import com.dewijones92.totum.ui.search.SearchViewModel.UiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class YoursSeriesRowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val opened = mutableListOf<SearchHit.YoursSeries>()

    private val series = SearchHit.YoursSeries(
        id = "0123456789abcdef",
        title = "Bluey",
        year = 2018,
        artworkUrl = HttpUrl.of("https://pi.example/jellyfin/image/0123456789abcdef"),
    )

    private fun show() {
        val results = Results.Loaded(
            podcasts = SearchSection.Found(emptyList()),
            videos = SearchSection.Found(Page.last(emptyList())),
            torrents = SearchSection.Absent,
            yours = SearchSection.Found(listOf(series)),
        )
        composeTestRule.setContent {
            TotumTheme {
                SearchContent(
                    state = UiState(results = results),
                    onSearch = {},
                    onQueryChange = {},
                    onSubscribe = {},
                    onPlayVideo = {},
                    onPlaySong = {},
                    onPlayTorrent = {},
                    onRemoveHistory = {},
                    onClearHistory = {},
                    actions = rememberMediaItemActions(FakeAppContainer()),
                    onGoToChannel = {},
                    more = SearchMore(),
                    onOpenSeries = { opened += it },
                )
            }
        }
        holdForAScreenshot()
    }

    private fun holdForAScreenshot() {
        val seconds = InstrumentationRegistry.getArguments().getString("holdSeconds")?.toLongOrNull() ?: 0L
        if (seconds > 0) Thread.sleep(seconds * MILLIS)
    }

    @Test
    fun aSeriesRowSaysWhatItIsAndWhereItIs() {
        show()

        composeTestRule.onNodeWithText("📺 Bluey").assertIsDisplayed()
        composeTestRule.onNodeWithText("2018", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("🎞️ In Jellyfin", substring = true).assertIsDisplayed()
    }

    @Test
    fun tappingASeriesRowOpensThatSeries() {
        show()

        composeTestRule.onNodeWithText("📺 Bluey").performClick()

        assertEquals(listOf("0123456789abcdef"), opened.map { it.id })
    }

    private companion object {
        const val MILLIS = 1000L
    }
}
