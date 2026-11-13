package com.dewijones92.totum.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.playback.PlaybackState
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.common.MediaItemRow
import com.dewijones92.totum.ui.player.SeekBar
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.minutes

@RunWith(AndroidJUnit4::class)
class LiveShowsLiveNotALengthTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun item(kind: MediaContentKind) = MediaItem(
        id = MediaItemId("abc123"),
        sourceId = SourceId("feed"),
        title = "A stream",
        publishedAt = null,
        duration = 42.minutes,
        author = "A channel",
        mediaUrl = HttpUrl.of("https://example.test/abc.mp4"),
        contentKind = kind,
    )

    private fun state(kind: MediaContentKind) = PlaybackState(
        itemId = MediaItemId("abc123"),
        title = "A stream",
        artist = "A channel",
        artworkUrl = null,
        kind = MediaKind.VIDEO,
        isPlaying = true,
        positionMs = 65_000,
        durationMs = 3_600_000,
        speed = 1f,
        contentKind = kind,
    )

    private fun showRow(kind: MediaContentKind) {
        composeTestRule.setContent {
            TotumTheme {
                MediaItemRow(item(kind), subtitleLines = emptyList(), pillar = MediaKind.VIDEO, onPlay = {})
            }
        }
    }

    @Test
    fun aLiveRowShowsLiveAndNoLength() {
        showRow(MediaContentKind.LIVE)

        composeTestRule.onNodeWithText("LIVE", substring = true).assertIsDisplayed()
        composeTestRule.onAllNodesWithText("42:00").assertCountEquals(0)
    }

    @Test
    fun anOrdinaryRowStillShowsItsLength() {
        showRow(MediaContentKind.STANDARD)

        composeTestRule.onNodeWithText("42:00").assertIsDisplayed()
    }

    @Test
    fun aLiveSeekBarSaysLiveInsteadOfTimes() {
        composeTestRule.setContent { TotumTheme { SeekBar(state(MediaContentKind.LIVE), onSeekTo = {}) } }

        composeTestRule.onNodeWithText("LIVE", substring = true).assertIsDisplayed()
        composeTestRule.onAllNodesWithText("1:05").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("1:00:00").assertCountEquals(0)
    }

    @Test
    fun anOrdinarySeekBarShowsItsTimes() {
        composeTestRule.setContent { TotumTheme { SeekBar(state(MediaContentKind.STANDARD), onSeekTo = {}) } }

        composeTestRule.onNodeWithText("1:05").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("LIVE", substring = true).assertCountEquals(0)
    }
}
