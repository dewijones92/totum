package com.dewijones92.totum.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.R
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.podcast.PreviewResult
import com.dewijones92.totum.data.podcast.fake.FakePodcastRepository
import com.dewijones92.totum.di.fake.FakeAppContainer
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.Subscription
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.common.ItemActionSheet
import com.dewijones92.totum.ui.common.ItemActions
import com.dewijones92.totum.ui.common.LocalItemActions
import com.dewijones92.totum.ui.common.MediaItemRow
import com.dewijones92.totum.ui.common.ProvidePlayStates
import com.dewijones92.totum.ui.podcasts.PodcastFeedScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class GoToSourceGoesThereTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val feedUrl = HttpUrl.of("https://feeds.example.com/show.rss")
    private val feed = MediaSource.PodcastFeed(
        id = SourceId(feedUrl.value),
        title = "The Show",
        feedUrl = feedUrl,
        artworkUrl = HttpUrl.of("https://img.example.com/show.jpg"),
    )
    private val episode = MediaItem(
        id = MediaItemId("ep-1"),
        sourceId = feed.id,
        title = "Episode one",
        publishedAt = null,
        duration = null,
        author = "The Show",
        mediaUrl = HttpUrl.of("https://cdn.example.com/ep1.mp3"),
    )
    private val video = MediaItem(
        id = MediaItemId("abc123"),
        sourceId = SourceId("ytfeed:SUBSCRIPTIONS"),
        title = "A video",
        publishedAt = null,
        duration = null,
        mediaUrl = HttpUrl.of("https://www.youtube.com/watch?v=abc123"),
    )

    private fun subscribedContainer() = FakeAppContainer(
        podcastRepository = FakePodcastRepository(
            initialSubscriptions = listOf(Subscription(feed, Instant.EPOCH)),
            initialEpisodes = listOf(episode),
        ),
    )

    @Test
    fun `go to podcast from any row opens the feed of that podcast`() {
        var opened: MediaSource? = null
        composeTestRule.setContent {
            TotumTheme {
                ProvidePlayStates(subscribedContainer(), onOpenSource = { opened = it }) {
                    MediaItemRow(item = episode, subtitleLines = emptyList(), pillar = MediaKind.PODCAST, onPlay = {})
                }
            }
        }

        composeTestRule.onNodeWithContentDescription(context.getString(R.string.queue_menu)).performClick()
        composeTestRule.onNodeWithText(context.getString(R.string.go_to_podcast)).performClick()
        composeTestRule.waitUntil(TIMEOUT_MS) { opened != null }

        assertEquals(feed.id, opened?.id)
        assertEquals(true, opened is MediaSource.PodcastFeed)
    }

    @Test
    fun `go to podcast finds a feed you no longer follow`() {
        var opened: MediaSource? = null
        composeTestRule.setContent {
            TotumTheme {
                ProvidePlayStates(FakeAppContainer(), onOpenSource = { opened = it }) {
                    MediaItemRow(item = episode, subtitleLines = emptyList(), pillar = MediaKind.PODCAST, onPlay = {})
                }
            }
        }

        composeTestRule.onNodeWithContentDescription(context.getString(R.string.queue_menu)).performClick()
        composeTestRule.onNodeWithText(context.getString(R.string.go_to_podcast)).performClick()
        composeTestRule.waitUntil(TIMEOUT_MS) { opened != null }

        assertEquals(feedUrl, (opened as MediaSource.PodcastFeed).feedUrl)
    }

    @Test
    fun `the player menu for an episode goes to the podcast and offers no video switch`() {
        composeTestRule.setContent {
            TotumTheme {
                CompositionLocalProvider(LocalItemActions provides InertActions) {
                    ItemActionSheet(episode, onDismiss = {})
                }
            }
        }

        composeTestRule.onNodeWithText(context.getString(R.string.go_to_podcast)).assertExists()
        composeTestRule.onAllNodesWithText(context.getString(R.string.go_to_channel)).assertCountEquals(0)
        composeTestRule.onAllNodesWithText(context.getString(R.string.play_audio_only)).assertCountEquals(0)
        composeTestRule.onAllNodesWithText(context.getString(R.string.play_with_video)).assertCountEquals(0)
    }

    @Test
    fun `the player menu for a video still goes to the channel and can switch mode`() {
        composeTestRule.setContent {
            TotumTheme {
                CompositionLocalProvider(LocalItemActions provides InertActions) {
                    ItemActionSheet(video, onDismiss = {})
                }
            }
        }

        composeTestRule.onNodeWithText(context.getString(R.string.go_to_channel)).assertExists()
        composeTestRule.onNodeWithText(context.getString(R.string.play_audio_only)).assertExists()
    }

    @Test
    fun `the page of a podcast does not offer to go to itself`() {
        composeTestRule.setContent {
            TotumTheme {
                ProvidePlayStates(subscribedContainer(), onOpenSource = {}) {
                    PodcastFeedScreen(subscribedContainer(), feed, onBack = {})
                }
            }
        }

        composeTestRule.onNodeWithText("Episode one").assertExists()
        composeTestRule.onAllNodesWithContentDescription(
            context.getString(R.string.queue_menu)
        ).onFirst().performClick()
        composeTestRule.onNodeWithText(context.getString(R.string.queue_play_next)).assertExists()
        composeTestRule.onAllNodesWithText(context.getString(R.string.go_to_podcast)).assertCountEquals(0)
    }

    @Test
    fun `a feed you do not follow is previewed rather than shown empty`() {
        val repository = FakePodcastRepository().apply {
            previews[feedUrl] = PreviewResult.Loaded(feed.copy(title = "The Show, previewed"), listOf(episode))
        }
        val container = FakeAppContainer(podcastRepository = repository)
        composeTestRule.setContent {
            TotumTheme {
                ProvidePlayStates(container, onOpenSource = {}) {
                    PodcastFeedScreen(container, feed.copy(artworkUrl = null), onBack = {})
                }
            }
        }

        composeTestRule.waitUntil(TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText("Episode one").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("The Show, previewed").assertExists()
        composeTestRule.onNodeWithText(context.getString(R.string.channel_subscribe)).assertExists()
        composeTestRule.onNodeWithContentDescription("The Show, previewed").assertExists()
    }

    private object InertActions : ItemActions {
        override fun playNext(item: MediaItem) = Unit
        override fun addToQueue(item: MediaItem) = Unit
        override fun addToPlaylist(item: MediaItem) = Unit
        override fun peek(item: MediaItem) = Unit
        override fun download(item: MediaItem, audioOnly: Boolean) = Unit
        override fun deleteDownload(id: MediaItemId) = Unit
        override fun setPlayed(id: MediaItemId, played: Boolean) = Unit
        override fun goToSource(item: MediaItem) = Unit
        override fun openSource(source: MediaSource) = Unit
        override val audioMode: Boolean = false
        override fun switchMode(item: MediaItem) = Unit
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
