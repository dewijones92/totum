package com.dewijones92.totum.ui

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dewijones92.totum.R
import com.dewijones92.totum.data.search.SearchSection
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.search.SearchViewModel
import com.dewijones92.totum.ui.search.SectionMessage
import com.dewijones92.totum.ui.search.hitSection
import com.dewijones92.totum.ui.search.torrentSection
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What each search section looks like in each of its states.
 *
 * The states exist because results now arrive one source at a time (Dewi, 2026-08-07: *"the search
 * in the app is quite slow as its blocked by the torrent search"*), and the UI claim is the whole
 * point of that change: a section still waiting must **say so and keep its heading**, so the screen
 * reads as "more is coming" rather than "that is everything". A section that answered with nothing
 * must disappear entirely, or every search would end in a wall of empty headings.
 *
 * Rendered directly rather than through the screen: this is about one section's four states, and
 * driving the whole screen would need a view model, a queue and three fakes to assert something none
 * of them are involved in.
 */
@RunWith(AndroidJUnit4::class)
class SearchSectionStatesTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /**
     * One section, in whichever state it is given, with a trivial row.
     *
     * The generic renderer rather than the whole screen: the claim under test is what a STATE looks
     * like, and every section shares this one function — so driving `SearchScreen` would need a view
     * model, a queue and three fakes to assert something none of them take part in.
     */
    private fun show(
        section: SearchSection<List<String>>,
        title: Int = R.string.destination_videos,
        failure: (@Composable (SearchSection.Failed) -> Unit)? = null,
    ) {
        composeTestRule.setContent {
            TotumTheme {
                LazyColumn(modifier = Modifier.testTag("results")) {
                    if (failure == null) {
                        hitSection({ stringResource(title) }, section) { row -> Text(row) }
                    } else {
                        hitSection({ stringResource(title) }, section, failure) { row -> Text(row) }
                    }
                }
            }
        }
    }

    /** THE POINT. A section still out keeps its heading, so the screen says more is coming. */
    @Test
    fun `a section that is still searching keeps its heading`() {
        show(SearchSection.Searching)

        composeTestRule.onNodeWithText("Videos", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a section that answered shows its results`() {
        show(SearchSection.Found(listOf("A found video")))

        composeTestRule.onNodeWithText("A found video", substring = true).assertIsDisplayed()
    }

    /**
     * A source that answered with nothing says nothing.
     *
     * Without this every search would end in a column of empty headings — and with three sections
     * arriving separately, that noise would be on screen for most of a search rather than none of it.
     */
    @Test
    fun `a section that found nothing is not shown at all`() {
        show(SearchSection.Found(emptyList()), title = R.string.destination_podcasts)

        assertEquals(
            "an empty section must not leave a heading behind",
            0,
            composeTestRule.onAllNodesWithText("Podcasts", substring = true).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun `a failing section explains itself rather than looking empty`() {
        show(SearchSection.Failed("itunes said no"), title = R.string.destination_podcasts)

        composeTestRule.onNodeWithText("Podcasts", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("couldn't be reached", substring = true).assertIsDisplayed()
    }

    /**
     * The home-server section gets its OWN wording, because the usual reason is where you are.
     *
     * A generic "couldn't be reached" would send someone looking for a fault; the server is only on
     * the home network or the VPN.
     */
    @Test
    fun `an unreachable home server says where to be rather than that something broke`() {
        show(
            SearchSection.Failed("no route"),
            title = R.string.search_section_torrents,
            failure = { _ -> SectionMessage(stringResource(R.string.search_torrents_unreachable)) },
        )

        composeTestRule.onNodeWithText("home Wi-Fi or VPN", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a slow home server says it is slow rather than unreachable`() {
        composeTestRule.setContent {
            TotumTheme {
                LazyColumn {
                    torrentSection(
                        SearchViewModel.Results.Loaded(
                            podcasts = SearchSection.Searching,
                            videos = SearchSection.Searching,
                            torrents = SearchSection.Failed("indexers slow", slow = true),
                        ),
                        onPlayTorrent = {},
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("taking a while", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("home Wi-Fi or VPN", substring = true).assertDoesNotExist()
    }

    @Test
    fun `results already in show while a late indexer is still being asked`() {
        show(
            SearchSection.Found(listOf("Sintel 1080p"), stillWaitingFor = listOf("1337x")),
            title = R.string.search_section_torrents
        )

        composeTestRule.onNodeWithText("Sintel 1080p").assertIsDisplayed()
        composeTestRule.onNodeWithText("Still searching 1337x", substring = true).assertIsDisplayed()
    }

    /** With no home server there is no heading to explain away. */
    @Test
    fun `an absent section leaves no trace`() {
        show(SearchSection.Absent, title = R.string.search_section_torrents)

        assertEquals(
            0,
            composeTestRule.onAllNodesWithText("home server", substring = true).fetchSemanticsNodes().size,
        )
    }
}
