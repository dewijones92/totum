package com.dewijones92.totum.ui.videos

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.podcast.fake.FakePodcastRepository
import com.dewijones92.totum.di.fake.FakeAppContainer
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.SourceGroup
import com.dewijones92.totum.domain.SourceGroupId
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.common.SHUFFLE_ASK_GO_TAG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class GroupChipShuffleTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun feed(id: String) = MediaSource.PodcastFeed(SourceId(id), id, HttpUrl.of("https://example.com/$id.xml"))

    private fun episode(feed: MediaSource.PodcastFeed, id: String) = MediaItem(
        id = MediaItemId(id),
        sourceId = feed.id,
        title = id,
        publishedAt = null,
        duration = null,
        mediaUrl = HttpUrl.of("https://example.com/$id.mp3"),
    )

    @Test
    fun everyGroupChipHasAShuffleThatQueuesAndPlaysThatGroup() {
        val news = feed("news")
        val comedy = feed("comedy")
        val container = FakeAppContainer(
            podcastRepository = FakePodcastRepository(
                initialEpisodes = listOf(episode(news, "n1"), episode(news, "n2"), episode(comedy, "c1")),
            ),
        )
        val groups = listOf(
            SourceGroup(SourceGroupId("g-news"), "News", listOf(news)),
            SourceGroup(SourceGroupId("g-comedy"), "Comedy", listOf(comedy)),
        )
        composeTestRule.setContent {
            TotumTheme {
                FeedSelector(VideosViewModel.UiState(groups = groups), {}, {}, {}) { group ->
                    ShuffleGroup(container, group, wide = false)
                }
            }
        }

        composeTestRule.onNodeWithTag(groupChipShuffleTag("g-comedy")).assertExists()
        composeTestRule.onNodeWithTag(groupChipShuffleTag("g-news")).performClick()
        composeTestRule.onNodeWithTag(SHUFFLE_ASK_GO_TAG).performClick()

        composeTestRule.waitUntil(5_000) { container.playbackQueue.state.value.entries.size == 2 }
        val entries = container.playbackQueue.state.value.entries
        assertEquals(setOf("n1", "n2"), entries.map { it.item.item.title }.toSet())
        assertEquals(setOf("shuffle:group:g-news"), entries.map { it.group?.id }.toSet())
        composeTestRule.waitUntil(5_000) { container.playbackController.state.value != null }
        assertTrue(container.playbackController.state.value?.title in setOf("n1", "n2"))
    }
}
