package com.dewijones92.totum.ui

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.R
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.common.PageToken
import com.dewijones92.totum.di.fake.FakeAppContainer
import com.dewijones92.totum.domain.MediaFilter
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.channel.ChannelContent
import com.dewijones92.totum.ui.channel.ChannelViewModel
import com.dewijones92.totum.ui.common.FILTER_FIELD_TAG
import com.dewijones92.totum.ui.common.FilterableList
import com.dewijones92.totum.ui.common.ProvidePlayStates
import com.dewijones92.totum.ui.common.rememberMediaItemActions
import com.dewijones92.totum.ui.queue.QueueScreen
import com.dewijones92.totum.ui.videos.VideosContent
import com.dewijones92.totum.ui.videos.VideosViewModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ListFilterTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun type(text: String) {
        composeTestRule.onNodeWithTag(FILTER_FIELD_TAG).performTextInput(text)
        composeTestRule.waitForIdle()
    }

    private fun item(id: String, title: String, author: String? = null) = MediaItem(
        id = MediaItemId(id),
        sourceId = SourceId("s"),
        title = title,
        publishedAt = null,
        duration = null,
        author = author,
        mediaUrl = HttpUrl.of("https://x.test/$id.mp3"),
    )

    @Test
    fun `typing narrows the list with typos forgiven and clearing brings it all back`() {
        val titles = listOf("Brighton sensational", "Chelsea collapse", "Brentford and Brighton")
        composeTestRule.setContent {
            TotumTheme {
                FilterableList("test", titles, { listOf(it) }) { shown, _ ->
                    LazyColumn { items(shown) { Text(it) } }
                }
            }
        }

        type("brigton")

        composeTestRule.onNodeWithText("Brighton sensational").assertExists()
        composeTestRule.onNodeWithText("Brentford and Brighton").assertExists()
        composeTestRule.onAllNodesWithText("Chelsea collapse").assertCountEquals(0)
        composeTestRule.onNodeWithText(context.getString(R.string.filter_count, 2, 3)).assertExists()

        composeTestRule.onNodeWithContentDescription(context.getString(R.string.filter_clear)).performClick()
        composeTestRule.onNodeWithText("Chelsea collapse").assertExists()
    }

    @Test
    fun `a query that matches nothing says so`() {
        composeTestRule.setContent {
            TotumTheme {
                FilterableList("test", listOf("Only item"), { listOf(it) }) { shown, _ ->
                    LazyColumn { items(shown) { Text(it) } }
                }
            }
        }

        type("xyzzy")

        composeTestRule.onNodeWithText(context.getString(R.string.filter_no_matches, "xyzzy")).assertExists()
    }

    @Test
    fun `filtering a paged feed does not page on its own`() {
        var pagesAsked = 0
        val videos = (1..30).map { item("v$it", if (it == 30) "The one about tennis" else "Video number $it") }
        composeTestRule.setContent {
            TotumTheme {
                VideosContent(
                    state = VideosViewModel.UiState(videos = videos, canLoadMore = true, signedIn = true),
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
                    onLoadMore = { pagesAsked++ },
                    filter = MediaFilter.ALL,
                    onSetFilter = {},
                )
            }
        }
        composeTestRule.waitForIdle()
        val before = pagesAsked

        type("tennis")
        composeTestRule.mainClock.advanceTimeBy(SETTLE_MS)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("The one about tennis").assertExists()
        assertEquals("a filtered feed must not page by itself", before, pagesAsked)
    }

    @Test
    fun `a filtered queue acts on the right entry and offers no drag`() {
        val container = FakeAppContainer()
        val queued = listOf("Alpha show", "Beta match report", "Gamma show").mapIndexed { i, title ->
            PlayableItem(item("e$i", title), PlayHandle.Podcast())
        }
        container.playbackQueue.playAll(queued)
        composeTestRule.setContent {
            TotumTheme { ProvidePlayStates(container, onOpenSource = {}) { QueueScreen(container) } }
        }
        composeTestRule.waitForIdle()

        type("gamma")

        composeTestRule.onNodeWithText("Gamma show").assertExists()
        composeTestRule.onAllNodesWithText("Alpha show").assertCountEquals(0)
        composeTestRule.onAllNodesWithContentDescription(context.getString(R.string.queue_reorder)).assertCountEquals(0)
        composeTestRule.onAllNodesWithContentDescription(context.getString(R.string.queue_menu))[0].performClick()
        composeTestRule.onNodeWithText(context.getString(R.string.queue_remove)).performClick()
        composeTestRule.waitUntil(TIMEOUT_MS) { container.playbackQueue.state.value.entries.size == 2 }

        assertEquals(
            listOf("e0", "e1"),
            container.playbackQueue.state.value.entries.map { it.item.item.id.value },
        )
    }

    @Test
    fun `move to top on a filtered queue row moves that entry from where it really is`() {
        val container = FakeAppContainer()
        val queued = listOf("Alpha show", "Beta match report", "Gamma show").mapIndexed { i, title ->
            PlayableItem(item("e$i", title), PlayHandle.Podcast())
        }
        container.playbackQueue.playAll(queued)
        composeTestRule.setContent {
            TotumTheme { ProvidePlayStates(container, onOpenSource = {}) { QueueScreen(container) } }
        }
        composeTestRule.waitForIdle()

        type("gamma")
        composeTestRule.onAllNodesWithContentDescription(context.getString(R.string.queue_menu))[0].performClick()
        composeTestRule.onNodeWithText(context.getString(R.string.queue_move_to_top)).performClick()
        composeTestRule.waitUntil(TIMEOUT_MS) {
            container.playbackQueue.state.value.entries.first().item.item.id.value == "e2"
        }

        assertEquals(
            listOf("e2", "e0", "e1"),
            container.playbackQueue.state.value.entries.map { it.item.item.id.value },
        )
    }

    private fun channelContent(state: ChannelViewModel.UiState, sourceKey: String, onLoadMore: () -> Unit = {}) {
        composeTestRule.setContent {
            TotumTheme {
                var key by remember { mutableStateOf(sourceKey) }
                channelKey = { key = it }
                ChannelContent(
                    state = state,
                    onBack = {},
                    onOpenGroups = {},
                    onToggleSubscribed = {},
                    onSelectTab = {},
                    onSearch = {},
                    onPlay = {},
                    onDownload = {},
                    onDeleteDownload = {},
                    onAddToPlaylist = {},
                    onOpenPlaylist = {},
                    onLoadMore = onLoadMore,
                    sourceKey = key,
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    private var channelKey: (String) -> Unit = {}

    private fun channelState(vararg titles: String) = ChannelViewModel.UiState(
        title = "A channel",
        videos = ChannelViewModel.TabState(
            loaded = true,
            items = titles.mapIndexed { i, t -> item("c$i", t) },
            next = PageToken("more"),
        ),
    )

    @Test
    fun `filtering a channel tab does not page on its own`() {
        var pagesAsked = 0
        channelContent(
            channelState(*Array(30) { if (it == 29) "Tennis special" else "Upload $it" }),
            "A"
        ) { pagesAsked++ }
        val before = pagesAsked

        type("tennis")
        composeTestRule.mainClock.advanceTimeBy(SETTLE_MS)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Tennis special").assertExists()
        assertEquals(before, pagesAsked)
    }

    @Test
    fun `a filter does not follow you to a different channel`() {
        channelContent(channelState("Alpha upload", "Beta upload"), "channel-A")
        type("alpha")
        composeTestRule.onAllNodesWithText("Beta upload").assertCountEquals(0)

        composeTestRule.runOnIdle { channelKey("channel-B") }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Beta upload").assertExists()
    }

    @Test
    fun `tapping a filtered queue row plays that entry`() {
        val container = FakeAppContainer()
        val queued = listOf("Alpha show", "Beta match report", "Gamma show").mapIndexed { i, title ->
            PlayableItem(item("e$i", title), PlayHandle.Podcast())
        }
        container.playbackQueue.playAll(queued)
        composeTestRule.setContent {
            TotumTheme { ProvidePlayStates(container, onOpenSource = {}) { QueueScreen(container) } }
        }
        composeTestRule.waitForIdle()

        type("gamma")
        composeTestRule.onNodeWithText("Gamma show").performClick()
        composeTestRule.waitUntil(TIMEOUT_MS) { container.playbackQueue.state.value.currentIndex == 2 }

        assertEquals(2, container.playbackQueue.state.value.currentIndex)
    }

    private companion object {
        const val SETTLE_MS = 2_000L
        const val TIMEOUT_MS = 5_000L
    }
}
