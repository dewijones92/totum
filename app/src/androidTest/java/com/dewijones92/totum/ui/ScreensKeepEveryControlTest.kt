package com.dewijones92.totum.ui

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.R
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.di.fake.FakeAppContainer
import com.dewijones92.totum.domain.MediaFilter
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.StorageUsage
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.common.FILTER_FIELD_TAG
import com.dewijones92.totum.ui.common.ProvidePlayStates
import com.dewijones92.totum.ui.common.rememberMediaItemActions
import com.dewijones92.totum.ui.library.DownloadSort
import com.dewijones92.totum.ui.library.LibraryContent
import com.dewijones92.totum.ui.queue.QueueScreen
import com.dewijones92.totum.ui.videos.VideosContent
import com.dewijones92.totum.ui.videos.VideosViewModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreensKeepEveryControlTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun item(id: String, title: String) = MediaItem(
        id = MediaItemId(id),
        sourceId = SourceId("s"),
        title = title,
        publishedAt = null,
        duration = null,
        mediaUrl = HttpUrl.of("https://x.test/$id.mp3"),
    )

    private fun tap(label: String) {
        composeTestRule.onNode(
            (hasText(label, substring = true) or hasContentDescription(label, substring = true)) and hasClickAction(),
            useUnmergedTree = false,
        ).performClick()
        composeTestRule.waitForIdle()
    }

    @Test
    fun `every library tile opens its page`() {
        val opened = mutableListOf<String>()
        composeTestRule.setContent {
            TotumTheme {
                LibraryContent(
                    downloaded = emptyList(),
                    inProgress = emptyList(),
                    failed = emptyList(),
                    storage = StorageUsage(itemCount = 0, usedBytes = 0, freeBytes = null),
                    sort = DownloadSort.DEFAULT,
                    onOpenPlaylists = { opened += "playlists" },
                    onOpenHistory = { opened += "history" },
                    onOpenSubscriptions = { opened += "subscriptions" },
                    onOpenAccount = { opened += "account" },
                    onPlay = {},
                    onDelete = {},
                    onCancel = {},
                    onCancelAll = {},
                    onRetry = {},
                    onDismiss = {},
                    onAddToPlaylist = {},
                    onSetSort = {},
                )
            }
        }

        tap(context.getString(R.string.playlists_title))
        tap(context.getString(R.string.all_subscriptions_title))
        tap(context.getString(R.string.history_title))
        tap(context.getString(R.string.destination_account))

        assertEquals(listOf("playlists", "subscriptions", "history", "account"), opened)
    }

    @Test
    fun `the signed-in videos header offers the bell and the sort and the filter`() {
        var bell = 0
        composeTestRule.setContent {
            TotumTheme {
                VideosContent(
                    state = VideosViewModel.UiState(
                        signedIn = true,
                        videos = listOf(item("v1", "A tennis match"), item("v2", "A cooking show")),
                    ),
                    newUploadsCount = 3,
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
                    onOpenNotifications = { bell++ },
                    onRefresh = {},
                    onSetSort = {},
                    onLoadMore = {},
                    filter = MediaFilter.ALL,
                    onSetFilter = {},
                )
            }
        }

        tap(context.getString(R.string.notifications_title))
        assertEquals("the bell opens the new uploads", 1, bell)

        tap(context.getString(R.string.sort_label))
        composeTestRule.onNode(hasText(context.getString(R.string.sort_oldest))).assertExistsAndClose()

        tap("Filter")
        composeTestRule.onNodeWithTag(FILTER_FIELD_TAG).assertExists()
    }

    @Test
    fun `clear all empties the queue`() {
        val container = FakeAppContainer()
        container.playbackQueue.playAll(
            listOf("Alpha", "Beta").mapIndexed { i, t -> PlayableItem(item("q$i", t), PlayHandle.Podcast()) }
        )
        composeTestRule.setContent {
            TotumTheme { ProvidePlayStates(container, onOpenSource = {}) { QueueScreen(container) } }
        }
        composeTestRule.waitForIdle()

        tap(context.getString(R.string.queue_clear_all))

        composeTestRule.waitUntil(TIMEOUT_MS) { container.playbackQueue.state.value.entries.isEmpty() }
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertExistsAndClose() {
        assertExists()
        performClick()
        composeTestRule.waitForIdle()
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
