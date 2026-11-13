package com.dewijones92.totum.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.R
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.podcast.fake.FakePodcastRepository
import com.dewijones92.totum.di.fake.FakeAppContainer
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.Subscription
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.common.MediaItemRow
import com.dewijones92.totum.ui.common.ProvidePlayStates
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class AddToGroupFromTheItemMenuTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val feedUrl = HttpUrl.of("https://example.com/feed.xml")
    private val feed = MediaSource.PodcastFeed(SourceId(feedUrl.value), "The Show", feedUrl)
    private val episode = MediaItem(
        id = MediaItemId("ep1"),
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
    private val torrentFile = MediaItem(
        id = MediaItemId("torrent:abc:1"),
        sourceId = SourceId("torrent"),
        title = "Film.2019.mkv",
        publishedAt = null,
        duration = null,
        mediaUrl = HttpUrl.of("https://home.test/torrent/stream/film.mkv"),
    )

    private fun container() = FakeAppContainer(
        podcastRepository = FakePodcastRepository(
            initialSubscriptions = listOf(Subscription(feed, Instant.EPOCH)),
            initialEpisodes = listOf(episode),
        ),
    )

    private fun show(container: FakeAppContainer, item: MediaItem, pillar: MediaKind) {
        composeTestRule.setContent {
            TotumTheme {
                ProvidePlayStates(container, onOpenSource = {}) {
                    MediaItemRow(item = item, subtitleLines = emptyList(), pillar = pillar, onPlay = {})
                }
            }
        }
        composeTestRule.onNodeWithContentDescription(context.getString(R.string.queue_menu)).performClick()
    }

    @Test
    fun aPodcastEpisodeOffersToAddItsShowAndTheGroupHoldsTheShow() {
        val container = container()
        show(container, episode, MediaKind.PODCAST)

        composeTestRule.onNodeWithText(context.getString(R.string.group_add_show)).performScrollTo().performClick()
        composeTestRule.waitUntil(TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(context.getString(R.string.groups_title))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNode(hasSetTextAction()).performTextInput("Podcasts")
        composeTestRule.onNodeWithText(context.getString(R.string.groups_create)).performClick()

        val saved = runBlocking {
            container.sourceGroupStore.observeGroups().first { groups -> groups.any { it.sources.isNotEmpty() } }
        }
        assertEquals(listOf(feed.id), saved.single().sources.map { it.id })
    }

    @Test
    fun aVideoOffersToAddItsChannel() {
        show(container(), video, MediaKind.VIDEO)

        composeTestRule.onNodeWithText(context.getString(R.string.group_add_channel)).assertExists()
    }

    @Test
    fun aTorrentFileHasNoChannelOrShowToGroup() {
        show(container(), torrentFile, MediaKind.PODCAST)

        composeTestRule.onAllNodesWithText(context.getString(R.string.group_add_channel)).assertCountEquals(0)
        composeTestRule.onAllNodesWithText(context.getString(R.string.group_add_show)).assertCountEquals(0)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
