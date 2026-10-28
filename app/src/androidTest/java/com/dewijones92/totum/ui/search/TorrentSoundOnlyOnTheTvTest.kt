package com.dewijones92.totum.ui.search

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import com.dewijones92.totum.data.search.SearchHit
import com.dewijones92.totum.data.torrent.TorrentLink
import com.dewijones92.totum.playback.fake.FakePlaybackController
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.cast.LocalScreenControl
import org.junit.Rule
import org.junit.Test

class TorrentSoundOnlyOnTheTvTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val controller = FakePlaybackController()

    private fun hit(title: String) = SearchHit.Torrent(
        title = title,
        subtitle = "12 seeders",
        artworkUrl = null,
        link = TorrentLink.ViaIndexer("/1/download"),
        seeders = 12,
        sizeBytes = 700_000_000,
        indexer = "test",
    )

    private fun show(vararg titles: String) = composeTestRule.setContent {
        TotumTheme {
            CompositionLocalProvider(LocalScreenControl provides controller) {
                Column { titles.forEach { TorrentHitRow(hit(it), onPlay = {}) } }
            }
        }
    }

    @Test
    fun whileCastingAnXvidResultSaysItWillBeSoundOnly() {
        controller.castDeviceFlow.value = "Living room TV"
        show("Public Domain Film 1961 DVDRip XviD", "Public Domain Film 1961 1080p x264")

        composeTestRule.onNodeWithText("🔊 Sound only on the TV (XviD)", useUnmergedTree = true).assertExists()
        composeTestRule.onAllNodesWithTag(TORRENT_SOUND_ONLY_TAG, useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun notCastingNothingIsSaid() {
        show("Public Domain Film 1961 DVDRip XviD")

        composeTestRule.onAllNodesWithTag(TORRENT_SOUND_ONLY_TAG, useUnmergedTree = true).assertCountEquals(0)
    }
}
