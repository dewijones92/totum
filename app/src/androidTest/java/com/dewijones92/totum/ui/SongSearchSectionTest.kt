package com.dewijones92.totum.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.common.Page
import com.dewijones92.totum.data.search.SearchHit
import com.dewijones92.totum.data.search.SearchSection
import com.dewijones92.totum.di.fake.FakeAppContainer
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.common.rememberMediaItemActions
import com.dewijones92.totum.ui.search.SearchContent
import com.dewijones92.totum.ui.search.SearchViewModel.Results
import com.dewijones92.totum.ui.search.SearchViewModel.UiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Songs on the search screen.
 *
 * Dewi, 2026-08-11: *"I wanna be able to stream YouTube Music … in my app"*. Stage one is exactly
 * this — songs in the search you already use, playing through the player that already exists.
 *
 * Three claims a unit test cannot make: that the section is on screen with the artist and album a
 * person scans for, that it sits **above** the video results, and that tapping a row asks for that
 * song rather than for something else.
 */
@RunWith(AndroidJUnit4::class)
class SongSearchSectionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val played = mutableListOf<SearchHit.Song>()

    private fun show(results: Results) {
        composeTestRule.setContent {
            TotumTheme {
                SearchContent(
                    state = UiState(results = results),
                    onSearch = {},
                    onQueryChange = {},
                    onSubscribe = {},
                    onPlayVideo = {},
                    onPlaySong = { played += it },
                    onPlayTorrent = {},
                    onRemoveHistory = {},
                    onClearHistory = {},
                    actions = rememberMediaItemActions(FakeAppContainer()),
                    onGoToChannel = {},
                    onLoadMoreVideos = {},
                )
            }
        }
    }

    @Test
    fun songsAreShownWithTheirArtistAndAlbum() {
        show(loaded(songs = SearchSection.Found(listOf(song()))))

        composeTestRule.onNodeWithText("Songs", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Feeling Good", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Nina Simone", substring = true).assertIsDisplayed()
    }

    @Test
    fun tappingASongAsksToPlayThatSong() {
        show(loaded(songs = SearchSection.Found(listOf(song(), song(title = "Sinnerman")))))

        composeTestRule.onNodeWithText("Sinnerman", substring = true).performClick()

        assertEquals(listOf("Sinnerman"), played.map { it.title })
    }

    /**
     * Songs above videos, because a music query is answered better by YouTube Music than by video
     * search — and putting the better answer second makes the worse one look like the answer.
     */
    @Test
    fun songsSitAboveTheVideoResults(): Unit = with(composeTestRule) {
        show(
            loaded(
                songs = SearchSection.Found(listOf(song())),
                videos = SearchSection.Found(Page.last(listOf(video()))),
            ),
        )

        val songTop = onNodeWithText("Feeling Good", substring = true).fetchSemanticsNode().positionInRoot.y
        val videoTop = onNodeWithText("A music video", substring = true).fetchSemanticsNode().positionInRoot.y

        assertEquals("the songs section must come first", true, songTop < videoTop)
    }

    /** An empty music answer must leave no heading behind, like every other section. */
    @Test
    fun aSearchThatFoundNoSongsShowsNoSongsHeading() {
        show(loaded(songs = SearchSection.Found(emptyList())))

        assertEquals(
            0,
            composeTestRule.onAllNodesWithText("Songs", substring = true).fetchSemanticsNodes().size,
        )
    }

    private fun loaded(
        songs: SearchSection<List<SearchHit.Song>> = SearchSection.Found(emptyList()),
        videos: SearchSection<Page<SearchHit.Video>> = SearchSection.Found(Page.last(emptyList())),
    ) = Results.Loaded(
        podcasts = SearchSection.Found(emptyList()),
        videos = videos,
        songs = songs,
        torrents = SearchSection.Absent,
    )

    private fun song(title: String = "Feeling Good") = SearchHit.Song(
        title = title,
        subtitle = "Nina Simone • I Put A Spell On You",
        artworkUrl = null,
        watchUrl = HttpUrl.of("https://www.youtube.com/watch?v=${title.filter { it.isLetterOrDigit() }}"),
        durationSeconds = 174,
        artist = "Nina Simone",
        album = "I Put A Spell On You",
    )

    private fun video() = SearchHit.Video(
        title = "A music video",
        subtitle = "A Channel",
        artworkUrl = null,
        watchUrl = HttpUrl.of("https://www.youtube.com/watch?v=avideo"),
        durationSeconds = 200,
    )
}
