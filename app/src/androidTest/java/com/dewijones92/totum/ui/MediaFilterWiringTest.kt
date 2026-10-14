package com.dewijones92.totum.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.di.fake.FakeAppContainer
import com.dewijones92.totum.domain.MediaFilter
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayState
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.common.LocalPlayStates
import com.dewijones92.totum.ui.common.rememberMediaItemActions
import com.dewijones92.totum.ui.videos.VideosContent
import com.dewijones92.totum.ui.videos.VideosViewModel
import org.junit.Rule
import org.junit.Test

/**
 * The progress filter, wired.
 *
 * The filtering itself is unit-tested in `:core:domain`; what needs a real composition is the
 * wiring — that the chips render, that tapping one applies it, and that a feed whose every item
 * is filtered out says so rather than looking broken or empty for the wrong reason.
 *
 * Driven directly rather than through the UI because the video feed needs a signed-in account
 * and the podcast feed needs a live subscription, neither of which an emulator has. Attempting
 * it by hand first cost a wrong feed URL and two failed subscribes; this is both faster and
 * permanent.
 */
class MediaFilterWiringTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val finished = item("finished", "Already watched")
    private val partWay = item("part-way", "Half watched")
    private val fresh = item("fresh", "Never opened")

    private val playStates = mapOf(
        finished.id to PlayState.Played,
        partWay.id to PlayState.InProgress(positionMs = 30, durationMs = 100),
        fresh.id to PlayState.Unplayed,
    )

    private fun setContent() {
        composeTestRule.setContent {
            var filter by remember { mutableStateOf(MediaFilter.ALL) }
            TotumTheme {
                CompositionLocalProvider(LocalPlayStates provides playStates) {
                    VideosContent(
                        // signedIn, or VideosContent renders its empty state instead of the feed — which is
                        // what the first run of this test actually hit.
                        state = VideosViewModel.UiState(
                            videos = listOf(finished, partWay, fresh),
                            signedIn = true,
                        ),
                        newUploadsCount = 0,
                        actions = rememberMediaItemActions(FakeAppContainer()),
                        onSubscribe = {},
                        onDialogClosed = {},
                        onPlay = {},
                        onDownload = {},
                        onDeleteDownload = {},
                        onSelectFeed = {},
                        onChannelClick = {},
                        onSwitchMode = {},
                        onGoToChannel = {},
                        onOpenPlaylists = {},
                        onOpenShorts = {},
                        onOpenNotifications = {},
                        onRefresh = {},
                        onSetSort = {},
                        onLoadMore = {},
                        filter = filter,
                        onSetFilter = { filter = it },
                    )
                }
            }
        }
    }

    @Test
    fun allShowsEverything() {
        setContent()

        composeTestRule.onNodeWithText("Already watched").assertIsDisplayed()
        composeTestRule.onNodeWithText("Half watched").assertIsDisplayed()
        composeTestRule.onNodeWithText("Never opened").assertIsDisplayed()
    }

    /** The headline behaviour: the finished item goes, the part-way one stays. */
    @Test
    fun unplayedHidesTheFinishedItem() {
        setContent()

        composeTestRule.onNodeWithText("🆕 Unplayed").performClick()

        composeTestRule.onNodeWithText("Already watched").assertDoesNotExist()
        composeTestRule.onNodeWithText("Half watched").assertIsDisplayed()
        composeTestRule.onNodeWithText("Never opened").assertIsDisplayed()
    }

    @Test
    fun inProgressKeepsOnlyTheStartedOne() {
        setContent()

        composeTestRule.onNodeWithText("⏯️ In progress").performClick()

        composeTestRule.onNodeWithText("Already watched").assertDoesNotExist()
        composeTestRule.onNodeWithText("Never opened").assertDoesNotExist()
        composeTestRule.onNodeWithText("Half watched").assertIsDisplayed()
    }

    /** An empty result must explain itself, or the feed looks broken rather than filtered. */
    @Test
    fun aFilterThatHidesEverythingSaysSo() {
        composeTestRule.setContent {
            TotumTheme {
                CompositionLocalProvider(LocalPlayStates provides mapOf(finished.id to PlayState.Played)) {
                    VideosContent(
                        state = VideosViewModel.UiState(videos = listOf(finished), signedIn = true),
                        newUploadsCount = 0,
                        actions = rememberMediaItemActions(FakeAppContainer()),
                        onSubscribe = {},
                        onDialogClosed = {},
                        onPlay = {},
                        onDownload = {},
                        onDeleteDownload = {},
                        onSelectFeed = {},
                        onChannelClick = {},
                        onSwitchMode = {},
                        onGoToChannel = {},
                        onOpenPlaylists = {},
                        onOpenShorts = {},
                        onOpenNotifications = {},
                        onRefresh = {},
                        onSetSort = {},
                        onLoadMore = {},
                        filter = MediaFilter.UNPLAYED,
                        onSetFilter = {},
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("🔍 Nothing matches this filter").assertIsDisplayed()
    }

    private fun item(id: String, title: String) = MediaItem(
        id = MediaItemId(id),
        sourceId = SourceId("feed"),
        title = title,
        publishedAt = null,
        duration = null,
        mediaUrl = HttpUrl.of("https://www.youtube.com/watch?v=$id"),
    )
}
