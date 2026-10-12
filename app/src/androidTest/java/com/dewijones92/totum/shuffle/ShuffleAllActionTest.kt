package com.dewijones92.totum.shuffle

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.podcast.fake.FakePodcastRepository
import com.dewijones92.totum.di.fake.FakeAppContainer
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.innertube.channel.fake.FakeYouTubeChannel
import com.dewijones92.totum.innertube.feeds.FeedVideo
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.common.SHUFFLE_ALL_TAG
import com.dewijones92.totum.ui.common.ShuffleAllAction
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ShuffleAllActionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val channel = MediaSource.VideoChannel(
        SourceId("https://www.youtube.com/channel/UCabcdefghijklmnopqrstuv"),
        "A channel",
        HttpUrl.of("https://www.youtube.com/channel/UCabcdefghijklmnopqrstuv"),
    )
    private val feed = MediaSource.PodcastFeed(SourceId("pod"), "A podcast", HttpUrl.of("https://example.com/feed.xml"))

    private fun video(id: String, kind: FeedVideo.Kind = FeedVideo.Kind.VIDEO) = FeedVideo(
        videoId = id,
        title = id,
        author = "A channel",
        durationSeconds = 60,
        thumbnailUrl = null,
        watchUrl = HttpUrl.of("https://www.youtube.com/watch?v=$id"),
        kind = kind,
    )

    private fun queued(container: FakeAppContainer): List<Pair<String, String?>> =
        container.playbackQueue.state.value.entries.map { it.item.item.title to it.group?.id }

    private fun show(container: FakeAppContainer, target: ShuffleTarget) {
        composeTestRule.setContent {
            TotumTheme { ShuffleAllAction(container.shuffleAll, container.applicationScope, target, wide = true) }
        }
    }

    @Test
    fun aChannelAsksAboutShortsAndVideosOnlyLeavesThemOut() {
        val container = FakeAppContainer(
            youTubeChannel = FakeYouTubeChannel(
                videos = listOf(video("v1"), video("v2")),
                shorts = listOf(video("s1", FeedVideo.Kind.SHORT)),
            ),
        )
        show(container, ShuffleTarget("channel:x", "A channel", listOf(channel)))

        composeTestRule.onNodeWithTag(SHUFFLE_ALL_TAG).performClick()
        composeTestRule.onNodeWithText("Include Shorts?").assertExists()
        composeTestRule.onNodeWithText("Videos only").performClick()

        composeTestRule.waitUntil(5_000) { queued(container).size == 2 }
        assertEquals(setOf("v1", "v2"), queued(container).map { it.first }.toSet())
        assertEquals(setOf("shuffle:channel:x"), queued(container).map { it.second }.toSet())
    }

    @Test
    fun includingShortsQueuesThemToo() {
        val container = FakeAppContainer(
            youTubeChannel = FakeYouTubeChannel(
                videos = listOf(video("v1")),
                shorts = listOf(video("s1", FeedVideo.Kind.SHORT)),
            ),
        )
        show(container, ShuffleTarget("channel:x", "A channel", listOf(channel)))

        composeTestRule.onNodeWithTag(SHUFFLE_ALL_TAG).performClick()
        composeTestRule.onNodeWithText("Include Shorts").performClick()

        composeTestRule.waitUntil(5_000) { queued(container).size == 2 }
        assertEquals(setOf("v1", "s1"), queued(container).map { it.first }.toSet())
    }

    @Test
    fun aPodcastShufflesStraightAwayWithNoQuestion() {
        val episodes = listOf("e1", "e2", "e3").map { id ->
            MediaItem(
                id = MediaItemId(id),
                sourceId = feed.id,
                title = id,
                publishedAt = null,
                duration = null,
                mediaUrl = HttpUrl.of("https://example.com/$id.mp3"),
            )
        }
        val container = FakeAppContainer(podcastRepository = FakePodcastRepository(initialEpisodes = episodes))
        show(container, ShuffleTarget("podcast:pod", "A podcast", listOf(feed)))

        composeTestRule.onNodeWithTag(SHUFFLE_ALL_TAG).performClick()

        composeTestRule.onNodeWithText("Include Shorts?").assertDoesNotExist()
        composeTestRule.waitUntil(5_000) { queued(container).size == 3 }
        assertEquals(setOf("shuffle:podcast:pod"), queued(container).map { it.second }.toSet())
    }
}
