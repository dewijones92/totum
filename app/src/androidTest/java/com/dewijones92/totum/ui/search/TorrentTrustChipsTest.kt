package com.dewijones92.totum.ui.search

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import com.dewijones92.totum.data.search.SearchHit
import com.dewijones92.totum.data.torrent.TorrentLink
import com.dewijones92.totum.theme.TotumTheme
import org.junit.Rule
import org.junit.Test

class TorrentTrustChipsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun hit(title: String, trust: List<String>) = SearchHit.Torrent(
        title = title,
        subtitle = "104 seeders · 1.60 GB · The Pirate Bay",
        artworkUrl = null,
        link = TorrentLink.ViaIndexer("/1/download"),
        seeders = 104,
        sizeBytes = 1_600_000_000,
        indexer = "The Pirate Bay",
        trust = trust,
        flagged = trust.any { it.startsWith("⚠️") },
    )

    @Test
    fun eachResultShowsItsTrustChipsAndAResultWithNoneShowsNoRow() {
        composeTestRule.setContent {
            TotumTheme {
                Column {
                    TorrentHitRow(
                        hit(
                            "Public Domain Film 1968 1080p",
                            listOf("💚 VIP uploader (someone)", "🌱 Well seeded (104 seeding)")
                        ),
                        onPlay = {}
                    )
                    TorrentHitRow(
                        hit("Public Domain Film 1968 HDCAM", listOf("⚠️ Looks like a cinema recording (HDCAM)")),
                        onPlay = {}
                    )
                    TorrentHitRow(hit("Public Domain Film 1968 720p", emptyList()), onPlay = {})
                }
            }
        }

        composeTestRule.onNodeWithText("💚 VIP uploader (someone)", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText(
            "⚠️ Looks like a cinema recording (HDCAM)",
            useUnmergedTree = true
        ).assertExists()
        composeTestRule.onAllNodesWithTag(TORRENT_TRUST_TAG, useUnmergedTree = true).assertCountEquals(2)
    }
}
