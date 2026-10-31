package com.dewijones92.totum.ui.search

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.dewijones92.totum.data.search.SearchHit
import com.dewijones92.totum.data.torrent.TorrentLink
import com.dewijones92.totum.theme.TotumTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TorrentControlsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun hit(title: String, seeders: Int, resolution: String, trusted: Boolean) = SearchHit.Torrent(
        title = title,
        subtitle = null,
        artworkUrl = null,
        link = TorrentLink.ViaIndexer("/1/download"),
        seeders = seeders,
        sizeBytes = 1,
        indexer = "x",
        resolution = resolution,
        trusted = trusted,
    )

    @Test
    fun theBestCopyPlaysAndTheFilterNarrowsItsChoice() {
        val played = mutableListOf<String>()
        val shelf = TorrentShelf()
        val hits = listOf(
            hit("Public Domain Film 1968 1080p", 60, "1080p", trusted = true),
            hit("Public Domain Film 1968 720p", 300, "720p", trusted = false),
        )
        composeTestRule.setContent { TotumTheme { TorrentControls(hits, shelf) { played += it.title } } }

        composeTestRule.onNodeWithText("▶️ Play the best copy: 1080p · 💚 trusted · seeders: 60").performClick()
        composeTestRule.onNodeWithTag("$TORRENT_FILTER_TAG-720p").performClick()
        composeTestRule.onNodeWithTag(TORRENT_BEST_TAG).performClick()

        assertEquals(listOf("Public Domain Film 1968 1080p", "Public Domain Film 1968 720p"), played)
        assertEquals("720p", shelf.resolution)
    }
}
