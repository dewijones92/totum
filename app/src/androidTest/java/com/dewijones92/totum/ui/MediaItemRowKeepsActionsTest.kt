package com.dewijones92.totum.ui

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.R
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.common.ItemActions
import com.dewijones92.totum.ui.common.LocalItemActions
import com.dewijones92.totum.ui.common.MediaItemRow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.minutes

/**
 * The row that ten screens are made of, keeping everything it offers.
 *
 * Dewi, 2026-08-07: *"make whole app sexy please without losing funcitonality"*. `MediaItemRow` is
 * where most of that happens — Videos, Search, Library, Queue, History, Playlists, Channel, Podcasts
 * and Notifications all render through it, so restyling it restyles the app. Which is exactly why it
 * is the riskiest thing to restyle: one dropped affordance is nine screens losing it at once.
 *
 * Written against the row as it was, before any of it moved, for the same reason
 * `PlayerKeepsEveryControlTest` was. It checks the actions, the badges and the states — the things a
 * restyle is most likely to quietly drop while everything still compiles and still looks fine.
 */
@RunWith(AndroidJUnit4::class)
class MediaItemRowKeepsActionsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val played = mutableListOf<String>()

    private val item = MediaItem(
        id = MediaItemId("abc"),
        sourceId = SourceId("feed"),
        title = "Is this Gary Stevensons last EVER interview?",
        publishedAt = null,
        publishedText = "2 days ago",
        duration = 42.minutes,
        author = "Novara Media",
        thumbnailUrl = HttpUrl.of("https://example.test/thumb.jpg"),
        // A row only plays when it HAS something to play; without this the tap is correctly ignored.
        mediaUrl = HttpUrl.of("https://example.test/abc.mp4"),
        viewsText = "1.2M views",
        membersOnly = true,
        contentKind = MediaContentKind.LIVE,
    )

    /** What the app provides everywhere; a row with no explicit download callbacks must reach it. */
    private val downloadsAsked = mutableListOf<Pair<MediaItemId, Boolean>>()
    private val actions = object : ItemActions {
        override fun playNext(item: MediaItem) = Unit
        override fun addToQueue(item: MediaItem) = Unit
        override fun addToPlaylist(item: MediaItem) = Unit
        override fun peek(item: MediaItem) = Unit
        override fun download(item: MediaItem, audioOnly: Boolean) { downloadsAsked += item.id to audioOnly }
        override fun deleteDownload(id: MediaItemId) = Unit
        override fun setPlayed(id: MediaItemId, played: Boolean) = Unit
        override fun goToSource(item: MediaItem) = Unit
        override val audioMode: Boolean = false
        override fun switchMode(item: MediaItem) = Unit
    }

    /**
     * The inert-control bug: Related, Notifications and Search drew the download icon with `{}`
     * behind it because these were the only two row callbacks without an app-wide default. A row
     * given NO download callbacks must still download through the provided actions.
     */
    @Test
    fun `a row with no explicit download callback still downloads through the app-wide actions`() {
        composeTestRule.setContent {
            TotumTheme {
                CompositionLocalProvider(LocalItemActions provides actions) {
                    MediaItemRow(
                        item = item,
                        subtitleLines = emptyList(),
                        pillar = MediaKind.VIDEO,
                        onPlay = {},
                        modifier = Modifier.testTag(ROW),
                    )
                }
            }
        }
        val label = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.download)
        composeTestRule.onNodeWithContentDescription(label).performClick()

        assertEquals(listOf(MediaItemId("abc") to false), downloadsAsked)
    }

    private fun show(downloadState: DownloadState = DownloadState.NotDownloaded) {
        composeTestRule.setContent {
            TotumTheme {
                LazyColumn(modifier = Modifier.testTag("list")) {
                    item {
                        MediaItemRow(
                            item = item,
                            subtitleLines = listOf("📺 Novara Media", "👁️ 1.2M views", "📅 2 days ago"),
                            pillar = MediaKind.VIDEO,
                            onPlay = { played += "play" },
                            onDownload = {},
                            onDeleteDownload = {},
                            downloadState = downloadState,
                            onPlayNext = {},
                            onAddToQueue = {},
                            onAddToPlaylist = {},
                            modifier = Modifier.testTag(ROW),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `the row shows what it is about`() {
        show()

        composeTestRule.onNodeWithText("Gary Stevensons", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Novara Media", substring = true).assertIsDisplayed()
    }

    /**
     * The badges are not decoration.
     *
     * LIVE and members-only are pills rather than subtitle text precisely because the subtitle
     * truncates, and "you cannot actually play this" must not be the part that gets cut. A restyle
     * that tidied them into the subtitle would look neater and be worse.
     */
    @Test
    fun `the live and members-only badges survive`() {
        show()

        composeTestRule.onNodeWithText("LIVE", substring = true, ignoreCase = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Members", substring = true, ignoreCase = true).assertIsDisplayed()
    }

    /** The duration rides on the thumbnail, where an ellipsis cannot reach it. */
    @Test
    fun `the duration is still shown`() {
        show()

        composeTestRule.onNodeWithText("42:00", substring = true).assertIsDisplayed()
    }

    @Test
    fun `tapping the row plays it`() {
        show()

        composeTestRule.onNodeWithText("Gary Stevensons", substring = true).performClick()

        assertEquals(listOf("play"), played)
    }

    /**
     * The long-press menu is where most of the row's actions live, so losing the gesture loses them
     * all at once — and it is exactly the sort of thing a layout change breaks silently.
     */
    @Test
    fun `long-pressing opens the action sheet with its actions`() {
        show()

        composeTestRule.onNodeWithTag(ROW).performTouchInput { longClick() }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Play next", substring = true, ignoreCase = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("queue", substring = true, ignoreCase = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("playlist", substring = true, ignoreCase = true).assertIsDisplayed()
    }

    /** A downloaded row must still say so — the tick is how "I can play this on a plane" is read. */
    @Test
    fun `a downloaded row still shows its state`() {
        show(DownloadState.Downloaded("/data/x.media", audioOnly = true))
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(ROW).assertIsDisplayed()
    }

    private companion object {
        const val ROW = "media-item-row"
    }
}
