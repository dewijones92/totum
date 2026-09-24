package com.dewijones92.totum.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dewijones92.totum.R
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.podcast.fake.FakePodcastRepository
import com.dewijones92.totum.di.fake.FakeAppContainer
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceActivity
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.Subscription
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.common.FILTER_FIELD_TAG
import com.dewijones92.totum.ui.common.MediaItemRow
import com.dewijones92.totum.ui.common.ProvidePlayStates
import com.dewijones92.totum.ui.common.SELECTION_BAR_TAG
import com.dewijones92.totum.ui.common.SelectableList
import com.dewijones92.totum.ui.common.mediaBulkActions
import com.dewijones92.totum.ui.common.rememberSelection
import com.dewijones92.totum.ui.playlist.LocalPlaylistsScreen
import com.dewijones92.totum.ui.podcasts.PodcastFeedScreen
import com.dewijones92.totum.ui.queue.QueueScreen
import com.dewijones92.totum.ui.subscriptions.AllSubscriptionsContent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class MultiSelectTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val activity get() = composeTestRule.activity

    private fun item(id: String, title: String) = MediaItem(
        id = MediaItemId(id),
        sourceId = SourceId("https://feeds.example.com/show.rss"),
        title = title,
        publishedAt = null,
        duration = null,
        author = "The Show",
        mediaUrl = HttpUrl.of("https://cdn.example.com/$id.mp3"),
    )

    private val items = listOf(item("a", "Alpha episode"), item("b", "Beta episode"), item("c", "Gamma episode"))

    private fun longPress(title: String) {
        composeTestRule.onNodeWithText(title).performTouchInput { longClick() }
        composeTestRule.waitForIdle()
    }

    private fun count(n: Int) = activity.resources.getQuantityString(R.plurals.selected_count, n, n)

    private fun showPlainList(container: FakeAppContainer) {
        composeTestRule.setContent {
            TotumTheme {
                ProvidePlayStates(container, onOpenSource = {}) {
                    val selection = rememberSelection("test")
                    SelectableList(selection, items, items, { it.id.value }, mediaBulkActions()) {
                        LazyColumn {
                            items(items, key = { it.id.value }) {
                                MediaItemRow(
                                    item = it,
                                    subtitleLines = emptyList(),
                                    pillar = MediaKind.PODCAST,
                                    onPlay = {}
                                )
                            }
                        }
                    }
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `long press starts selecting and taps then add or remove rows`() {
        showPlainList(FakeAppContainer())

        longPress("Alpha episode")
        composeTestRule.onNodeWithText(count(1)).assertExists()
        composeTestRule.onAllNodesWithContentDescription(activity.getString(R.string.queue_menu)).assertCountEquals(0)

        composeTestRule.onNodeWithText("Gamma episode").performClick()
        composeTestRule.onNodeWithText(count(2)).assertExists()

        composeTestRule.onNodeWithText("Alpha episode").performClick()
        composeTestRule.onNodeWithText(count(1)).assertExists()
    }

    @Test
    fun `back ends selecting rather than leaving the screen`() {
        showPlainList(FakeAppContainer())
        longPress("Beta episode")

        composeTestRule.runOnUiThread { activity.onBackPressedDispatcher.onBackPressed() }
        composeTestRule.waitForIdle()

        composeTestRule.onAllNodesWithTag(SELECTION_BAR_TAG).assertCountEquals(0)
        assertTrue(!activity.isFinishing)
    }

    @Test
    fun `add to queue and play next keep the order of the list`() {
        val container = FakeAppContainer()
        showPlainList(container)
        longPress("Alpha episode")
        composeTestRule.onNodeWithText("Gamma episode").performClick()

        composeTestRule.onNodeWithText(activity.getString(R.string.queue_add)).performClick()
        composeTestRule.waitUntil(TIMEOUT_MS) { container.playbackQueue.state.value.entries.size == 2 }
        assertEquals(listOf("a", "c"), container.playbackQueue.state.value.entries.map { it.item.item.id.value })
        composeTestRule.onAllNodesWithTag(SELECTION_BAR_TAG).assertCountEquals(0)

        longPress("Beta episode")
        composeTestRule.onNodeWithText("Alpha episode").performClick()
        composeTestRule.onNodeWithText(activity.getString(R.string.queue_play_next)).performClick()
        composeTestRule.waitUntil(TIMEOUT_MS) { container.playbackQueue.state.value.entries.size == 3 }
        assertEquals(
            "play next puts the chosen items first, in list order",
            listOf("a", "b", "c"),
            container.playbackQueue.state.value.entries.map { it.item.item.id.value },
        )
    }

    @Test
    fun `in the queue several rows move to the top together in order and can be removed together`() {
        val container = FakeAppContainer()
        val queued = listOf("One", "Two", "Three", "Four").mapIndexed { i, t ->
            PlayableItem(item("q$i", t), PlayHandle.Podcast())
        }
        container.playbackQueue.playAll(queued)
        composeTestRule.setContent {
            TotumTheme { ProvidePlayStates(container, onOpenSource = {}) { QueueScreen(container) } }
        }
        composeTestRule.waitForIdle()
        fun order() = container.playbackQueue.state.value.entries.map { it.item.item.title }

        longPress("Two")
        composeTestRule.onNodeWithText("Four").performClick()
        composeTestRule.onNodeWithText(activity.getString(R.string.queue_move_to_top)).performClick()
        composeTestRule.waitUntil(TIMEOUT_MS) { order().first() == "Two" }
        assertEquals(listOf("Two", "Four", "One", "Three"), order())

        longPress("One")
        composeTestRule.onNodeWithText("Three").performClick()
        composeTestRule.onNodeWithText(activity.getString(R.string.queue_remove)).performClick()
        composeTestRule.waitUntil(TIMEOUT_MS) { order().size == 2 }
        assertEquals(listOf("Two", "Four"), order())
    }

    @Test
    fun `select all takes only what the filter shows`() {
        val feedUrl = HttpUrl.of("https://feeds.example.com/show.rss")
        val feed = MediaSource.PodcastFeed(SourceId(feedUrl.value), "The Show", feedUrl)
        val container = FakeAppContainer(
            podcastRepository = FakePodcastRepository(
                initialSubscriptions = listOf(Subscription(feed, Instant.EPOCH)),
                initialEpisodes = items,
            ),
        )
        composeTestRule.setContent {
            TotumTheme {
                ProvidePlayStates(container, onOpenSource = {}) { PodcastFeedScreen(container, feed, onBack = {}) }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(FILTER_FIELD_TAG).performTextInput("gamma")
        composeTestRule.waitForIdle()
        longPress("Gamma episode")
        composeTestRule.onNodeWithText(activity.getString(R.string.select_all)).performClick()

        composeTestRule.onNodeWithText(count(1)).assertExists()
    }

    @Test
    fun `deleting several playlists asks first and deletes only when confirmed`() {
        val container = FakeAppContainer()
        val store = container.localPlaylistStore
        runBlocking { listOf("Road trip", "Gym", "Sleep").forEach { store.create(it) } }
        composeTestRule.setContent {
            TotumTheme {
                ProvidePlayStates(container, onOpenSource = {}) {
                    LocalPlaylistsScreen(container, onBack = {}, onOpen = {})
                }
            }
        }
        composeTestRule.waitForIdle()
        fun names() = runBlocking { store.observePlaylists().first().map { it.name } }.sorted()

        longPress("Road trip")
        composeTestRule.onNodeWithText("Sleep").performClick()
        composeTestRule.onNodeWithText(activity.getString(R.string.playlist_delete)).performClick()
        composeTestRule.onNodeWithText(activity.getString(R.string.cancel)).performClick()
        composeTestRule.waitForIdle()
        assertEquals("cancel deletes nothing", listOf("Gym", "Road trip", "Sleep"), names())

        composeTestRule.onNodeWithText(activity.getString(R.string.playlist_delete)).performClick()
        composeTestRule.onAllNodesWithText(activity.getString(R.string.playlist_delete))[1].performClick()
        composeTestRule.waitUntil(TIMEOUT_MS) { names().size == 1 }
        assertEquals(listOf("Gym"), names())
    }

    @Test
    fun `unsubscribing several sources asks first and hands over exactly those`() {
        val show = MediaSource.PodcastFeed(SourceId("s1"), "A Show", HttpUrl.of("https://f.example.com/1.rss"))
        val channel = MediaSource.VideoChannel(
            SourceId("c1"),
            "A Channel",
            HttpUrl.of("https://www.youtube.com/channel/UC1")
        )
        val other = MediaSource.VideoChannel(
            SourceId("c2"),
            "Other Channel",
            HttpUrl.of("https://www.youtube.com/channel/UC2")
        )
        var unsubscribed: List<MediaSource>? = null
        composeTestRule.setContent {
            TotumTheme {
                AllSubscriptionsContent(
                    sources = listOf(
                        SourceActivity(show, null),
                        SourceActivity(channel, null),
                        SourceActivity(other, null)
                    ),
                    onBack = {},
                    onUnsubscribe = { unsubscribed = it },
                    onOpen = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        longPress("A Show")
        composeTestRule.onNodeWithText("A Channel").performClick()
        composeTestRule.onNodeWithText(activity.getString(R.string.channel_unsubscribe)).performClick()
        assertEquals("nothing happens before confirming", null, unsubscribed)
        composeTestRule.onAllNodesWithText(activity.getString(R.string.channel_unsubscribe))[1].performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf(show, channel), unsubscribed)
    }

    @Test
    fun `holding the drag grip reorders rather than starting a selection`() {
        val container = FakeAppContainer()
        container.playbackQueue.playAll(
            listOf("One", "Two").mapIndexed { i, t -> PlayableItem(item("g$i", t), PlayHandle.Podcast()) }
        )
        composeTestRule.setContent {
            TotumTheme { ProvidePlayStates(container, onOpenSource = {}) { QueueScreen(container) } }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onAllNodesWithContentDescription(
            activity.getString(R.string.queue_reorder),
            useUnmergedTree = true
        )[0]
            .performTouchInput { longClick() }
        composeTestRule.waitForIdle()

        composeTestRule.onAllNodesWithTag(SELECTION_BAR_TAG).assertCountEquals(0)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
