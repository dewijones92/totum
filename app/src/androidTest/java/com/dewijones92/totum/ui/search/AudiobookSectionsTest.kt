package com.dewijones92.totum.ui.search

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.search.SearchHit
import com.dewijones92.totum.data.search.SearchSection
import com.dewijones92.totum.data.torrent.TorrentCategory
import com.dewijones92.totum.data.torrent.TorrentLink
import com.dewijones92.totum.theme.TotumTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AudiobookSectionsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val book = SearchHit.Book(
        title = "The War of the Worlds",
        subtitle = "H. G. Wells · LibriVox · 1,414,173 plays",
        artworkUrl = null,
        feedUrl = HttpUrl.of("https://archive.org/metadata/war_of_the_worlds_librivox"),
        identifier = "war_of_the_worlds_librivox",
        librivox = true,
    )

    private val bookTorrent = SearchHit.Torrent(
        title = "The War of the Worlds - H. G. Wells [MP3]",
        subtitle = "1 seeders · 0.20 GB · AudioBook Bay",
        artworkUrl = null,
        link = TorrentLink.ViaIndexer("/11/download"),
        seeders = 1,
        sizeBytes = 1,
        indexer = "AudioBook Bay",
        category = TorrentCategory.AUDIOBOOKS,
    )

    @Test
    fun freeBooksAndBookTorrentsEachHaveTheirSectionAndPlay() {
        val books = mutableListOf<String>()
        val torrents = mutableListOf<String>()
        val results = SearchViewModel.Results.Loaded(
            podcasts = SearchSection.Found(emptyList()),
            videos = SearchSection.Absent,
            songs = SearchSection.Absent,
            torrents = SearchSection.Absent,
            books = SearchSection.Found(listOf(book)),
            bookTorrents = SearchSection.Found(listOf(bookTorrent)),
        )
        composeTestRule.setContent {
            TotumTheme {
                LazyColumn { bookSections(results, { books += it.title }, { torrents += it.title }, {}) }
            }
        }

        composeTestRule.onNodeWithText("🎧 Audiobooks (free: LibriVox and the Internet Archive)").assertExists()
        composeTestRule.onNodeWithText("H. G. Wells · LibriVox · 1,414,173 plays").assertExists()
        composeTestRule.onNodeWithTag("$BOOK_PLAY_TAG-war_of_the_worlds_librivox").performClick()
        composeTestRule.onNodeWithText("🎧 Audiobook torrents").assertExists()
        composeTestRule.onNodeWithText("The War of the Worlds - H. G. Wells [MP3]").performClick()

        assertEquals(listOf("The War of the Worlds"), books)
        assertEquals(listOf("The War of the Worlds - H. G. Wells [MP3]"), torrents)
    }
}
